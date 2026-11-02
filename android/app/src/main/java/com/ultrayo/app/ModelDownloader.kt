package com.ultrayo.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Los cerebros que Ultra Yo puede usar. Todos funcionan dentro del teléfono y sin internet;
 * solo hace falta red para descargarlos la primera vez.
 */
enum class BrainModel(
    val id: String,
    val label: String,
    val fileName: String,
    val url: String?,
    val sizeGb: String,
    val description: String,
) {
    LIGHT(
        "ligero", "Ligero (2B)", "gemma-4-E2B-it.litertlm",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        "2,6 GB", "El más rápido. Bien para órdenes y charla corta.",
    ),
    SMART(
        "listo", "Listo (4B)", "gemma-4-E4B-it.litertlm",
        "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        "3,7 GB", "Razona y escribe bastante mejor. Usa la GPU para seguir siendo rápido.",
    ),
    CUSTOM(
        "propio", "Propio (entrenado por ti)", "", null, "",
        "Un modelo .litertlm que hayas entrenado tú (guía en el repo, carpeta entrenamiento). Ponlo en Ultra Yo/modelos.",
    );

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)

        /** El cerebro que Maurizio ha elegido (aunque aún se esté descargando). */
        fun selected(context: Context): BrainModel =
            entries.firstOrNull { it.id == prefs(context).getString("modelo", null) } ?: LIGHT

        fun select(context: Context, model: BrainModel) = prefs(context).edit().putString("modelo", model.id).apply()

        /** El que se usa ahora: el elegido si ya está listo; si no, cualquiera que ya esté descargado. */
        fun active(context: Context): BrainModel {
            val chosen = selected(context)
            if (ModelDownloader(context, chosen).isReady()) return chosen
            return listOf(SMART, LIGHT, CUSTOM).firstOrNull { ModelDownloader(context, it).isReady() } ?: chosen
        }

        /** Un modelo propio dejado en Ultra Yo/modelos (el más nuevo), si hay alguno. */
        fun customFile(context: Context): File? =
            File(Vault.dir(context), "modelos").apply { mkdirs() }
                .listFiles { f -> f.isFile && f.name.endsWith(".litertlm", ignoreCase = true) }
                ?.maxByOrNull { it.lastModified() }
    }
}

sealed interface DownloadState {
    data object None : DownloadState
    data class Running(val progress: Float) : DownloadState
    data object Done : DownloadState
    data class Failed(val reason: Int) : DownloadState
}

/** Descarga un cerebro una sola vez con el gestor de descargas de Android. */
class ModelDownloader(private val context: Context, val model: BrainModel = BrainModel.active(context)) {

    val file: File = if (model == BrainModel.CUSTOM) {
        BrainModel.customFile(context) ?: File(Vault.dir(context), "modelos/ninguno.litertlm")
    } else {
        File(context.getExternalFilesDir(null), model.fileName)
    }
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)

    // El ligero conserva las claves de antes, para no volver a descargarlo tras actualizar la app.
    private val readyKey = if (model == BrainModel.LIGHT) "model_ready" else "model_ready_${model.id}"
    private val idKey = if (model == BrainModel.LIGHT) "download_id" else "download_id_${model.id}"

    fun isReady() = if (model == BrainModel.CUSTOM) file.exists() else prefs.getBoolean(readyKey, false) && file.exists()

    fun start() {
        val url = checkNotNull(model.url) { "Este modelo no se descarga: cópialo a Ultra Yo/modelos." }
        file.delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Cerebro de Ultra Yo: ${model.label}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, model.fileName)
        val id = manager.enqueue(request)
        prefs.edit().putLong(idKey, id).putBoolean(readyKey, false).apply()
    }

    /** Borra el archivo descargado para liberar espacio. */
    fun delete() {
        if (model == BrainModel.CUSTOM) return
        file.delete()
        prefs.edit().putBoolean(readyKey, false).remove(idKey).apply()
    }

    fun state(): DownloadState {
        if (isReady()) return DownloadState.Done
        if (model == BrainModel.CUSTOM) return DownloadState.None
        val id = prefs.getLong(idKey, -1)
        if (id == -1L) return DownloadState.None
        manager.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return DownloadState.None
            return when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().putBoolean(readyKey, true).apply()
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
