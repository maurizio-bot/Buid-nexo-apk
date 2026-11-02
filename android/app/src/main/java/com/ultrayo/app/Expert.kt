package com.ultrayo.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modo experto: para tareas muy difíciles, Ultra Yo pasa la pregunta a un modelo grande en la
 * nube (Gemini, con el plan gratuito de Google AI Studio). Está apagado por defecto y solo se usa
 * cuando Maurizio lo pide con "modo experto: …". Lo único que sale del teléfono es esa pregunta
 * y las últimas frases del chat. La clave se guarda en la app, no en la carpeta compartida.
 */
object Expert {

    private const val MODEL = "gemini-flash-latest"
    private const val FALLBACK_MODEL = "gemini-2.5-flash"
    private const val CONTEXT_TURNS = 6

    private val command = Regex(
        "^(?:modo experto|experto|preg[uú]ntale al experto|consulta al experto|p[aá]sale(?: esto)? al experto)[,:]?\\s+(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("experto", false)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("experto", on).apply()
    fun key(context: Context) = prefs(context).getString("experto_clave", "").orEmpty()
    fun setKey(context: Context, key: String) = prefs(context).edit().putString("experto_clave", key.trim()).apply()

    /** La pregunta, si [text] es una petición explícita del modo experto. */
    fun questionIn(text: String): String? = command.find(text.trim())?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    /** Por qué no se puede usar ahora, o null si está listo. */
    fun notReady(context: Context): String? = when {
        !enabled(context) -> "El modo experto está apagado. Si quieres usarlo, actívalo en Ajustes, en Modo experto. Mandaría tu pregunta a Google."
        key(context).isBlank() -> "Para el modo experto falta tu clave gratuita de Google AI Studio. Ponla en Ajustes, en Modo experto."
        else -> null
    }

    /** Pregunta al modelo grande. Lento (red): llamar fuera del hilo principal. */
    fun ask(context: Context, systemPrompt: String, history: List<Turn>, question: String): String {
        val contents = JSONArray()
        history.takeLast(CONTEXT_TURNS).filter { it.content.isNotBlank() }.forEach {
            contents.put(message(if (it.role == "user") "user" else "model", it.content))
        }
        contents.put(message("user", question))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", contents)
        return try {
            call(MODEL, key(context), body)
        } catch (e: NotFound) {
            call(FALLBACK_MODEL, key(context), body)
        }
    }

    private class NotFound : Exception()

    private fun message(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun call(model: String, key: String, body: JSONObject): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 90_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("x-goog-api-key", key)
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                code == 404 -> throw NotFound()
                code == 400 && text.contains("API_KEY", ignoreCase = true) -> error("la clave no es válida")
                code == 403 -> error("la clave no tiene permiso (revísala en Google AI Studio)")
                code == 429 -> error("se ha gastado el cupo gratuito por ahora; prueba más tarde")
                code !in 200..299 -> error("el servidor respondió $code")
            }
            val candidate = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                ?: error("no devolvió respuesta")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: error("respuesta vacía")
            return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
                .ifEmpty { error("respuesta vacía") }
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Guarda cada pregunta y respuesta en Ultra Yo/entrenamiento/conversaciones.jsonl, en el formato
 * que usa el cuaderno de ajuste fino (carpeta entrenamiento del repo). Nunca sale del teléfono:
 * Maurizio decide si lo sube a Colab.
 */
object TrainingLog {

    private fun prefs(context: Context) = context.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("guardar_entrenamiento", true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("guardar_entrenamiento", on).apply()

    fun file(context: Context): File = File(File(Vault.dir(context), "entrenamiento").apply { mkdirs() }, "conversaciones.jsonl")

    fun count(context: Context): Int = runCatching { file(context).useLines { it.count() } }.getOrDefault(0)

    fun clear(context: Context) = file(context).delete()

    /** [source] dice quién contestó: "local" (Ultra en el teléfono) o "experto" (la nube, mejor para enseñarle). */
    fun add(context: Context, question: String, answer: String, source: String) {
        if (!enabled(context) || question.isBlank() || answer.isBlank()) return
        runCatching {
            val line = JSONObject()
                .put("fecha", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
                .put("origen", source)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "user").put("content", question))
                        .put(JSONObject().put("role", "assistant").put("content", answer)),
                )
            file(context).appendText(line.toString() + "\n")
        }
    }
}
