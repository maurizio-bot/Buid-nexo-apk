package com.ultrayo.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de errores que Maurizio puede copiar desde Ajustes y mandárselo a Claude.
 *
 * Apunta tres cosas:
 * - los fallos de las herramientas y del modelo (que ya no cierran la app),
 * - la última herramienta que empezó, escrita antes de ejecutarla, para que si la app se cierra
 *   de golpe (un fallo nativo que Java no puede capturar) se sepa qué estaba haciendo,
 * - por qué se cerró la app la última vez, según Android (cierre nativo, falta de memoria…).
 */
object ErrorLog {

    private const val MAX_BYTES = 60_000
    private val DATE = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("es-ES"))

    @Volatile
    private var dir: File? = null

    private fun file() = dir?.let { File(it, "registro-errores.txt") }
    private fun stepFile() = dir?.let { File(it, "ultimo-paso.txt") }

    fun init(context: Context) {
        dir = context.filesDir
    }

    /** Apunta un error. Nunca lanza nada. */
    fun add(what: String, error: Throwable? = null) {
        runCatching {
            val f = file() ?: return
            val entry = buildString {
                append("— ").append(DATE.format(Date())).append(" · ").append(what).append('\n')
                if (error != null) append(error.stackTraceToString().take(3000)).append('\n')
            }
            synchronized(this) {
                val old = if (f.exists()) f.readText() else ""
                val all = old + entry
                f.writeText(if (all.length > MAX_BYTES) all.takeLast(MAX_BYTES) else all)
            }
        }
    }

    /** Lo que está en marcha ahora mismo, por tipo ("modelo", "herramienta", "voz"…). */
    private val running = LinkedHashMap<String, String>()

    /**
     * Apunta que empieza algo arriesgado (el motor del modelo contestando, una herramienta, el oído…).
     * Se escribe en disco antes de hacerlo; si la app se cierra de golpe, al volver se sabe qué era.
     * Varias cosas pueden estar en marcha a la vez (por ejemplo el oído de la palabra "Ultra" y el modelo).
     */
    fun begin(key: String, what: String) {
        runCatching {
            synchronized(running) {
                running[key] = DATE.format(Date()) + " · " + what
                saveRunning()
            }
        }
    }

    /** Lo que estaba en marcha con esa clave terminó (bien o con un error ya apuntado). */
    fun end(key: String) {
        runCatching {
            synchronized(running) {
                if (running.remove(key) != null) saveRunning()
            }
        }
    }

    private fun saveRunning() {
        val f = stepFile() ?: return
        if (running.isEmpty()) f.delete() else f.writeText(running.values.joinToString("\n"))
    }

    /** Lo que se está haciendo ahora mismo (una herramienta). */
    fun step(what: String) = begin("herramienta", what)

    /** La herramienta terminó. */
    fun stepDone() = end("herramienta")

    fun read(): String = runCatching { file()?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    fun clear() {
        runCatching { file()?.delete() }
    }

    /**
     * Al abrir la app: pregunta a Android si la última vez se cerró por un fallo y lo apunta,
     * junto con lo que estaba haciendo en ese momento y, si fue un fallo nativo, el resumen del
     * informe de Android (qué librería y qué función). Devuelve el informe si hubo un cierre
     * que la app no pudo apuntar por sí misma (fallo nativo), para enseñarlo en pantalla.
     */
    fun checkLastExit(context: Context): String? = runCatching {
        val pending = stepFile()?.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        stepFile()?.delete()
        val exit = lastExit(context)
        // Con Android 11 o más nuevo se sabe si fue un fallo: si Android no dice nada, se cerró normal
        // (deslizada, actualizada, apagada) aunque hubiera algo en marcha, como esperar la palabra "Ultra".
        if (exit == null && (Build.VERSION.SDK_INT >= 30 || pending == null)) return@runCatching null
        val native = exit?.native == true
        // Si el motor estaba en la GPU cuando se cerró de golpe, la próxima vez va al procesador.
        val gpuBlamed = native && (exit?.tombstone?.looksLikeGpu == true || pending?.contains("en la GPU") == true)
        if (gpuBlamed) GpuGuard.markBroken(context)
        val report = buildString {
            append("La app se cerró sola la última vez.\n")
            append("Versión de Ultra Yo: ").append(version(context)).append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" · ").append(Build.MODEL).append('\n')
            if (exit != null) append("Motivo según Android: ").append(exit.reason).append('\n')
            if (pending != null) append("Lo que estaba haciendo:\n").append(pending).append('\n')
            else if (exit != null) append("Lo que estaba haciendo: nada apuntado (no era el modelo contestando ni una herramienta).\n")
            exit?.tombstone?.let { append(it.text()).append('\n') }
            if (native && exit?.tombstone == null) append("Android no dejó el informe del fallo nativo.\n")
            if (gpuBlamed) append("Parece un fallo de la GPU: a partir de ahora el modelo usa el procesador (Ajustes > Usar la GPU para volver a probar).\n")
        }
        add(report.trim())
        report
    }.getOrNull()

    private class Exit(val reason: String, val native: Boolean, val tombstone: Tombstone.Info?)

    private fun version(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun lastExit(context: Context): Exit? {
        if (Build.VERSION.SDK_INT < 30) return null
        val prefs = context.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)
        val seen = prefs.getLong("ultimo_cierre_visto", 0)
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            .filter { it.timestamp > seen }
            .maxByOrNull { it.timestamp } ?: return null
        prefs.edit().putLong("ultimo_cierre_visto", info.timestamp).apply()
        val kind = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "fallo de la app (Java)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "fallo nativo (motor del modelo o librería)"
            ApplicationExitInfo.REASON_ANR -> "se quedó colgada y Android la cerró"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "falta de memoria"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "usó demasiados recursos"
            ApplicationExitInfo.REASON_SIGNALED -> "señal del sistema ${info.status}"
            else -> return null // cerrada por Maurizio, actualizada, etc.: no es un fallo
        }
        val native = info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
        // En Android 12 o más nuevo, Android guarda el informe del fallo nativo (el "tombstone").
        val tombstone = if (native && Build.VERSION.SDK_INT >= 31) Tombstone.read(runCatching { info.traceInputStream }.getOrNull()) else null
        val memory = (info.pss / 1024).takeIf { it > 0 }?.let { ", memoria usada $it MB" } ?: ""
        val reason = "$kind, ${DATE.format(Date(info.timestamp))}" +
            (info.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + memory
        return Exit(reason, native, tombstone)
    }
}
