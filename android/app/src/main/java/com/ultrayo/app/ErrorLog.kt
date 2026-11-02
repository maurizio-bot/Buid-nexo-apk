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

    /**
     * Lo que se está haciendo ahora mismo (por ejemplo "herramienta buscarArchivos").
     * Se escribe en disco antes de hacerlo; si la app se cierra de golpe, al volver se sabe.
     */
    fun step(what: String) {
        runCatching { stepFile()?.writeText(DATE.format(Date()) + " · " + what) }
    }

    /** Lo que estaba en marcha terminó bien. */
    fun stepDone() {
        runCatching { stepFile()?.delete() }
    }

    fun read(): String = runCatching { file()?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    fun clear() {
        runCatching { file()?.delete() }
    }

    /**
     * Al abrir la app: pregunta a Android si la última vez se cerró por un fallo y lo apunta,
     * junto con lo que estaba haciendo en ese momento. Devuelve el informe si hubo un cierre
     * que la app no pudo apuntar por sí misma (fallo nativo), para enseñarlo en pantalla.
     */
    fun checkLastExit(context: Context): String? = runCatching {
        val pending = stepFile()?.takeIf { it.exists() }?.readText()
        stepFile()?.delete()
        val reason = lastExitReason(context)
        if (reason == null && pending == null) return@runCatching null
        val report = buildString {
            append("La app se cerró sola la última vez.\n")
            if (reason != null) append("Motivo según Android: ").append(reason).append('\n')
            if (pending != null) append("Lo que estaba haciendo: ").append(pending).append('\n')
        }
        add(report.trim())
        report
    }.getOrNull()

    private fun lastExitReason(context: Context): String? {
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
        return "$kind, ${DATE.format(Date(info.timestamp))}" +
            (info.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
            ", memoria usada ${info.pss / 1024} MB"
    }
}
