package com.ultrayo.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un archivo encontrado en el teléfono. [path] es la ruta que se le enseña a la IA. */
data class FoundFile(
    val path: String,
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long,
    val durationMs: Long = 0,
) {
    val kind: String
        get() = when {
            mime.startsWith("image/") -> "foto"
            mime.startsWith("video/") -> "vídeo"
            mime.startsWith("audio/") -> "audio"
            DocReader.canRead(name, mime) -> "documento"
            else -> "archivo"
        }

    fun line(): String = buildString {
        append(path).append("  (").append(kind)
        if (size > 0) append(", ").append(humanSize(size))
        if (durationMs > 0) append(", ").append(durationMs / 60000).append(" min ").append(durationMs / 1000 % 60).append(" s")
        if (modified > 0) append(", ").append(DATE.format(Date(modified)))
        append(')')
    }

    private companion object {
        val DATE = SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("es-ES"))

        fun humanSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
            else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
        }
    }
}

/**
 * Buscar archivos en el teléfono, siempre en modo lectura: fotos, vídeos y audio de la galería
 * (con el permiso de Android para verlos) y cualquier archivo de las carpetas que Maurizio ha
 * compartido con la app (documentos, descargas…). Nunca cambia ni borra nada.
 */
object DeviceFiles {

    private const val MAX_DEPTH = 8
    private const val MAX_VISITED = 20000

    /** Lo último encontrado, para poder abrir por ruta lo que se acaba de buscar. */
    private val recent = object : LinkedHashMap<String, FoundFile>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FoundFile>?) = size > 500
    }

    /** Permisos para ver la galería (solo lectura). */
    fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasMedia(context: Context): Boolean = mediaPermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    } || (Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)

    /**
     * Busca por nombre. [kind] puede ser foto, vídeo, audio, documento o vacío para todo.
     * Las palabras de [query] tienen que aparecer todas en la ruta.
     */
    fun search(context: Context, query: String, kind: String = "", limit: Int = 40): List<FoundFile> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val wantedKind = normalizeKind(kind)
        fun matches(f: FoundFile) =
            (wantedKind == null || f.kind == wantedKind) && words.all { f.path.lowercase().contains(it) }

        val found = mutableListOf<FoundFile>()
        if (hasMedia(context) && wantedKind != "documento" && wantedKind != "archivo") {
            found += media(context, words, wantedKind, limit).filter(::matches)
        }
        if (found.size < limit) {
            for (root in Folders.list(context)) {
                walkTree(context, root) { f ->
                    if (matches(f) && found.none { it.name == f.name && it.size == f.size }) found += f
                    found.size < limit
                }
                if (found.size >= limit) break
            }
        }
        val result = found.sortedByDescending { it.modified }.take(limit)
        synchronized(recent) { result.forEach { recent[it.path] = it } }
        return result
    }

    /** Encuentra un archivo por la ruta que devolvió [search] (o, si no, por su nombre). */
    fun find(context: Context, path: String): FoundFile? {
        val wanted = path.trim().substringBefore("  (")
        synchronized(recent) { recent[wanted] }?.let { return it }
        val name = wanted.substringAfterLast('/')
        return search(context, name, limit = 20).firstOrNull { it.path == wanted }
            ?: search(context, name, limit = 20).firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    fun readText(context: Context, file: FoundFile, limit: Int = MAX_DOC_TEXT): String =
        DocReader.read(context, file.uri, file.name, file.mime, limit)

    /** Copia una foto (o un fotograma de un vídeo) reducida a la caché, para enseñarla en el chat y que el modelo la pueda mirar. */
    fun copyPreview(context: Context, file: FoundFile, maxSide: Int = 1024): String {
        val bitmap = if (file.kind == "vídeo") {
            context.contentResolver.loadThumbnail(file.uri, Size(maxSide, maxSide), null)
        } else {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, file.uri)) { decoder, info, _ ->
                val scale = minOf(1f, maxSide.toFloat() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val dir = File(context.cacheDir, "fotos").apply { mkdirs() }
        val out = File(dir, "foto-${System.currentTimeMillis()}.jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return out.path
    }

    private fun normalizeKind(kind: String): String? = when (kind.trim().lowercase().removeSuffix("s").removeSuffix("e")) {
        "foto", "imag", "imagen", "image", "fotografía", "fotografia" -> "foto"
        "vídeo", "video" -> "vídeo"
        "audio", "música", "musica", "canción", "cancion", "nota de voz" -> "audio"
        "documento", "doc", "pdf", "texto" -> "documento"
        else -> null
    }

    /** Fotos, vídeos y audio a través de la galería de Android: rápido aunque haya miles. */
    private fun media(context: Context, words: List<String>, kind: String?, limit: Int): List<FoundFile> {
        val collections = buildList {
            if (kind == null || kind == "foto") add("Fotos" to MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            if (kind == null || kind == "vídeo") add("Vídeos" to MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            if (kind == null || kind == "audio") add("Audio" to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }
        val out = mutableListOf<FoundFile>()
        for ((label, collection) in collections) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DURATION,
            )
            // Filtra en la base de datos de la galería por la palabra más larga; el resto se comprueba después.
            val key = words.maxByOrNull { it.length }
            val selection = key?.let { "(${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)" }
            val args = key?.let { arrayOf("%$it%", "%$it%") }
            runCatching {
                context.contentResolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
            }.getOrNull()?.use { c ->
                var taken = 0
                while (c.moveToNext() && taken < limit * 4) {
                    val name = c.getString(1) ?: continue
                    val folder = c.getString(5)?.trimEnd('/') ?: ""
                    out += FoundFile(
                        path = listOf(label, folder, name).filter { it.isNotEmpty() }.joinToString("/"),
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        name = name,
                        mime = c.getString(2) ?: "",
                        size = c.getLong(3),
                        modified = c.getLong(4) * 1000,
                        durationMs = c.getLong(6),
                    )
                    taken++
                }
            }
        }
        return out
    }

    /**
     * Recorre una carpeta compartida preguntando a Android por cada directorio de una vez
     * (nombre, tipo, tamaño y fecha juntos), que es mucho más rápido que ir archivo por archivo.
     */
    private fun walkTree(context: Context, tree: Uri, visit: (FoundFile) -> Boolean) {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return
        val rootName = Folders.name(context, tree)
        val pending = ArrayDeque<Triple<String, String, Int>>()
        pending.add(Triple(rootId, rootName, 0))
        var visited = 0
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (pending.isNotEmpty() && visited < MAX_VISITED) {
            val (docId, path, depth) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cursor = runCatching { context.contentResolver.query(children, projection, null, null, null) }.getOrNull() ?: continue
            cursor.use { c ->
                while (c.moveToNext()) {
                    visited++
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH && !name.startsWith(".")) pending.add(Triple(id, "$path/$name", depth + 1))
                        continue
                    }
                    val file = FoundFile(
                        path = "$path/$name",
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        name = name,
                        mime = mime,
                        size = if (c.isNull(3)) 0 else c.getLong(3),
                        modified = if (c.isNull(4)) 0 else c.getLong(4),
                    )
                    if (!visit(file)) return
                }
            }
        }
    }
}
