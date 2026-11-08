package com.ultrayo.app

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnóstico: todo lo que hace Ultra, paso a paso y con la hora al milisegundo (oír, la puerta del micrófono,
 * qué decide hacer, el modelo, las herramientas, la voz, PARAR…). Se ve y se copia en Ajustes > Diagnóstico.
 *
 * Cada paso se escribe en el archivo en el mismo momento (sin esperar), así que no se pierde aunque la app
 * se cierre de golpe: al volver a abrirla, el diagnóstico de justo antes del cierre sigue ahí.
 * Se empieza un archivo nuevo cada 10 minutos; se guardan el actual y el anterior (para que siempre haya
 * 10 minutos completos) y los más viejos se borran, para no llenar el teléfono.
 */
object Diag {

    const val WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_TEXT = 300

    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private val day = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)

    private var dir: File? = null
    private var slot = -1L
    private var out: FileOutputStream? = null

    /** Solo si no hay carpeta (pruebas en el PC): se guarda en memoria. */
    private val memory = ArrayList<String>()

    fun init(filesDir: File) = synchronized(this) {
        dir = File(filesDir, "diagnostico").apply { mkdirs() }
    }

    /** Apunta algo que acaba de pasar. [area]: oído, puerta, voz, decide, modelo, herramienta, parar… Nunca lanza. */
    fun log(area: String, text: String, now: Long = System.currentTimeMillis()) {
        runCatching {
            val line = "$now\t$area\t${text.replace('\n', ' ').replace('\t', ' ').take(MAX_TEXT)}\n"
            synchronized(this) {
                val folder = dir
                if (folder == null) {
                    memory += line
                    return
                }
                val current = now / WINDOW_MS
                if (current != slot || out == null) rotate(folder, current)
                // Sin búfer: la línea queda en el archivo aunque la app se cierre justo después.
                out?.write(line.toByteArray())
            }
        }
    }

    private fun rotate(folder: File, current: Long) {
        runCatching { out?.close() }
        slot = current
        out = FileOutputStream(File(folder, "diag-$current.txt"), true)
        folder.listFiles()?.forEach { f ->
            val n = f.name.removePrefix("diag-").removeSuffix(".txt").toLongOrNull()
            if (n == null || n < current - 1) f.delete()
        }
    }

    private fun lines(): List<String> = synchronized(this) {
        val folder = dir ?: return memory.toList()
        runCatching { out?.flush() }
        folder.listFiles { f -> f.name.startsWith("diag-") }
            .orEmpty()
            .sortedBy { it.name.removePrefix("diag-").removeSuffix(".txt").toLongOrNull() ?: 0L }
            .flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()) }
    }

    private class Event(val at: Long, val area: String, val text: String)

    /** Los 10 minutos de actividad hasta el último paso apuntado (aunque fuera antes de un cierre). */
    private fun recent(): List<Event> {
        val events = lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            Event(at, parts.getOrElse(1) { "?" }, parts.getOrElse(2) { "" })
        }.sortedBy { it.at }
        val last = events.lastOrNull()?.at ?: return emptyList()
        return events.filter { last - it.at <= WINDOW_MS }
    }

    /** Cuántos pasos hay en los últimos 10 minutos de actividad. */
    fun size(): Int = recent().size

    /** Los últimos 10 minutos de actividad, una línea por paso, listos para copiar. */
    fun text(): String {
        val events = recent()
        if (events.isEmpty()) return ""
        var previous = events.first().at
        return buildString {
            append("Desde ").append(day.format(Date(events.first().at))).append(' ').append(time.format(Date(events.first().at)))
                .append(" hasta ").append(time.format(Date(events.last().at))).append('\n')
            for (e in events) {
                val gap = e.at - previous
                previous = e.at
                append(time.format(Date(e.at)))
                if (gap >= 1000) append(String.format(Locale.ROOT, " (+%.1f s)", gap / 1000.0))
                append(" [").append(e.area).append("] ").append(e.text).append('\n')
            }
        }
    }

    /** Vuelve a guardar solo en memoria (para las pruebas en el PC). */
    internal fun detach() = synchronized(this) {
        runCatching { out?.close() }
        out = null
        slot = -1
        dir = null
    }

    fun clear() = synchronized(this) {
        memory.clear()
        runCatching { out?.close() }
        out = null
        slot = -1
        dir?.listFiles()?.forEach { it.delete() }
    }
}
