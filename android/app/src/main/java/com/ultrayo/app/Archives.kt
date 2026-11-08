package com.ultrayo.app

import android.content.Context
import android.net.Uri
import com.ultrayo.app.comprimidos.Unpack
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Archivos comprimidos: ver qué llevan dentro y descomprimirlos en la carpeta de Ultra Yo
 * (Documentos/Ultra Yo/archivos/descomprimidos). El comprimido original no se toca.
 * Sabe abrir zip, 7z, rar (RAR4 y RAR5), tar, gz, bz2 y xz (y combinaciones como tar.gz), también
 * con contraseña: zip (normal y AES), 7z y RAR4. RAR5 con contraseña no hay manera libre de abrirlo.
 */
object Archives {

    private const val MAX_ENTRIES = 5000
    private const val MAX_TOTAL = 2L shl 30 // 2 GB descomprimidos como mucho
    private const val MAX_LISTED = 25
    private const val RAR5_LOCKED = "este RAR5 tiene contraseña, y dentro del teléfono no hay ningún lector libre que abra " +
        "RAR5 cifrados (los RAR4 con contraseña sí). Si se puede, que lo pase a zip o 7z con contraseña: esos sí los abro"

    init {
        Unpack.rar5 = ::rar5
    }

    fun isArchive(name: String): Boolean = Unpack.suffix(name) != null

    /** Un vistazo a lo que hay dentro, sin descomprimir nada. */
    fun describe(context: Context, uri: Uri, name: String, password: String? = null): String = withLocalCopy(context, uri, name) { file ->
        val entries = mutableListOf<Pair<String, Long>>()
        forEachEntry(file, name, password) { entryName, size, _ ->
            entries += entryName to size
            true
        }
        summary(name, entries, destination = null)
    }

    /** Descomprime en la carpeta de Ultra Yo. Devuelve la carpeta creada y un resumen de lo que había. */
    fun extract(context: Context, uri: Uri, name: String, password: String? = null): Pair<File, String> = withLocalCopy(context, uri, name) { file ->
        val dest = uniqueDir(File(Vault.files(context), "descomprimidos"), baseName(name))
        val root = dest.canonicalPath + File.separator
        val entries = mutableListOf<Pair<String, Long>>()
        var total = 0L
        try {
            forEachEntry(file, name, password) { entryName, _, write ->
                val target = File(dest, entryName)
                // Nada de rutas tipo "../../" que se salgan de la carpeta.
                if (!target.canonicalPath.startsWith(root)) return@forEachEntry true
                target.parentFile?.mkdirs()
                val written = target.outputStream().use { out ->
                    val limited = Unpack.Limited(out, MAX_TOTAL - total)
                    try {
                        write(limited)
                    } finally {
                        total += limited.written
                    }
                    limited.written
                }
                entries += entryName to written
                true
            }
        } catch (e: Throwable) {
            // Lo que no se pudo abrir no deja una carpeta vacía o a medias sin avisar.
            if (entries.isEmpty()) dest.deleteRecursively()
            throw e
        }
        dest to summary(name, entries, destination = dest)
    }

    /** Un mensaje claro para la IA cuando falta la contraseña o no es la buena. */
    fun explain(e: Throwable): String = when (e) {
        is Unpack.NeedsPassword -> "${e.message}. Pídele la contraseña a Maurizio y vuelve a intentarlo poniéndola en contrasena."
        is Unpack.WrongPassword -> "${e.message}. Pregúntale a Maurizio si la ha escrito bien."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun <T> withLocalCopy(context: Context, uri: Uri, name: String, work: (File) -> T): T {
        if (uri.scheme == "file") return work(File(uri.path!!))
        // 7z, rar y los zip con contraseña necesitan poder saltar dentro del archivo: se trabaja con una copia temporal.
        val tmp = File(context.cacheDir, "comprimido-${System.currentTimeMillis()}-${name.takeLast(40).replace('/', '_')}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: error("no pude abrir el archivo")
            return work(tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun forEachEntry(file: File, name: String, password: String?, visit: (String, Long, (java.io.OutputStream) -> Unit) -> Boolean) {
        var count = 0
        Unpack.forEachEntry(file, name, password) { entryName, size, write ->
            if (++count > MAX_ENTRIES) error("tiene más de $MAX_ENTRIES archivos; he parado")
            visit(entryName.trimStart('/'), size, write)
        }
    }

    /** RAR5 con libarchive (librería nativa). No sabe contraseñas de RAR5: lo dice claro. */
    private fun rar5(file: File, visit: Unpack.Visitor) {
        val archive = Archive.readNew()
        try {
            Archive.readSupportFormatRar5(archive)
            Archive.readOpenFileName(archive, file.path.toByteArray(), 64 * 1024)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val entry = try {
                    Archive.readNextHeader(archive)
                } catch (e: ArchiveException) {
                    if (e.message.orEmpty().contains("ncrypt")) throw Unpack.NeedsPassword(RAR5_LOCKED)
                    throw java.io.IOException("no pude leer el rar: ${e.message}")
                }
                if (entry == 0L) break
                if (ArchiveEntry.filetype(entry) and ArchiveEntry.AE_IFMT != ArchiveEntry.AE_IFREG) continue
                if (ArchiveEntry.isEncrypted(entry)) throw Unpack.NeedsPassword(RAR5_LOCKED)
                val entryName = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.let { String(it) } ?: continue
                val size = if (ArchiveEntry.sizeIsSet(entry)) ArchiveEntry.size(entry) else -1
                val keepGoing = visit.visit(entryName, size) { out ->
                    while (true) {
                        val wrapped = ByteBuffer.wrap(buffer)
                        try {
                            Archive.readData(archive, wrapped)
                        } catch (e: ArchiveException) {
                            if (e.message.orEmpty().contains("ncrypt")) throw Unpack.NeedsPassword(RAR5_LOCKED)
                            throw java.io.IOException("no pude sacar $entryName: ${e.message}")
                        }
                        val read = wrapped.position()
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
                if (!keepGoing) break
            }
        } finally {
            runCatching { Archive.free(archive) }
        }
    }

    private fun summary(name: String, entries: List<Pair<String, Long>>, destination: File?): String = buildString {
        val total = entries.sumOf { it.second.coerceAtLeast(0) }
        append(name).append(": ").append(entries.size).append(if (entries.size == 1) " archivo" else " archivos")
        if (total > 0) append(", ").append(human(total)).append(" descomprimido")
        append(".\n")
        destination?.let { append("Descomprimido en ").append(it.path).append(". Para leer un archivo usa la ruta Ultra Yo/")
            .append(it.relativeTo(it.parentFile!!.parentFile!!.parentFile!!).path).append("/<archivo>.\n") }
        val types = entries.groupingBy { DocReader.extension(it.first).ifEmpty { "sin extensión" } }.eachCount()
            .entries.sortedByDescending { it.value }
        append("Tipos: ").append(types.joinToString { "${it.value} ${it.key}" }).append(".\n")
        entries.take(MAX_LISTED).forEach { (entry, size) ->
            append("- ").append(entry)
            if (size >= 0) append(" (").append(human(size)).append(')')
            append('\n')
        }
        if (entries.size > MAX_LISTED) append("… y ").append(entries.size - MAX_LISTED).append(" más.\n")
    }

    private fun baseName(name: String): String {
        val s = Unpack.suffix(name) ?: return name
        val base = name.dropLast(s.length).ifBlank { "comprimido" }
        // "informe.gz" -> "informe"; "fotos.tgz" -> "fotos"
        return base.substringAfterLast('/')
    }

    private fun uniqueDir(parent: File, name: String): File {
        var dir = File(parent, name)
        var n = 2
        while (dir.exists()) dir = File(parent, "$name ($n)").also { n++ }
        dir.mkdirs()
        return dir
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    }
}
