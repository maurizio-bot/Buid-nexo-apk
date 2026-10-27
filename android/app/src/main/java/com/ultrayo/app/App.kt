package com.ultrayo.app

import android.app.Application
import android.content.Context
import java.io.File

/** Guarda el último error que cerró la app para enseñarlo al volver a abrirla. */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile(this).writeText(
                    "Ultra Yo ${BuildConfigInfo.version(this)}\n${error.stackTraceToString()}"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun crashFile(context: Context) = File(context.filesDir, "ultimo-error.txt")
    }
}

private object BuildConfigInfo {
    fun version(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"
}
