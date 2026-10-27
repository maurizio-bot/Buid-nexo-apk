package com.ultrayo.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

private const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
private const val MODEL_URL =
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$MODEL_FILE"

sealed interface DownloadState {
    data object None : DownloadState
    data class Running(val progress: Float) : DownloadState
    data object Done : DownloadState
    data class Failed(val reason: Int) : DownloadState
}

/** Descarga el modelo una sola vez con el gestor de descargas de Android. */
class ModelDownloader(private val context: Context) {

    val file = File(context.getExternalFilesDir(null), MODEL_FILE)
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)

    fun isReady() = prefs.getBoolean("model_ready", false) && file.exists()

    fun start() {
        file.delete()
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Cerebro de Ultra Yo")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, MODEL_FILE)
        val id = manager.enqueue(request)
        prefs.edit().putLong("download_id", id).putBoolean("model_ready", false).apply()
    }

    fun state(): DownloadState {
        if (isReady()) return DownloadState.Done
        val id = prefs.getLong("download_id", -1)
        if (id == -1L) return DownloadState.None
        manager.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return DownloadState.None
            return when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().putBoolean("model_ready", true).apply()
                    DownloadState.Done
                }
                DownloadManager.STATUS_FAILED ->
                    DownloadState.Failed(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                else -> {
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    DownloadState.Running(if (total > 0) done.toFloat() / total else 0f)
                }
            }
        }
    }
}
