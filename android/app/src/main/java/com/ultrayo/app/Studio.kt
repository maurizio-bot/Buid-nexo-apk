package com.ultrayo.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.withContext
import java.util.Locale

private const val PROMPT_INSTRUCTION = """Eres un experto en prompts para Stable Diffusion 1.5.
Convierte la petición en un prompt en inglés: una sola línea de palabras clave separadas por comas
(sujeto, detalles, estilo, luz, encuadre) que termine en "highly detailed, best quality".
Responde solo con el prompt, sin comillas ni explicaciones."""

private const val EDIT_INSTRUCTION = """Eres un experto en prompts para Stable Diffusion 1.5.
Te paso una imagen (o su descripción) y un cambio que hay que hacerle. Escribe en inglés el prompt
que describe la imagen YA CAMBIADA: una sola línea de palabras clave separadas por comas que termine
en "highly detailed, best quality". Responde solo con el prompt, sin comillas ni explicaciones."""

/**
 * El estudio de imágenes: traduce lo que pide Maurizio a un prompt que entienda el pintor,
 * pinta, y guarda el resultado en la app y en la galería. Lo usan el chat y el editor.
 */
object Studio {

    /** Avance borroso mientras pinta, para enseñarlo en el chat o en el editor. */
    var preview by mutableStateOf<Bitmap?>(null)
    var progress by mutableStateOf<String?>(null)

    /** La imagen abierta en el editor, o null si está cerrado. */
    var editing by mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())

    /** Qué decir si el pintor aún no está listo (y empieza a descargarlo), o null si se puede pintar ya. */
    fun notReadyMessage(context: Context): String? {
        if (Painter.busy) return "Ya estoy pintando otra imagen. Espera a que termine."
        return when (val s = Painter.state(context)) {
            PainterState.Ready -> null
            PainterState.None -> {
                Painter.startDownload(context)
                "Para pintar necesito descargar mi pintor, unos ${"%.1f".format(Locale.forLanguageTag("es"), Painter.model(context).sizeMb / 1000.0)} GB y solo una vez. " +
                    "Ya he empezado; cuando termine vuelve a pedírmelo."
            }
            is PainterState.Downloading -> "Todavía estoy descargando el pintor: va por el ${(s.progress * 100).toInt()} %."
            PainterState.Unpacking -> "Estoy preparando el pintor, dame un minuto."
            is PainterState.Failed -> {
                Painter.retry(context)
                "El pintor falló antes (${s.message}). Vuelvo a descargarlo."
            }
        }
    }

    /** Pide a la IA que habla el prompt en inglés. Si no está lista, usa la frase tal cual. */
    private suspend fun promptFor(instruction: String, text: String, imagePath: String? = null): String {
        val brain = Ai.brain ?: return text
        Assistant.brainDirty = true
        val answer = runCatching {
            withContext(Ai.thread) { brain.answerOnce(instruction, text, imagePath) }
        }.getOrNull()
        return answer?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?.trim()?.trim('"', '\'', '`')?.take(400)
            ?.ifBlank { null } ?: text
    }

    private fun onProgress(step: Int, total: Int, image: Bitmap?) = main.post {
        progress = "Pintando… $step de $total"
        if (image != null) preview = image
    }

    private suspend fun run(context: Context, request: PaintRequest): Bitmap {
        progress = "Despertando al pintor…"
        try {
            return Painter.paint(context, request, ::onProgress)
        } finally {
            progress = null
            preview = null
        }
    }

    /** Pinta [description] desde cero. Devuelve la ruta de la imagen. */
    suspend fun create(context: Context, description: String): String {
        progress = "Pensando cómo pintarlo…"
        val prompt = promptFor(PROMPT_INSTRUCTION, description)
        val image = run(context, PaintRequest(prompt = prompt))
        return keep(context, image)
    }

    /** Vuelve a pintar [source] siguiendo [instruction]. [sourcePath] deja que la IA mire la imagen si puede. */
    suspend fun aiEdit(context: Context, source: Bitmap, instruction: String, strength: Float = 0.6f, sourcePath: String? = null): Bitmap {
        if (!Painter.canEdit(context)) error("este modelo de pintor no sabe cambiar imágenes, solo crearlas")
        progress = "Pensando el cambio…"
        val seesImages = Ai.brain?.visionEnabled == true && sourcePath != null
        val prompt = promptFor(
            EDIT_INSTRUCTION,
            if (seesImages) "Cambio: $instruction" else "Imagen: desconocida. Cambio: $instruction",
            if (seesImages) sourcePath else null,
        )
        return run(context, PaintRequest(prompt = prompt, source = source, strength = strength))
    }

    /** Guarda en la app y en la galería. Devuelve la ruta dentro de la app. */
    fun keep(context: Context, bitmap: Bitmap): String {
        ImageOps.saveToGallery(context, bitmap)
        return ImageOps.saveInApp(context, bitmap)
    }
}
