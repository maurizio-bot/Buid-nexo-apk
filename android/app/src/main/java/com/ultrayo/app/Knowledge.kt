package com.ultrayo.app

import android.content.Context
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    @Tool(description = "Busca en internet y lee las mejores páginas a la vez; devuelve los párrafos útiles con su enlace. Úsala para preguntas que piden una respuesta completa y actual.")
    fun investigarWeb(
        @ToolParam(description = "Lo que hay que averiguar") consulta: String,
    ): String = WebSearch.readTop(consulta, pages = 3).ifBlank { "No encontré nada o no hay conexión a internet." }

    @Tool(description = "El tiempo que hace y la previsión de hoy y mañana en una ciudad.")
    fun tiempo(
        @ToolParam(description = "La ciudad; vacío para la última que pidió Maurizio") lugar: String,
    ): String = runCatching { LiveInfo.weather(context, lugar.ifBlank { null }, tomorrow = false) }.getOrElse { "No pude ver el tiempo: ${it.message}" }

    @Tool(description = "Convierte dinero de una moneda a otra con el cambio oficial del día (Banco Central Europeo).")
    fun cambioMoneda(
        @ToolParam(description = "La cantidad, por ejemplo 100") cantidad: String,
        @ToolParam(description = "Moneda de origen: código (USD) o nombre (dólares)") de: String,
        @ToolParam(description = "Moneda de destino: código (EUR) o nombre (euros)") a: String,
    ): String = runCatching {
        LiveInfo.currency(cantidad.replace(',', '.').toDouble(), LiveInfo.currencyCode(de) ?: de.uppercase(), LiveInfo.currencyCode(a) ?: a.uppercase())
    }.getOrElse { "No pude convertirlo: ${it.message}" }

    @Tool(description = "Los últimos titulares de noticias, en general o sobre un tema.")
    fun noticias(
        @ToolParam(description = "El tema; vacío para las noticias principales") tema: String,
    ): String = runCatching { LiveInfo.news(tema.ifBlank { null }) }.getOrElse { "No pude ver las noticias: ${it.message}" }

    @Tool(description = "El resumen de Wikipedia sobre un tema, persona o lugar.")
    fun wikipedia(
        @ToolParam(description = "El tema, persona o lugar") tema: String,
    ): String = runCatching { LiveInfo.wiki(tema) }.getOrElse { "No lo encontré en Wikipedia: ${it.message}" }

    @Tool(description = "Busca en lo que has aprendido de Maurizio y por tu cuenta (recuerdos, reglas, investigaciones), por parecido.")
    fun recordar(
        @ToolParam(description = "De qué se trata") tema: String,
    ): String = Recall.get(context).relevant(tema, k = 6).joinToString("\n") { "- ${it.text}" }.ifEmpty { "No recuerdo nada sobre eso." }

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
