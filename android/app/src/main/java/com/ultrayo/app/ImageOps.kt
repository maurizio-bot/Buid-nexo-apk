package com.ultrayo.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/** Un filtro de color con nombre, para el editor y para las órdenes de voz. */
enum class Filter(val label: String) {
    BlackWhite("B/N"),
    Sepia("Sepia"),
    Vivid("Vivo"),
    Warm("Cálido"),
    Cold("Frío"),
    Invert("Negativo"),
}

/** Retoques básicos de imágenes, sin IA. Todo devuelve una imagen nueva y deja la original igual. */
object ImageOps {

    fun load(path: String): Bitmap? = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = false
    })

    fun rotate(bitmap: Bitmap, degrees: Float): Bitmap =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)

    fun flip(bitmap: Bitmap): Bitmap =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postScale(-1f, 1f) }, true)

    /** Recorta el rectángulo dado en fracciones de la imagen (de 0 a 1). */
    fun crop(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val x = (left.coerceIn(0f, 1f) * bitmap.width).toInt()
        val y = (top.coerceIn(0f, 1f) * bitmap.height).toInt()
        val w = ((right - left).coerceIn(0f, 1f) * bitmap.width).toInt().coerceIn(1, bitmap.width - x)
        val h = ((bottom - top).coerceIn(0f, 1f) * bitmap.height).toInt().coerceIn(1, bitmap.height - y)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    /** El cuadrado más grande del centro. */
    fun cropSquare(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        return Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
    }

    /** Recorta al centro y escala a [size]×[size], como lo necesita la IA que pinta. */
    fun squareOf(bitmap: Bitmap, size: Int): Bitmap = Bitmap.createScaledBitmap(cropSquare(bitmap), size, size, true)

    fun matrixOf(filter: Filter): ColorMatrix = when (filter) {
        Filter.BlackWhite -> ColorMatrix().apply { setSaturation(0f) }
        Filter.Sepia -> ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, 40f,
                0f, 1f, 0f, 0f, 20f,
                0f, 0f, 1f, 0f, -20f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        Filter.Vivid -> adjustments(brightness = 0f, contrast = 1.15f, saturation = 1.6f)
        Filter.Warm -> ColorMatrix(floatArrayOf(
            1.1f, 0f, 0f, 0f, 10f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 0.85f, 0f, -10f,
            0f, 0f, 0f, 1f, 0f,
        ))
        Filter.Cold -> ColorMatrix(floatArrayOf(
            0.88f, 0f, 0f, 0f, -10f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1.12f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f,
        ))
        Filter.Invert -> ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    /** [brightness] de -100 a 100, [contrast] y [saturation] multiplican (1 = igual). */
    fun adjustments(brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
        val t = (1f - contrast) * 128f + brightness
        return ColorMatrix().apply {
            setSaturation(saturation)
            postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
    }

    fun applyMatrix(bitmap: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return out
    }

    fun filter(bitmap: Bitmap, filter: Filter) = applyMatrix(bitmap, matrixOf(filter))

    /**
     * Escribe [text] centrado en el punto ([x], [y]), en fracciones de la imagen.
     * [size] es la altura de la letra en fracción del lado corto. Lleva borde para que se lea sobre cualquier fondo.
     */
    fun drawText(bitmap: Bitmap, text: String, x: Float, y: Float, size: Float, color: Int): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val px = size * minOf(out.width, out.height)
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px
            this.color = color
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val stroke = TextPaint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = px / 8f
            this.color = if (Luminance.isLight(color)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        val width = (out.width * 0.9f).toInt()
        fun layout(paint: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        val outline = layout(stroke)
        val body = layout(fill)
        val left = (x * out.width - width / 2f).coerceIn(0f, (out.width - width).toFloat())
        val top = (y * out.height - body.height / 2f).coerceIn(0f, (out.height - body.height).coerceAtLeast(0).toFloat())
        canvas.save()
        canvas.translate(left, top)
        outline.draw(canvas)
        body.draw(canvas)
        canvas.restore()
        return out
    }

    /** Guarda en la carpeta de imágenes de la app y devuelve la ruta. */
    fun saveInApp(context: Context, bitmap: Bitmap, prefix: String = "imagen"): String {
        val dir = File(context.filesDir, "imagenes").apply { mkdirs() }
        val file = File(dir, "$prefix-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.path
    }

    /** Copia la imagen a la galería del teléfono (Imágenes/Ultra Yo). Devuelve false si no se pudo. */
    fun saveToGallery(context: Context, bitmap: Bitmap): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ultra-yo-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ultra Yo")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
}

private object Luminance {
    fun isLight(color: Int): Boolean {
        val r = android.graphics.Color.red(color)
        val g = android.graphics.Color.green(color)
        val b = android.graphics.Color.blue(color)
        return 0.299 * r + 0.587 * g + 0.114 * b > 150
    }
}
