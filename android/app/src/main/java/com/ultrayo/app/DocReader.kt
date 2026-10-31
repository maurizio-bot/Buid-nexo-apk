package com.ultrayo.app

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

const val MAX_DOC_TEXT = 6000

/**
 * Saca el texto de un archivo para que la IA lo lea: texto plano, PDF, Word (docx y, a medias, doc),
 * Excel, PowerPoint, OpenDocument, RTF, EPUB y HTML. Solo lee, nunca cambia el archivo.
 */
object DocReader {

    val TEXT = setOf(
        "txt", "md", "csv", "tsv", "json", "xml", "log", "kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h",
        "css", "yml", "yaml", "ini", "cfg", "conf", "gradle", "sh", "bat", "sql", "properties", "srt", "vtt",
    )
    private val ZIPPED = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "epub")
    private val OTHER = setOf("pdf", "doc", "rtf", "html", "htm")

    /** Formatos que sabe leer, para decírselo a Maurizio. */
    const val FORMATS = "txt, pdf (también escaneados), doc, docx, xlsx, pptx, odt, rtf, epub, html, csv, json, código y el texto de las fotos"

    fun canRead(name: String, mime: String? = null): Boolean {
        val ext = extension(name)
        return ext in TEXT || ext in ZIPPED || ext in OTHER || ext in Ocr.IMAGES ||
            mime.orEmpty().startsWith("text/") || mime.orEmpty().startsWith("image/")
    }

    /** El texto de [uri], recortado a [limit] caracteres. Lanza una excepción si no puede leerlo. */
    fun read(context: Context, uri: Uri, name: String, mime: String? = null, limit: Int = MAX_DOC_TEXT): String {
        val ext = extension(name)
        if (ext in Ocr.IMAGES || mime.orEmpty().startsWith("image/")) {
            return finish(Ocr.read(context, uri), limit, "no veo ningún texto en esa imagen")
        }
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            when {
                ext == "pdf" -> pdf(context, stream, limit)
                ext == "docx" -> zipped(stream, limit) { it == "word/document.xml" }
                ext == "xlsx" -> excel(stream, limit)
                ext == "pptx" -> zipped(stream, limit) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                ext in setOf("odt", "ods", "odp") -> zipped(stream, limit) { it == "content.xml" }
                ext == "epub" -> zipped(stream, limit) { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }
                ext == "doc" -> legacyWord(stream.readBytes())
                ext == "rtf" -> rtf(plain(stream, limit * 4))
                ext == "html" || ext == "htm" -> html(plain(stream, limit * 4))
                ext in TEXT || mime.orEmpty().startsWith("text/") -> plain(stream, limit)
                else -> error("no sé leer archivos .$ext (sé leer $FORMATS)")
            }
        } ?: error("no pude abrir el archivo")
        // Un PDF escaneado no lleva texto dentro: se lee mirando sus páginas.
        if (ext == "pdf" && text.isBlank()) return finish(Ocr.readPdf(context, uri, limit), limit, "no veo texto en ese PDF")
        return finish(text, limit, "el archivo no tiene texto que pueda leer")
    }

    private fun finish(text: String, limit: Int, empty: String): String {
        val clean = text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (clean.isEmpty()) error(empty)
        return if (clean.length > limit) clean.take(limit) + "\n[…archivo recortado…]" else clean
    }

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun plain(stream: InputStream, limit: Int): String {
        val reader = stream.bufferedReader()
        val buffer = CharArray(limit)
        var total = 0
        while (total < limit) {
            val read = reader.read(buffer, total, limit - total)
            if (read <= 0) break
            total += read
        }
        return String(buffer, 0, total)
    }

    private var pdfReady = false

    private fun pdf(context: Context, stream: InputStream, limit: Int): String {
        if (!pdfReady) {
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfReady = true
        }
        return PDDocument.load(stream).use { doc ->
            val stripper = PDFTextStripper()
            // Solo las primeras páginas: el modelo no puede leer más de unas pocas a la vez.
            stripper.endPage = minOf(doc.numberOfPages, 1 + limit / 1500)
            stripper.getText(doc)
        }
    }

    /** Los formatos de Office modernos y OpenDocument son un zip de XML: junta el texto de los XML que interesan. */
    private fun zipped(stream: InputStream, limit: Int, wanted: (String) -> Boolean): String {
        val parts = sortedMapOf<String, String>(compareBy<String>({ it.length }, { it }))
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && wanted(entry.name)) {
                    parts[entry.name] = xmlText(ByteArrayInputStream(zip.readBytes()))
                    if (parts.values.sumOf { it.length } > limit) break
                }
            }
        }
        return parts.values.joinToString("\n\n")
    }

    /** Las hojas de Excel guardan los textos aparte (sharedStrings) y en cada celda solo su número. */
    private fun excel(stream: InputStream, limit: Int): String {
        var shared = emptyList<String>()
        val sheets = sortedMapOf<String, ByteArray>(compareBy<String>({ it.length }, { it }))
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == "xl/sharedStrings.xml" -> shared = xmlText(ByteArrayInputStream(zip.readBytes())).split('\n')
                    entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml") -> sheets[entry.name] = zip.readBytes()
                }
            }
        }
        val out = StringBuilder()
        for ((name, bytes) in sheets) {
            out.append("Hoja ").append(name.substringAfterLast("sheet").removeSuffix(".xml")).append(":\n")
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(bytes), null)
            var sharedCell = false
            var inValue = false
            while (parser.next() != XmlPullParser.END_DOCUMENT && out.length < limit) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "c" -> sharedCell = parser.getAttributeValue(null, "t") == "s"
                        "v", "t" -> inValue = true
                    }
                    XmlPullParser.TEXT -> if (inValue) {
                        val value = parser.text
                        out.append(if (sharedCell) shared.getOrNull(value.trim().toIntOrNull() ?: -1) ?: value else value).append('\t')
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "v", "t" -> inValue = false
                        "row" -> out.append('\n')
                    }
                }
            }
        }
        return out.toString()
    }

    /** El texto de un XML, con saltos de línea en párrafos, filas y diapositivas. */
    private fun xmlText(stream: InputStream): String {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)
        val out = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.TEXT -> out.append(parser.text)
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "tab", "c" -> out.append('\t')
                    "br", "cr" -> out.append('\n')
                }
                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "p", "h", "row", "si", "tr", "li", "div", "h1", "h2", "h3" -> out.append('\n')
                }
            }
        }
        return out.toString()
    }

    /**
     * Los .doc antiguos son binarios. Sin una librería enorme solo se puede rescatar el texto
     * buscando tramos legibles; suele bastar para saber de qué trata.
     */
    private fun legacyWord(bytes: ByteArray): String {
        val runs = mutableListOf<String>()
        // Texto en UTF-16 (lo normal en Word 97 y posteriores)
        val utf16 = StringBuilder()
        var i = 0
        while (i + 1 < bytes.size) {
            val ch = ((bytes[i + 1].toInt() and 0xff) shl 8 or (bytes[i].toInt() and 0xff)).toChar()
            if (ch == '\r' || ch == '\n' || (ch >= ' ' && Character.isLetterOrDigit(ch) || ch in " .,;:¿?¡!()-'\"%€$/")) {
                utf16.append(if (ch == '\r') '\n' else ch)
            } else {
                if (utf16.length >= 20) runs += utf16.toString()
                utf16.clear()
            }
            i += 2
        }
        if (utf16.length >= 20) runs += utf16.toString()
        if (runs.sumOf { it.length } > 100) return runs.joinToString("\n")
        // Texto de un solo byte (Word 6/95)
        return String(bytes, Charsets.ISO_8859_1)
            .split(Regex("[^\\p{L}\\p{N} .,;:¿?¡!()\\-'\"%€$/\\r\\n]+"))
            .filter { it.length >= 20 }
            .joinToString("\n")
    }

    private fun rtf(raw: String): String = raw
        .replace(Regex("\\\\'([0-9a-fA-F]{2})")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("\\\\(par|line)\\b ?"), "\n")
        .replace(Regex("\\{\\\\\\*[^{}]*\\}"), "")
        .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
        .replace(Regex("[{}]"), "")

    private fun html(raw: String): String = raw
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<(br|/p|/div|/li|/h\\d|/tr)[^>]*>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
}
