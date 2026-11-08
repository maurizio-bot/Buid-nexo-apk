package com.ultrayo.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnóstico: todo lo que hace Ultra, paso a paso y con la hora al milisegundo, durante los últimos
 * 10 minutos (oír, la puerta del micrófono, qué decide hacer, el modelo, las herramientas, la voz, PARAR…).
 * Solo vive en la memoria del teléfono (no se guarda en archivos) y se ve y se copia en Ajustes > Diagnóstico,
 * para pegárselo a Claude y ver el problema real.
 */
object Diag {

    const val WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_EVENTS = 4000
    private const val MAX_TEXT = 300

    private class Event(val at: Long, val area: String, val text: String)

    private val events = ArrayDeque<Event>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    /** Apunta algo que acaba de pasar. [area]: oído, puerta, voz, decide, modelo, herramienta, parar… Nunca lanza. */
    fun log(area: String, text: String, now: Long = System.currentTimeMillis()) {
        runCatching {
            synchronized(events) {
                events.addLast(Event(now, area, text.replace('\n', ' ').take(MAX_TEXT)))
                prune(now)
            }
        }
    }

    private fun prune(now: Long) {
        while (events.isNotEmpty() && (events.size > MAX_EVENTS || now - events.first().at > WINDOW_MS)) events.removeFirst()
    }

    /** Cuántos pasos hay apuntados ahora. */
    fun size(now: Long = System.currentTimeMillis()): Int = synchronized(events) {
        prune(now)
        events.size
    }

    /** Todo lo de los últimos 10 minutos, una línea por paso, listo para copiar. */
    fun text(now: Long = System.currentTimeMillis()): String = synchronized(events) {
        prune(now)
        if (events.isEmpty()) return ""
        var previous = events.first().at
        buildString {
            for (e in events) {
                val gap = e.at - previous
                previous = e.at
                append(time.format(Date(e.at)))
                append(if (gap >= 1000) String.format(Locale.ROOT, " (+%.1f s)", gap / 1000.0) else "")
                append(" [").append(e.area).append("] ").append(e.text).append('\n')
            }
        }
    }

    fun clear() = synchronized(events) { events.clear() }
}
