package com.ultrayo.app

import android.content.Context
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Herramientas para que Ultra Yo compruebe en vez de "saber": buscar en internet (gratis, sin
 * clave), leer una página, hacer cuentas exactas y buscar en los documentos de Maurizio.
 */
class KnowledgeTools(context: Context) : ToolSet {

    private val context = context.applicationContext

    @Tool(description = "Busca en internet (gratis) y devuelve los primeros resultados con título, enlace y resumen. Úsala para datos actuales, noticias, precios, horarios o cualquier cosa que no sepas seguro.")
    fun buscarWeb(
        @ToolParam(description = "Lo que hay que buscar, en pocas palabras") consulta: String,
    ): String = WebSearch.search(consulta).ifEmpty { listOf("No encontré nada o no hay conexión a internet.") }.joinToString("\n")

    @Tool(description = "Lee el texto de una página web por su enlace (por ejemplo uno que devolvió buscarWeb).")
    fun leerWeb(
        @ToolParam(description = "El enlace completo, empezando por https://") enlace: String,
    ): String = runCatching { WebSearch.read(enlace) }.getOrElse { "No pude leer la página: ${it.message}" }

    @Tool(description = "Hace una cuenta exacta. Úsala siempre que haya números en vez de calcular de cabeza. Admite + - * / ^ %, paréntesis, raiz, sin, cos, tan, log, ln, abs, redondear, pi y e.")
    fun calcular(
        @ToolParam(description = "La expresión, por ejemplo (1200*0.21)+35 o raiz(2)") expresion: String,
    ): String = runCatching { "${expresion.trim()} = ${Calculator.format(Calculator.eval(expresion))}" }
        .getOrElse { "No pude calcular eso: ${it.message}" }

    @Tool(description = "Busca dentro del contenido de los documentos de Maurizio (su carpeta Ultra Yo y las carpetas que te ha dejado leer) y devuelve los fragmentos más relevantes con el nombre del archivo. Cita el archivo al contestar.")
    fun buscarEnDocumentos(
        @ToolParam(description = "La pregunta o las palabras clave") pregunta: String,
    ): String {
        if (Assistant.locked()) return "El teléfono está bloqueado: no puedo mirar sus documentos."
        return DocIndex.formatted(context, pregunta)
    }
}

/** Búsqueda web gratuita: DuckDuckGo (versión HTML) y, si no da nada, Wikipedia. */
object WebSearch {

    private const val AGENT = "Mozilla/5.0 (Linux; Android 14) UltraYo"

    fun search(query: String, limit: Int = 5): List<String> {
        val ddg = runCatching { duckDuckGo(query, limit) }.getOrDefault(emptyList())
        if (ddg.isNotEmpty()) return ddg
        return runCatching { wikipedia(query, limit) }.getOrDefault(emptyList())
    }

    private fun duckDuckGo(query: String, limit: Int): List<String> {
        val html = get("https://html.duckduckgo.com/html/?kl=es-es&q=" + URLEncoder.encode(query, "UTF-8"))
        val links = Regex("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { clean(it.groupValues[1]) }.toList()
        return links.take(limit).mapIndexed { i, m ->
            var href = m.groupValues[1].replace("&amp;", "&")
            Regex("uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (href.startsWith("//")) href = "https:$href"
            "${i + 1}. ${clean(m.groupValues[2])} — $href\n   ${snippets.getOrElse(i) { "" }}"
        }.filterNot { it.contains("duckduckgo.com/y.js") }
    }

    private fun wikipedia(query: String, limit: Int): List<String> {
        val url = "https://es.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=$limit&srsearch=" +
            URLEncoder.encode(query, "UTF-8")
        val results = JSONObject(get(url)).getJSONObject("query").getJSONArray("search")
        return List(results.length()) { i ->
            val r = results.getJSONObject(i)
            val title = r.getString("title")
            "${i + 1}. $title (Wikipedia) — https://es.wikipedia.org/wiki/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}\n   ${clean(r.optString("snippet"))}"
        }
    }

    /** El texto legible de una página, recortado para que quepa en la conversación. */
    fun read(url: String, limit: Int = 3500): String {
        require(url.startsWith("http://") || url.startsWith("https://")) { "el enlace tiene que empezar por https://" }
        val text = clean(
            get(url)
                .replace(Regex("<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
                .replace(Regex("<(br|p|div|li|h[1-6]|tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        )
        return text.lines().map { it.trim() }.filter { it.length > 1 }.joinToString("\n").take(limit).ifBlank { "La página no tiene texto que pueda leer." }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 7000
        c.readTimeout = 9000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", AGENT)
        c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
        try {
            if (c.responseCode !in 200..299) error("la web respondió ${c.responseCode}")
            return c.inputStream.bufferedReader().use { r ->
                val buf = CharArray(400_000)
                var n = 0
                while (n < buf.size) {
                    val read = r.read(buf, n, buf.size - n)
                    if (read < 0) break
                    n += read
                }
                String(buf, 0, n)
            }
        } finally {
            c.disconnect()
        }
    }

    private fun clean(html: String): String = html
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n")
        .trim()
}

/** Calculadora exacta (no se equivoca como el modelo al hacer cuentas de cabeza). */
object Calculator {

    /** "Cuánto es 25 por 4 más 3" → 25*4+3. Devuelve null si no parece una cuenta. */
    fun fromSpeech(text: String): String? {
        val m = Regex("^(?:cu[aá]nto (?:es|son|da|sale)|calcula(?:me)?|calc[uú]lame)\\s+(.+?)[?.!]*$", RegexOption.IGNORE_CASE).find(text.trim()) ?: return null
        var e = " " + m.groupValues[1].lowercase(Locale.ROOT) + " "
        listOf(
            "multiplicado por" to "*", "dividido (?:por|entre)" to "/", "elevado a" to "^", "al cuadrado" to "^2", "al cubo" to "^3",
            "ra[ií]z cuadrada de" to "raiz", "ra[ií]z de" to "raiz", "por ciento de" to "%*", "por ciento" to "%",
            "por" to "*", "entre" to "/", "m[aá]s" to "+", "menos" to "-", "x" to "*", "×" to "*", "÷" to "/",
        ).forEach { (w, op) -> e = e.replace(Regex("(?<=[\\s\\d)])$w(?=[\\s\\d(])"), " $op ") }
        e = e.trim()
        // Solo números, operadores y funciones conocidas; si queda otra palabra, no es una cuenta.
        if (!Regex("\\d").containsMatchIn(e) || Regex("[a-zñáéíóú]+").findAll(e).any { it.value !in FUNCTIONS && it.value !in CONSTANTS }) return null
        return e
    }

    fun eval(expression: String): Double = Parser(
        expression.lowercase(Locale.ROOT).replace(Regex("(\\d),(\\d)"), "$1.$2").replace("×", "*").replace("÷", "/").replace(" ", "")
    ).parse()

    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "no tiene un resultado definido"
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) return String.format(Locale.forLanguageTag("es-ES"), "%,d", v.toLong())
        return String.format(Locale.forLanguageTag("es-ES"), "%,.6f", v).trimEnd('0').trimEnd(',')
    }

    private val FUNCTIONS = setOf("raiz", "sqrt", "sin", "sen", "cos", "tan", "log", "ln", "abs", "redondear", "round")
    private val CONSTANTS = setOf("pi", "e")

    private class Parser(val s: String) {
        var i = 0

        fun parse(): Double {
            val v = expr()
            if (i < s.length) error("no entiendo \"${s.substring(i)}\"")
            return v
        }

        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                lastPercent = false
                val t = term()
                // "200+10%" = 200 más el 10 % de 200
                v = if (lastPercent) { if (op == '+') v * (1 + t) else v * (1 - t) } else if (op == '+') v + t else v - t
                lastPercent = false
            }
            return v
        }

        var lastPercent = false

        fun term(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val p = power()
                v = if (op == '*') v * p else v / p
                lastPercent = false
            }
            return v
        }

        fun power(): Double {
            val base = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return Math.pow(base, power())
            }
            return base
        }

        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            var v = atom()
            if (i < s.length && s[i] == '%') {
                i++
                v /= 100
                lastPercent = true
            }
            return v
        }

        fun atom(): Double {
            if (i >= s.length) error("falta un número al final")
            if (s[i] == '(') {
                i++
                val v = expr()
                if (i >= s.length || s[i] != ')') error("falta cerrar un paréntesis")
                i++
                return v
            }
            if (s[i].isDigit() || s[i] == '.') {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                return s.substring(start, i).toDouble()
            }
            if (s[i].isLetter()) {
                val start = i
                while (i < s.length && s[i].isLetter()) i++
                val name = s.substring(start, i)
                when (name) {
                    "pi" -> return Math.PI
                    "e" -> return Math.E
                }
                val arg = atom()
                return when (name) {
                    "raiz", "sqrt" -> Math.sqrt(arg)
                    "sin", "sen" -> Math.sin(Math.toRadians(arg))
                    "cos" -> Math.cos(Math.toRadians(arg))
                    "tan" -> Math.tan(Math.toRadians(arg))
                    "log" -> Math.log10(arg)
                    "ln" -> Math.log(arg)
                    "abs" -> Math.abs(arg)
                    "redondear", "round" -> Math.rint(arg)
                    else -> error("no conozco la función $name")
                }
            }
            error("no entiendo \"${s[i]}\"")
        }
    }
}

/**
 * Índice del contenido de los documentos de Maurizio para encontrar lo relevante (búsqueda
 * BM25 por fragmentos). Se guarda en la caché y solo vuelve a leer los archivos que cambian.
 */
object DocIndex {

    private const val CHUNK = 900
    private const val OVERLAP = 150
    private const val MAX_FILES = 400
    private const val MAX_TEXT_PER_FILE = 80_000

    private data class Chunk(val file: String, val part: Int, val text: String, val terms: Map<String, Int>, val length: Int)
    private data class FileEntry(val path: String, val modified: Long, val chunks: List<Chunk>)

    private var files = mutableMapOf<String, FileEntry>()
    private var loaded = false

    private val STOP = setOf(
        "que", "los", "las", "del", "una", "uno", "unos", "unas", "por", "para", "con", "sin", "como", "pero", "mas", "este",
        "esta", "esto", "ese", "esa", "eso", "son", "fue", "ser", "hay", "sus", "mis", "tus", "nos", "les", "lo", "le", "se",
        "donde", "cuando", "cual", "quien", "sobre", "entre", "todo", "toda", "muy", "ya", "the", "and", "for", "dime", "busca",
        "documentos", "documento", "archivos", "archivo", "mis",
    )

    fun terms(text: String): List<String> =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .split(Regex("[^a-z0-9ñ]+")).filter { it.length > 2 && it !in STOP }

    /** Los fragmentos más relevantes, ya listos para dárselos al modelo con su archivo de origen. */
    fun formatted(context: Context, query: String, limit: Int = 4): String {
        val hits = search(context, query, limit)
        if (hits.isEmpty()) {
            return if (files.isEmpty()) "No hay documentos que pueda leer. Maurizio puede darme acceso a una carpeta en Ajustes o guardar archivos en mi carpeta."
            else "No encontré nada sobre eso en sus documentos (${files.size} revisados)."
        }
        return hits.mapIndexed { i, c -> "[${i + 1}] ${c.file} (parte ${c.part}):\n${c.text.trim()}" }.joinToString("\n\n")
    }

    @Synchronized
    private fun search(context: Context, query: String, limit: Int): List<Chunk> {
        refresh(context)
        val q = terms(query).distinct()
        if (q.isEmpty()) return emptyList()
        val all = files.values.flatMap { it.chunks }
        if (all.isEmpty()) return emptyList()
        val avg = all.sumOf { it.length }.toDouble() / all.size
        val df = q.associateWith { t -> all.count { t in it.terms } }
        val n = all.size
        return all.map { c ->
            var score = 0.0
            for (t in q) {
                val f = c.terms[t] ?: continue
                val idf = ln(1 + (n - df.getValue(t) + 0.5) / (df.getValue(t) + 0.5))
                score += idf * (f * 2.2) / (f + 1.2 * (0.25 + 0.75 * c.length / avg))
            }
            // Que aparezca en el nombre del archivo también cuenta.
            score += q.count { c.file.lowercase(Locale.ROOT).contains(it) } * 0.8
            c to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /** Vuelve a leer solo los documentos nuevos o cambiados. */
    private fun refresh(context: Context) {
        if (!loaded) {
            load(context)
            loaded = true
        }
        val docs = DeviceFiles.search(context, "", "documento", limit = MAX_FILES)
        val seen = mutableSetOf<String>()
        var changed = false
        for (doc in docs) {
            seen += doc.path
            val old = files[doc.path]
            if (old != null && old.modified == doc.modified) continue
            val text = runCatching { DeviceFiles.readText(context, doc, limit = MAX_TEXT_PER_FILE) }.getOrNull() ?: continue
            files[doc.path] = FileEntry(doc.path, doc.modified, chunk(doc.path, text))
            changed = true
        }
        if (files.keys.retainAll(seen)) changed = true
        if (changed) save(context)
    }

    private fun chunk(path: String, text: String): List<Chunk> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        val out = mutableListOf<Chunk>()
        var start = 0
        var part = 1
        while (start < clean.length) {
            var end = min(clean.length, start + CHUNK)
            // Cortar en un final de frase si hay uno cerca.
            if (end < clean.length) {
                val dot = clean.lastIndexOf(". ", end)
                if (dot > start + CHUNK / 2) end = dot + 1
            }
            val piece = clean.substring(start, end)
            val t = terms(piece)
            out += Chunk(path.substringAfterLast('/'), part++, piece, t.groupingBy { it }.eachCount(), max(1, t.size))
            if (end >= clean.length) break
            start = max(end - OVERLAP, start + 1)
        }
        return out
    }

    private fun cache(context: Context) = File(context.cacheDir, "indice-documentos.json")

    private fun save(context: Context) = runCatching {
        val arr = JSONArray()
        files.values.forEach { f ->
            arr.put(JSONObject().put("path", f.path).put("modified", f.modified).put("chunks", JSONArray(f.chunks.map { it.text })))
        }
        cache(context).writeText(arr.toString())
    }

    private fun load(context: Context) = runCatching {
        val arr = JSONArray(cache(context).takeIf { it.exists() }?.readText() ?: return@runCatching)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val path = o.getString("path")
            val texts = o.getJSONArray("chunks")
            val chunks = List(texts.length()) { j ->
                val piece = texts.getString(j)
                val t = terms(piece)
                Chunk(path.substringAfterLast('/'), j + 1, piece, t.groupingBy { it }.eachCount(), max(1, t.size))
            }
            files[path] = FileEntry(path, o.getLong("modified"), chunks)
        }
    }
}
