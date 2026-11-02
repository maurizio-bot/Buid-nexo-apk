package com.ultrayo.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Un resultado de búsqueda. */
data class Hit(val title: String, val url: String, val snippet: String)

/**
 * Caché de internet en el teléfono: lo consultado hace poco sale al instante, sin volver a
 * descargarlo. Cada respuesta caduca según lo rápido que cambia (el tiempo, en minutos; una
 * página, en un día). Entra en el límite de espacio de la memoria (ver [Recall]).
 */
object WebCache {

    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        if (dir == null) dir = File(context.cacheDir, "web").apply { mkdirs() }
    }

    private fun file(key: String): File? {
        val d = dir ?: return null
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(d, hash)
    }

    fun get(key: String, maxAgeMs: Long): String? {
        val f = file(key) ?: return null
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > maxAgeMs) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun put(key: String, value: String) {
        runCatching { file(key)?.writeText(value) }
    }

    fun bytes(): Long = dir?.listFiles()?.sumOf { it.length() } ?: 0

    /** Borra lo más antiguo hasta quedarse en [maxBytes]. */
    fun trim(maxBytes: Long) {
        val files = dir?.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    fun clear() = trim(0)
}

/** Internet gratis y sin claves: buscar, leer páginas y quedarse con lo útil, todo en paralelo. */
object WebSearch {

    private const val AGENT = "Mozilla/5.0 (Linux; Android 14) UltraYo"
    private const val HOUR = 3_600_000L

    private val pool = Executors.newFixedThreadPool(4)

    /** Busca en DuckDuckGo y en Wikipedia a la vez; primero los de DuckDuckGo. */
    fun hits(query: String, limit: Int = 6): List<Hit> {
        val ddg = pool.submit(Callable { runCatching { duckDuckGo(query, limit) }.getOrDefault(emptyList()) })
        val wiki = pool.submit(Callable { runCatching { wikipedia(query, 2) }.getOrDefault(emptyList()) })
        val a = runCatching { ddg.get(12, TimeUnit.SECONDS) }.getOrDefault(emptyList())
        val b = runCatching { wiki.get(12, TimeUnit.SECONDS) }.getOrDefault(emptyList())
        return (a + b.filter { w -> a.none { it.url == w.url } }).take(limit)
    }

    /** Los resultados ya escritos para dárselos al modelo. */
    fun search(query: String, limit: Int = 5): List<String> =
        hits(query, limit).mapIndexed { i, h -> "${i + 1}. ${h.title} — ${h.url}\n   ${h.snippet}" }

    /**
     * Busca, abre a la vez las [pages] mejores páginas y saca de cada una los párrafos que más
     * tienen que ver con la pregunta. Devuelve esos párrafos con su enlace, o "" si no hay nada.
     */
    fun readTop(query: String, pages: Int = 3, perPage: Int = 1200): String {
        val found = hits(query, pages + 3)
        if (found.isEmpty()) return ""
        val chosen = found.filterNot { it.url.contains("youtube.com") || it.url.endsWith(".pdf") }.take(pages)
        val words = DocIndex.terms(query).toSet()
        val jobs = chosen.map { hit -> hit to pool.submit(Callable { runCatching { usefulParts(read(hit.url, 60_000), words, perPage) }.getOrDefault("") }) }
        val parts = jobs.mapIndexed { i, (hit, job) ->
            val text = runCatching { job.get(15, TimeUnit.SECONDS) }.getOrDefault("").ifBlank { hit.snippet }
            "[${i + 1}] ${hit.title} — ${hit.url}\n$text"
        }
        return parts.joinToString("\n\n")
    }

    /** Los párrafos con más palabras de la pregunta, en su orden original, hasta [limit] letras. */
    private fun usefulParts(text: String, words: Set<String>, limit: Int): String {
        val paragraphs = text.lines().map { it.trim() }.filter { it.length > 60 }
        if (paragraphs.isEmpty()) return text.take(limit)
        val ranked = paragraphs.withIndex().sortedByDescending { (_, p) -> DocIndex.terms(p).count { it in words } }
        val picked = mutableListOf<IndexedValue<String>>()
        var size = 0
        for (p in ranked) {
            if (size + p.value.length > limit && picked.isNotEmpty()) continue
            picked += p
            size += p.value.length
            if (size >= limit) break
        }
        return picked.sortedBy { it.index }.joinToString("\n") { it.value }.take(limit)
    }

    private fun duckDuckGo(query: String, limit: Int): List<Hit> {
        val html = get("https://html.duckduckgo.com/html/?kl=es-es&q=" + URLEncoder.encode(query, "UTF-8"), 6 * HOUR)
        val links = Regex("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { clean(it.groupValues[1]) }.toList()
        return links.mapIndexed { i, m ->
            var href = m.groupValues[1].replace("&amp;", "&")
            Regex("uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (href.startsWith("//")) href = "https:$href"
            Hit(clean(m.groupValues[2]), href, snippets.getOrElse(i) { "" })
        }.filterNot { it.url.contains("duckduckgo.com/y.js") }.take(limit)
    }

    private fun wikipedia(query: String, limit: Int): List<Hit> {
        val url = "https://es.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=$limit&srsearch=" +
            URLEncoder.encode(query, "UTF-8")
        val results = JSONObject(get(url, 24 * HOUR)).getJSONObject("query").getJSONArray("search")
        return List(results.length()) { i ->
            val r = results.getJSONObject(i)
            val title = r.getString("title")
            Hit("$title (Wikipedia)", "https://es.wikipedia.org/wiki/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}", clean(r.optString("snippet")))
        }
    }

    /** El texto legible de una página, recortado para que quepa en la conversación. */
    fun read(url: String, limit: Int = 3500): String {
        require(url.startsWith("http://") || url.startsWith("https://")) { "el enlace tiene que empezar por https://" }
        val text = clean(
            get(url, 24 * HOUR)
                .replace(Regex("<(script|style|noscript|svg|nav|footer|header|aside|form)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
                .replace(Regex("<(br|p|div|li|h[1-6]|tr|article|section)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        )
        return text.lines().map { it.trim() }.filter { it.length > 1 }.joinToString("\n").take(limit).ifBlank { "La página no tiene texto que pueda leer." }
    }

    /** Descarga [url], o la saca de la caché si se descargó hace menos de [cacheMs]. */
    fun get(url: String, cacheMs: Long): String {
        if (cacheMs > 0) WebCache.get(url, cacheMs)?.let { return it }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 9000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", AGENT)
        c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
        try {
            if (c.responseCode !in 200..299) error("la web respondió ${c.responseCode}")
            val body = c.inputStream.bufferedReader().use { r ->
                val buf = CharArray(400_000)
                var n = 0
                while (n < buf.size) {
                    val read = r.read(buf, n, buf.size - n)
                    if (read < 0) break
                    n += read
                }
                String(buf, 0, n)
            }
            if (cacheMs > 0) WebCache.put(url, body)
            return body
        } finally {
            c.disconnect()
        }
    }

    fun clean(html: String): String = html
        .replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n")
        .trim()
}

/**
 * Fuentes directas para lo más común, sin buscador ni modelo: el tiempo (Open-Meteo), el
 * cambio de moneda (Banco Central Europeo vía Frankfurter), noticias (Google Noticias) y
 * Wikipedia. Todas gratis y sin clave.
 */
object LiveInfo {

    private const val MIN = 60_000L

    // --- Órdenes directas ---

    private val weatherAsk = Regex(
        "^(?:qu[eé] tiempo (?:hace|hay|va a hacer|har[aá])|c[oó]mo est[aá] el tiempo|(?:el )?tiempo|(?:la )?temperatura|(?:va|vaya) a llover|llover[aá]|hace (?:fr[ií]o|calor))(?: hoy| ma[ñn]ana| ahora)?(?: en ([\\p{L} .'-]+?))?(?: hoy| ma[ñn]ana| ahora)?[?.!]*$",
        RegexOption.IGNORE_CASE,
    )
    private val moneyAsk = Regex(
        "^(?:cu[aá]nto (?:son|es|ser[ií]an?)|convierte|pasa|cambia)\\s+([\\d.,]+)\\s+([\\p{L} ]+?)\\s+(?:a|en)\\s+([\\p{L} ]+?)[?.!]*$",
        RegexOption.IGNORE_CASE,
    )
    private val rateAsk = Regex(
        "^(?:a cu[aá]nto est[aá]|c[oó]mo est[aá]|cu[aá]nto vale) (?:el|la) ([\\p{L} ]+?)(?: (?:en|frente al?|contra el) ([\\p{L} ]+?))?[?.!]*$",
        RegexOption.IGNORE_CASE,
    )
    private val newsAsk = Regex(
        "^(?:dime |cu[eé]ntame |lee(?:me)? )?(?:las )?(?:[uú]ltimas )?(?:noticias|titulares)(?: de hoy)?(?: (?:de|sobre|del?) (.+?))?[?.!]*$|^qu[eé] (?:hay de nuevo|ha pasado hoy)[?.!]*$",
        RegexOption.IGNORE_CASE,
    )
    private val wikiAsk = Regex(
        "^(?:busca en (?:la )?wikipedia|qu[eé] dice (?:la )?wikipedia (?:de|sobre)|seg[uú]n (?:la )?wikipedia,?(?: qui[eé]n es| qu[eé] es)?)\\s+(.+?)[?.!]*$",
        RegexOption.IGNORE_CASE,
    )

    /** Si [text] es una de estas preguntas, devuelve el trabajo (con red, fuera del hilo principal) que da la respuesta. */
    fun direct(context: Context, text: String): (() -> String)? {
        val t = text.trim()
        weatherAsk.find(t)?.let { m ->
            val place = m.groupValues[1].trim().ifEmpty { null }
            val tomorrow = Regex("ma[ñn]ana", RegexOption.IGNORE_CASE).containsMatchIn(t)
            return { weather(context, place, tomorrow) }
        }
        moneyAsk.find(t)?.let { m ->
            val from = currencyCode(m.groupValues[2])
            val to = currencyCode(m.groupValues[3])
            val amount = m.groupValues[1].replace(".", "").replace(',', '.').toDoubleOrNull()
            if (from != null && to != null && amount != null) return { currency(amount, from, to) }
        }
        rateAsk.find(t)?.let { m ->
            val from = currencyCode(m.groupValues[1])
            val to = m.groupValues[2].ifBlank { "euro" }.let(::currencyCode)
            if (from != null && to != null && from != to) return { currency(1.0, from, to) }
        }
        newsAsk.find(t)?.let { m ->
            val topic = m.groupValues.getOrNull(1)?.trim()?.ifEmpty { null }
            return { news(topic) }
        }
        wikiAsk.find(t)?.let { m -> return { wiki(m.groupValues[1]) } }
        return null
    }

    // --- El tiempo ---

    fun weather(context: Context, place: String?, tomorrow: Boolean): String {
        val prefs = context.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)
        val wanted = place ?: prefs.getString("ciudad_tiempo", null)
            ?: return "¿De qué ciudad? Dime por ejemplo \"qué tiempo hace en Madrid\" y la recordaré para la próxima."
        val geo = JSONObject(WebSearch.get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=es&name=" + URLEncoder.encode(wanted, "UTF-8"), 30 * 24 * 60 * MIN))
            .optJSONArray("results")?.optJSONObject(0) ?: return "No encuentro la ciudad \"$wanted\"."
        prefs.edit().putString("ciudad_tiempo", wanted).apply()
        val name = geo.getString("name")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${geo.getDouble("latitude")}&longitude=${geo.getDouble("longitude")}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&timezone=auto&forecast_days=2"
        val data = JSONObject(WebSearch.get(url, 20 * MIN))
        val daily = data.getJSONObject("daily")
        fun day(i: Int) = "entre ${daily.getJSONArray("temperature_2m_min").getDouble(i).toInt()} y " +
            "${daily.getJSONArray("temperature_2m_max").getDouble(i).toInt()} grados, ${sky(daily.getJSONArray("weather_code").getInt(i))}, " +
            "${daily.getJSONArray("precipitation_probability_max").optInt(i, 0)} % de probabilidad de lluvia"
        if (tomorrow) return "Mañana en $name: ${day(1)}."
        val now = data.getJSONObject("current")
        return "En $name ahora hay ${now.getDouble("temperature_2m").toInt()} grados (sensación de ${now.getDouble("apparent_temperature").toInt()}), " +
            "${sky(now.getInt("weather_code"))}. Hoy, ${day(0)}."
    }

    private fun sky(code: Int) = when (code) {
        0 -> "despejado"
        1, 2 -> "poco nuboso"
        3 -> "nublado"
        45, 48 -> "con niebla"
        51, 53, 55, 56, 57 -> "con llovizna"
        61, 63, 66, 80, 81 -> "con lluvia"
        65, 67, 82 -> "con lluvia fuerte"
        71, 73, 75, 77, 85, 86 -> "con nieve"
        95, 96, 99 -> "con tormenta"
        else -> "variable"
    }

    // --- Moneda ---

    private val CURRENCIES = listOf(
        "eur" to Regex("euros?|eur|€"),
        "usd" to Regex("d[oó]lar(?:es)?(?: estadounidenses?| americanos?)?|usd|\\$"),
        "gbp" to Regex("libras?(?: esterlinas?)?|gbp|£"),
        "jpy" to Regex("yen(?:es)?|jpy"),
        "chf" to Regex("francos? suizos?|chf"),
        "cny" to Regex("yuan(?:es)?|cny"),
        "mxn" to Regex("pesos? mexicanos?|mxn"),
        "brl" to Regex("reales?(?: brasileños?)?|brl"),
        "cad" to Regex("d[oó]lar(?:es)? canadienses?|cad"),
        "aud" to Regex("d[oó]lar(?:es)? australianos?|aud"),
        "sek" to Regex("coronas? suecas?|sek"),
        "nok" to Regex("coronas? noruegas?|nok"),
        "dkk" to Regex("coronas? danesas?|dkk"),
        "pln" to Regex("zlotys?|esloti(?:s)?|pln"),
        "try" to Regex("liras? turcas?|try"),
        "inr" to Regex("rupias?(?: indias?)?|inr"),
    )

    /** Código de moneda a partir de su nombre en español o su código. */
    fun currencyCode(name: String): String? {
        val n = name.trim().lowercase(Locale.ROOT)
        // Primero los nombres más largos ("dólares canadienses" antes que "dólares").
        return CURRENCIES.sortedByDescending { it.second.pattern.length }.firstOrNull { it.second.matches(n) }?.first?.uppercase()
            ?: CURRENCIES.firstOrNull { it.second.matches(n.removePrefix("el ").removePrefix("la ")) }?.first?.uppercase()
    }

    fun currency(amount: Double, from: String, to: String): String {
        if (from == to) return "Es la misma moneda."
        val data = JSONObject(WebSearch.get("https://api.frankfurter.app/latest?from=$from&to=$to", 60 * MIN))
        val rate = data.getJSONObject("rates").getDouble(to)
        val result = amount * rate
        return "${Calculator.format(amount)} $from son ${Calculator.format(Math.round(result * 100) / 100.0)} $to " +
            "(cambio oficial del ${data.optString("date")}: 1 $from = ${Calculator.format(Math.round(rate * 10000) / 10000.0)} $to)."
    }

    // --- Noticias ---

    fun news(topic: String?): String {
        val url = if (topic == null) "https://news.google.com/rss?hl=es&gl=ES&ceid=ES:es"
        else "https://news.google.com/rss/search?hl=es&gl=ES&ceid=ES:es&q=" + URLEncoder.encode(topic, "UTF-8")
        val xml = WebSearch.get(url, 15 * MIN)
        val titles = Regex("<item>.*?<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { WebSearch.clean(it.groupValues[1]) }.filter { it.isNotBlank() }.take(5).toList()
        if (titles.isEmpty()) return "No encontré noticias${topic?.let { " sobre $it" } ?: ""}."
        return (if (topic == null) "Titulares de ahora:\n" else "Lo último sobre $topic:\n") +
            titles.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
    }

    // --- Wikipedia ---

    fun wiki(topic: String): String {
        val title = WebSearch.hits("$topic wikipedia", 6).firstOrNull { it.url.contains("es.wikipedia.org/wiki/") }
            ?.url?.substringAfter("/wiki/")?.let { URLDecoder.decode(it, "UTF-8") }
            .let { URLEncoder.encode((it ?: topic.trim()).replace(' ', '_'), "UTF-8").replace("+", "_") }
        val data = JSONObject(WebSearch.get("https://es.wikipedia.org/api/rest_v1/page/summary/$title", 24 * 60 * MIN))
        val extract = data.optString("extract").ifBlank { error("no hay resumen") }
        return "$extract (Wikipedia: ${data.optString("title")})"
    }
}
