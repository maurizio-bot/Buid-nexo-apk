package com.ultrayo.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * El "oído" de Ultra Yo: un reconocedor de voz pequeño en español (Vosk, unos 40 MB)
 * que funciona sin internet y escucha la palabra "Ultra" en segundo plano.
 */
object WakeModel {

    private const val URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"

    fun dir(context: Context) = File(context.filesDir, "oido-es")

    fun isReady(context: Context) = File(dir(context), ".listo").exists()

    /** Descarga y descomprime el modelo. Se hace una sola vez. */
    suspend fun download(context: Context) = withContext(Dispatchers.IO) {
        val target = dir(context)
        target.deleteRecursively()
        target.mkdirs()
        val connection = URL(URL_ZIP).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            ZipInputStream(connection.inputStream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // Quita la carpeta raíz del zip (vosk-model-small-es-0.42/…).
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isEmpty() || relative.contains("..")) continue
                    val out = File(target, relative)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        File(target, ".listo").createNewFile()
    }
}
