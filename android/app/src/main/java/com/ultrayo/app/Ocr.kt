package com.ultrayo.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/**
 * Lee el texto que aparece en una imagen (fotos de documentos, carteles, capturas de pantalla…).
 * Usa el lector de ML Kit que va dentro de la app: funciona sin internet y gratis.
 * Todas las funciones tardan un poco: llamarlas siempre fuera del hilo principal.
 */
object Ocr {

    private const val MAX_SIDE = 2048
    private const val MAX_PDF_PAGES = 5

    val IMAGES = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun read(bitmap: Bitmap): String {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        // Un bloque por párrafo, en el orden en que aparecen de arriba abajo.
        return result.textBlocks
            .sortedWith(compareBy<Text.TextBlock>({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .joinToString("\n\n") { it.text }
    }

    fun read(context: Context, uri: Uri): String = read(decode(ImageDecoder.createSource(context.contentResolver, uri)))

    fun read(path: String): String = read(decode(ImageDecoder.createSource(File(path))))

    private fun decode(source: ImageDecoder.Source): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** Para PDF escaneados (sin texto dentro): dibuja las primeras páginas y lee lo que pone en ellas. */
    fun readPdf(context: Context, uri: Uri, limit: Int): String {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("no pude abrir el PDF")
        return PdfRenderer(fd).use { pdf ->
            val out = StringBuilder()
            for (i in 0 until minOf(pdf.pageCount, MAX_PDF_PAGES)) {
                if (out.length > limit) break
                pdf.openPage(i).use { page ->
                    val scale = MAX_SIDE.toFloat() / maxOf(page.width, page.height)
                    val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.append("Página ").append(i + 1).append(":\n").append(read(bitmap)).append("\n\n")
                    bitmap.recycle()
                }
            }
            out.toString()
        }
    }
}
