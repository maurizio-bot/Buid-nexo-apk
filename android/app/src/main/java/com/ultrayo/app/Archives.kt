package com.ultrayo.app

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * Archivos comprimidos: ver qué llevan dentro y descomprimirlos en la carpeta de Ultra Yo
 * (Documentos/Ultra Yo/archivos/descomprimidos). El comprimido original no se toca.
 * Sabe abrir zip, 7z, tar, gz, bz2 y xz (y sus combinaciones como tar.gz).
 */
object Archives {

    private const val MAX_ENTRIES = 5000
    private const val MAX_TOTAL = 2L shl 30 // 2 GB descomprimidos como mucho
    private const val MAX_LISTED = 25

    private val SUFFIXES = listOf(
        ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tbz", ".tar.xz", ".txz",
        ".zip", ".jar", ".7z", ".tar", ".gz", ".bz2", ".xz",
    )

    fun isArchive(name: String): Boolean = suffix(name) != null || name.lowercase().endsWith(".rar")

    /** Un vistazo a lo que hay dentro, sin descomprimir nada. */
    fun describe(context: Context, uri: Uri, name: String): String = withLocalCopy(context, uri, name) { file ->
        val entries = mutableListOf<Pair<String, Long>>()
        forEachEntry(file, name) { entryName, size, _ ->
            entries += entryName to size
            true
        }
        summary(name, entries, destination = null)
    }

    /** Descomprime en la carpeta de Ultra Yo. Devuelve la carpeta creada y un resumen de lo que había. */
    fun extract(context: Context, uri: Uri, name: String): Pair<File, String> = withLocalCopy(context, uri, name) { file ->
        val dest = uniqueDir(File(Vault.files(context), "descomprimidos"), baseName(name))
        val root = dest.canonicalPath + File.separator
        val entries = mutableListOf<Pair<String, Long>>()
        var total = 0L
        forEachEntry(file, name) { entryName, _, input ->
            val target = File(dest, entryName)
            // Nada de rutas tipo "../../" que se salgan de la carpeta.
            if (!target.canonicalPath.startsWith(root)) return@forEachEntry true
            target.parentFile?.mkdirs()
            val written = target.outputStream().use { out -> copyLimited(input, out, MAX_TOTAL - total) }
            total += written
            entries += entryName to written
            if (total >= MAX_TOTAL) error("descomprimido sería más de 2 GB; he parado")
            true
        }
        dest to summary(name, entries, destination = dest)
    }

    private fun <T> withLocalCopy(context: Context, uri: Uri, name: String, work: (File) -> T): T {
        if (name.lowercase().endsWith(".rar")) error("los .rar no los sé abrir todavía; sé abrir zip, 7z, tar, gz, bz2 y xz")
        if (uri.scheme == "file") return work(File(uri.path!!))
        // 7z y algunos zip necesitan poder saltar dentro del archivo: se trabaja con una copia temporal.
        val tmp = File(context.cacheDir, "comprimido-${System.currentTimeMillis()}-${name.takeLast(40).replace('/', '_')}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: error("no pude abrir el archivo")
            return work(tmp)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Recorre los archivos de dentro (sin carpetas). [visit] recibe el nombre, el tamaño (o -1)
     * y el contenido; devuelve false para parar.
     */
    private fun forEachEntry(file: File, name: String, visit: (String, Long, InputStream) -> Boolean) {
        var count = 0
        fun next(entryName: String, size: Long, input: InputStream): Boolean {
            if (++count > MAX_ENTRIES) error("tiene más de $MAX_ENTRIES archivos; he parado")
            return visit(entryName.trimStart('/'), size, input)
        }
        when (val s = suffix(name) ?: error("no es un comprimido que sepa abrir")) {
            ".7z" -> @Suppress("DEPRECATION") SevenZFile(file).use { z ->
                while (true) {
                    val entry = z.nextEntry ?: break
                    if (entry.isDirectory || entry.isAntiItem) continue
                    if (!next(entry.name, entry.size, z.getInputStream(entry))) break
                }
            }
            ".zip", ".jar" -> archive(ZipArchiveInputStream(BufferedInputStream(file.inputStream())), ::next)
            ".tar" -> archive(TarArchiveInputStream(BufferedInputStream(file.inputStream())), ::next)
            else -> {
                val compressed = decompress(s, BufferedInputStream(file.inputStream()))
                if (s.startsWith(".t")) {
                    archive(TarArchiveInputStream(compressed), ::next)
                } else {
                    // Un solo archivo comprimido (por ejemplo informe.txt.gz)
                    compressed.use { next(baseName(name), -1, it) }
                }
            }
        }
    }

    private fun archive(stream: ArchiveInputStream<*>, next: (String, Long, InputStream) -> Boolean) = stream.use { a ->
        while (true) {
            val entry = a.nextEntry ?: break
            if (entry.isDirectory) continue
            if (!a.canReadEntryData(entry)) error("\"${entry.name}\" está protegido con contraseña o usa un formato que no sé abrir")
            if (!next(entry.name, entry.size, a)) break
        }
    }

    private fun decompress(suffix: String, input: InputStream): InputStream = when (suffix) {
        ".tar.gz", ".tgz", ".gz" -> GzipCompressorInputStream(input, true)
        ".tar.bz2", ".tbz2", ".tbz", ".bz2" -> BZip2CompressorInputStream(input, true)
        else -> XZCompressorInputStream(input, true)
    }

    /** Copia sin cerrar [input] (es el comprimido entero) y sin pasar de [max] bytes. */
    private fun copyLimited(input: InputStream, out: java.io.OutputStream, max: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < max) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), max - total).toInt())
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return total
    }

    private fun summary(name: String, entries: List<Pair<String, Long>>, destination: File?): String = buildString {
        val total = entries.sumOf { it.second.coerceAtLeast(0) }
        append(name).append(": ").append(entries.size).append(if (entries.size == 1) " archivo" else " archivos")
        if (total > 0) append(", ").append(human(total)).append(" descomprimido")
        append(".\n")
        destination?.let { append("Descomprimido en ").append(it.path).append(". Para leer un archivo usa la ruta Ultra Yo/")
            .append(it.relativeTo(it.parentFile!!.parentFile!!.parentFile!!).path).append("/<archivo>.\n") }
        val types = entries.groupingBy { DocReader.extension(it.first).ifEmpty { "sin extensión" } }.eachCount()
            .entries.sortedByDescending { it.value }
        append("Tipos: ").append(types.joinToString { "${it.value} ${it.key}" }).append(".\n")
        entries.take(MAX_LISTED).forEach { (entry, size) ->
            append("- ").append(entry)
            if (size >= 0) append(" (").append(human(size)).append(')')
            append('\n')
        }
        if (entries.size > MAX_LISTED) append("… y ").append(entries.size - MAX_LISTED).append(" más.\n")
    }

    private fun suffix(name: String): String? = SUFFIXES.firstOrNull { name.lowercase().endsWith(it) }

    private fun baseName(name: String): String {
        val s = suffix(name) ?: return name
        val base = name.dropLast(s.length).ifBlank { "comprimido" }
        // "informe.gz" -> "informe"; "fotos.tgz" -> "fotos"
        return base.substringAfterLast('/')
    }

    private fun uniqueDir(parent: File, name: String): File {
        var dir = File(parent, name)
        var n = 2
        while (dir.exists()) dir = File(parent, "$name ($n)").also { n++ }
        dir.mkdirs()
        return dir
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    }
}
