package com.ultrayo.app

import java.io.InputStream

/**
 * Lee el informe que Android guarda cuando la app se cierra por un fallo nativo (el "tombstone",
 * que [android.app.ApplicationExitInfo.getTraceInputStream] entrega en Android 12 o más nuevo)
 * y lo resume en pocas líneas: qué señal fue, en qué hilo, qué librería y qué función, y las
 * últimas líneas del registro de Android antes del cierre.
 *
 * El informe viene en formato protobuf (debuggerd/proto/tombstone.proto). Para no meter otra
 * librería, aquí hay un lector mínimo de protobuf que solo saca los campos que interesan.
 */
object Tombstone {

    /** Lo importante de un cierre nativo. */
    data class Info(
        val signal: String,
        val abortMessage: String,
        val causes: List<String>,
        val threadName: String,
        val tid: Int,
        val frames: List<String>,
        /** La primera librería de la app (o del motor) en la pila: la sospechosa. */
        val suspect: String?,
        val logs: List<String>,
    ) {
        /** Si el fallo parece de la GPU (drivers de gráficos o el acelerador GPU del motor). */
        val looksLikeGpu: Boolean
            get() = (frames.take(12) + listOfNotNull(suspect)).any { f ->
                val l = f.lowercase()
                GPU_HINTS.any { it in l }
            }

        fun text(): String = buildString {
            append("Señal: ").append(signal).append('\n')
            if (abortMessage.isNotBlank()) append("Mensaje del motor: ").append(abortMessage.take(600)).append('\n')
            causes.take(3).forEach { append("Causa probable: ").append(it.take(300)).append('\n') }
            append("Hilo que falló: ").append(threadName.ifBlank { "?" }).append(" (").append(tid).append(")\n")
            if (suspect != null) append("Librería sospechosa: ").append(suspect).append('\n')
            if (frames.isNotEmpty()) {
                append("Pila:\n")
                frames.take(MAX_FRAMES).forEachIndexed { i, f -> append("  #").append(i.toString().padStart(2, '0')).append(' ').append(f).append('\n') }
            }
            if (logs.isNotEmpty()) {
                append("Últimas líneas de Android:\n")
                logs.forEach { append("  ").append(it.take(300)).append('\n') }
            }
        }.trim()
    }

    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_FRAMES = 14
    private const val MAX_LOGS = 15

    private val GPU_HINTS = listOf("gpu", "opencl", "vulkan", "adreno", "mali", "libgles", "libegl", "kgsl", "libllvm-qcom")

    /** Librerías del sistema que casi nunca son la culpable: se saltan al buscar la sospechosa. */
    private val SYSTEM_LIBS = listOf("/libc.so", "/libc++", "/libart.so", "/libbase.so", "/libutils.so", "/libandroid_runtime.so", "/libdl.so", "/libm.so", "/linker64", "/libunwindstack", "/apex/com.android.art/")

    /** Lee y resume el informe. Devuelve null si no se entiende. Nunca lanza nada. */
    fun read(stream: InputStream?): Info? = runCatching {
        val bytes = stream?.use { readCapped(it) } ?: return null
        parse(bytes)
    }.getOrNull()

    private fun readCapped(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < MAX_BYTES) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun parse(bytes: ByteArray): Info? {
        var tid = 0
        var signal = ""
        var abort = ""
        val causes = mutableListOf<String>()
        val threads = mutableMapOf<Int, Pair<String, List<Frame>>>()
        val logs = mutableListOf<LogLine>()
        for (f in Pb(bytes).fields()) {
            when (f.num) {
                6 -> tid = f.varint.toInt()
                10 -> signal = signal(f.bytes ?: continue)
                14 -> abort = f.string()
                15 -> Pb(f.bytes ?: continue).fields().firstOrNull { it.num == 1 }?.let { causes += it.string() }
                16 -> thread(f.bytes ?: continue)?.let { threads[it.first] = it.second }
                18 -> logs += logBuffer(f.bytes ?: continue)
            }
        }
        if (signal.isBlank() && threads.isEmpty() && abort.isBlank()) return null
        val (name, frames) = threads[tid] ?: threads.values.firstOrNull() ?: ("" to emptyList())
        val suspect = frames.firstOrNull { fr -> fr.file.isNotBlank() && SYSTEM_LIBS.none { it in fr.file } }?.file?.substringAfterLast('/')
        val lastLogs = logs.filter { it.tid == tid || it.priority >= 5 }.takeLast(MAX_LOGS).ifEmpty { logs.takeLast(MAX_LOGS) }
        return Info(
            signal = signal.ifBlank { "?" },
            abortMessage = abort,
            causes = causes,
            threadName = name,
            tid = tid,
            frames = frames.map { it.text() },
            suspect = suspect,
            logs = lastLogs.map { it.text() },
        )
    }

    private data class Frame(val relPc: Long, val function: String, val offset: Long, val file: String) {
        fun text(): String {
            val lib = file.substringAfterLast('/').ifBlank { "?" }
            val fn = if (function.isNotBlank()) " ($function+$offset)" else ""
            return "$lib pc ${"%x".format(relPc)}$fn"
        }
    }

    private data class LogLine(val time: String, val tid: Int, val priority: Int, val tag: String, val message: String) {
        fun text(): String {
            val p = "??VDIWEF".getOrElse(priority) { '?' }
            return "$p $tag: ${message.trim()}"
        }
    }

    private fun signal(bytes: ByteArray): String {
        var name = ""
        var code = ""
        var hasFault = false
        var fault = 0L
        for (f in Pb(bytes).fields()) {
            when (f.num) {
                2 -> name = f.string()
                4 -> code = f.string()
                8 -> hasFault = f.varint != 0L
                9 -> fault = f.varint
            }
        }
        return buildString {
            append(name.ifBlank { "?" })
            if (code.isNotBlank()) append(" (").append(code).append(')')
            if (hasFault) append(" en la dirección 0x").append(java.lang.Long.toHexString(fault))
        }
    }

    /** Una entrada del mapa de hilos: clave = id, valor = Thread. */
    private fun thread(entry: ByteArray): Pair<Int, Pair<String, List<Frame>>>? {
        var key = 0
        var value: ByteArray? = null
        for (f in Pb(entry).fields()) {
            when (f.num) {
                1 -> key = f.varint.toInt()
                2 -> value = f.bytes
            }
        }
        val v = value ?: return null
        var name = ""
        val frames = mutableListOf<Frame>()
        for (f in Pb(v).fields()) {
            when (f.num) {
                2 -> name = f.string()
                4 -> f.bytes?.let { frames += frame(it) }
            }
        }
        return key to (name to frames)
    }

    private fun frame(bytes: ByteArray): Frame {
        var relPc = 0L
        var fn = ""
        var off = 0L
        var file = ""
        for (f in Pb(bytes).fields()) {
            when (f.num) {
                1 -> relPc = f.varint
                4 -> fn = f.string()
                5 -> off = f.varint
                6 -> file = f.string()
            }
        }
        return Frame(relPc, fn, off, file)
    }

    private fun logBuffer(bytes: ByteArray): List<LogLine> {
        val out = mutableListOf<LogLine>()
        for (f in Pb(bytes).fields()) {
            if (f.num != 2) continue
            var time = ""
            var tid = 0
            var prio = 0
            var tag = ""
            var msg = ""
            for (g in Pb(f.bytes ?: continue).fields()) {
                when (g.num) {
                    1 -> time = g.string()
                    3 -> tid = g.varint.toInt()
                    4 -> prio = g.varint.toInt()
                    5 -> tag = g.string()
                    6 -> msg = g.string()
                }
            }
            out += LogLine(time, tid, prio, tag, msg)
        }
        return out
    }

    /** Un campo de protobuf: número, y su valor (entero o bytes). */
    class Field(val num: Int, val varint: Long, val bytes: ByteArray?) {
        fun string(): String = bytes?.toString(Charsets.UTF_8).orEmpty()
    }

    /** Lector mínimo de protobuf: recorre los campos de un mensaje. Se para sin lanzar si los datos están rotos. */
    class Pb(private val buf: ByteArray) {
        private var pos = 0

        fun fields(): Sequence<Field> = sequence {
            while (pos < buf.size) {
                val tag = varint() ?: return@sequence
                val num = (tag ushr 3).toInt()
                when ((tag and 7).toInt()) {
                    0 -> yield(Field(num, varint() ?: return@sequence, null))
                    1 -> {
                        if (pos + 8 > buf.size) return@sequence
                        var v = 0L
                        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xff) shl (8 * i))
                        pos += 8
                        yield(Field(num, v, null))
                    }
                    2 -> {
                        val len = varint() ?: return@sequence
                        if (len < 0 || pos + len > buf.size) return@sequence
                        val b = buf.copyOfRange(pos, pos + len.toInt())
                        pos += len.toInt()
                        yield(Field(num, 0, b))
                    }
                    5 -> {
                        if (pos + 4 > buf.size) return@sequence
                        var v = 0L
                        for (i in 0 until 4) v = v or ((buf[pos + i].toLong() and 0xff) shl (8 * i))
                        pos += 4
                        yield(Field(num, v, null))
                    }
                    else -> return@sequence
                }
            }
        }

        private fun varint(): Long? {
            var result = 0L
            var shift = 0
            while (pos < buf.size && shift < 64) {
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return null
        }
    }
}
