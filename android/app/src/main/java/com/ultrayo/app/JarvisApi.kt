package com.ultrayo.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Turn(val role: String, val content: String)

class JarvisException(message: String) : Exception(message)

/** Cliente del servidor de Ultra Yo. Llamar siempre fuera del hilo principal. */
class JarvisApi(private val baseUrl: String, private val token: String) {

    fun chat(history: List<Turn>): String {
        val conn = URL(baseUrl.trimEnd('/') + "/chat").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $token")

            val messages = JSONArray()
            history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
            conn.outputStream.use { it.write(JSONObject().put("messages", messages).toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body).getString("detail") }.getOrNull()
                throw JarvisException(detail ?: "El servidor respondió con error $code")
            }
            return JSONObject(body).getString("reply")
        } finally {
            conn.disconnect()
        }
    }
}
