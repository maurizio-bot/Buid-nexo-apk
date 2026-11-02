package com.ultrayo.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Los modelos de la voz rápida (sherpa-onnx), que se descargan una sola vez y funcionan sin internet:
 * - el oído: reconocimiento de voz en español en streaming (Kroko, zipformer), unos 70 MB;
 * - la voz: voz neural en español (Piper), unos 65 MB.
 */
object VoiceModels {

    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    enum class Kind(val folder: String, val label: String, val candidates: List<String>) {
        EAR(
            "oido-rapido",
            "oído rápido",
            listOf("$BASE/asr-models/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06.tar.bz2"),
        ),
        VOICE(
            "voz-neural",
            "voz neural",
            // Si una voz no está, se prueba la siguiente.
            listOf(
                "$BASE/tts-models/vits-piper-es_ES-davefx-medium.tar.bz2",
                "$BASE/tts-models/vits-piper-es_ES-sharvard-medium.tar.bz2",
                "$BASE/tts-models/vits-piper-es_ES-carlfm-x_low.tar.bz2",
            ),
        ),
    }

    /** Progreso de la descarga en curso (0..1), o null si no se está descargando nada. */
    var progress by mutableStateOf<Float?>(null)
        private set
    var downloading by mutableStateOf<Kind?>(null)
        private set

    fun dir(context: Context, kind: Kind) = File(context.filesDir, "voz/${kind.folder}")

    fun isReady(context: Context, kind: Kind) = File(dir(context, kind), ".listo").exists()

    fun allReady(context: Context) = Kind.entries.all { isReady(context, it) }

    fun delete(context: Context) = Kind.entries.forEach { dir(context, it).deleteRecursively() }

    /** Descarga lo que falte. Lanza una excepción si no se pudo. */
    suspend fun downloadAll(context: Context) {
        for (kind in Kind.entries) if (!isReady(context, kind)) download(context, kind)
    }

    private suspend fun download(context: Context, kind: Kind) = withContext(Dispatchers.IO) {
        val target = dir(context, kind)
        var lastError: Exception? = null
        for (url in kind.candidates) {
            try {
                downloading = kind
                progress = 0f
                target.deleteRecursively()
                target.mkdirs()
                fetch(url, target)
                File(target, ".listo").createNewFile()
                return@withContext
            } catch (e: Exception) {
                lastError = e
                target.deleteRecursively()
            } finally {
                downloading = null
                progress = null
            }
        }
        throw lastError ?: IllegalStateException("No pude descargar el ${kind.label}")
    }

    private fun fetch(url: String, target: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode != 200) error("respuesta ${connection.responseCode}")
            val total = connection.contentLengthLong
            val counted = Counting(connection.inputStream.buffered(1 shl 16)) { read ->
                if (total > 0) progress = read.toFloat() / total
            }
            TarArchiveInputStream(BZip2CompressorInputStream(counted)).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    // Quita la carpeta raíz del paquete (vits-piper-…/…).
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isEmpty() || relative.contains("..")) continue
                    val out = File(target, relative)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Busca un archivo del modelo por su nombre (los paquetes no siempre los llaman igual). */
    fun find(dir: File, test: (File) -> Boolean): File? =
        dir.walkTopDown().filter { it.isFile && test(it) }.sortedBy { it.path.length }.firstOrNull()

    private class Counting(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        private fun add(n: Long) {
            count += n
            if (count - lastReport > 512 * 1024) {
                lastReport = count
                onRead(count)
            }
        }
    }
}
