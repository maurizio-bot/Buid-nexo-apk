package com.ultrayo.app.comprimidos

import com.github.junrar.exception.CrcErrorException
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.WrongPasswordException
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Abrir comprimidos, también con contraseña: zip (también AES), 7z, rar (RAR4 y RAR5), tar,
 * gz, bz2, xz y sus combinaciones. Sin Android, para poder probarlo en el ordenador; lo que
 * necesita una librería nativa (RAR5) lo pone la app en [rar5].
 */
object Unpack {

    /** Hace falta la contraseña (y no la tenemos). */
    class NeedsPassword(message: String) : IOException(message)

    /** La contraseña no abre el comprimido. */
    class WrongPassword(message: String) : IOException(message)

    /** Para cada archivo de dentro: nombre, tamaño (o -1) y cómo escribir su contenido. Devuelve false para parar. */
    fun interface Visitor {
        fun visit(name: String, size: Long, write: (OutputStream) -> Unit): Boolean
    }

    /** RAR5 lo abre libarchive (librería nativa de la app). Lo rellena la app al arrancar. */
    @Volatile var rar5: ((File, Visitor) -> Unit)? = null

    val SUFFIXES = listOf(
        ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tbz", ".tar.xz", ".txz",
        ".zip", ".jar", ".7z", ".rar", ".tar", ".gz", ".bz2", ".xz",
    )

    const val FORMATS = "zip, 7z, rar, tar, gz, bz2 y xz"

    fun suffix(name: String): String? = SUFFIXES.firstOrNull { name.lowercase().endsWith(it) }

    fun forEachEntry(file: File, name: String, password: String?, visit: Visitor) {
        val pass = password?.takeIf { it.isNotEmpty() }
        when (val s = suffix(name) ?: error("no es un comprimido que sepa abrir")) {
            ".zip", ".jar" -> zip(file, pass, visit)
            ".7z" -> sevenZ(file, pass, visit)
            ".rar" -> rar(file, pass, visit)
            ".tar" -> archive(TarArchiveInputStream(BufferedInputStream(file.inputStream())), visit)
            else -> {
                val compressed = decompress(s, BufferedInputStream(file.inputStream()))
                if (s.startsWith(".t")) {
                    archive(TarArchiveInputStream(compressed), visit)
                } else {
                    // Un solo archivo comprimido (por ejemplo informe.txt.gz)
                    compressed.use { input -> visit.visit(name.substringAfterLast('/').dropLast(s.length).ifBlank { "archivo" }, -1) { input.copyTo(it) } }
                }
            }
        }
    }

    private fun zip(file: File, password: String?, visit: Visitor) {
        val zip = net.lingala.zip4j.ZipFile(file, password?.toCharArray())
        val headers = try {
            zip.fileHeaders
        } catch (e: ZipException) {
            // Zip raro (sin índice al final, a medias…): se lee de principio a fin como antes.
            zip.close()
            return archive(ZipArchiveInputStream(BufferedInputStream(file.inputStream())), visit)
        }
        zip.use {
            for (h in headers) {
                if (h.isDirectory) continue
                if (h.isEncrypted && password == null) throw NeedsPassword("\"${h.fileName}\" está protegido con contraseña")
                val keepGoing = visit.visit(h.fileName, h.uncompressedSize) { out ->
                    try {
                        zip.getInputStream(h).use { it.copyTo(out) }
                    } catch (e: ZipException) {
                        if (e.type == ZipException.Type.WRONG_PASSWORD || (h.isEncrypted && e.message.orEmpty().contains("password", true))) {
                            throw WrongPassword("la contraseña no es correcta")
                        }
                        throw e
                    }
                }
                if (!keepGoing) break
            }
        }
    }

    private fun sevenZ(file: File, password: String?, visit: Visitor) {
        val z = try {
            SevenZFile.builder().setFile(file).apply { if (password != null) setPassword(password.toCharArray()) }.get()
        } catch (e: PasswordRequiredException) {
            throw NeedsPassword("este 7z está protegido con contraseña")
        } catch (e: IOException) {
            if (password != null) throw WrongPassword("la contraseña no es correcta (o el 7z está dañado)")
            throw e
        }
        z.use {
            while (true) {
                val entry = z.nextEntry ?: break
                if (entry.isDirectory || entry.isAntiItem) continue
                val keepGoing = visit.visit(entry.name, entry.size) { out ->
                    try {
                        z.getInputStream(entry).copyTo(out)
                    } catch (e: PasswordRequiredException) {
                        throw NeedsPassword("este 7z está protegido con contraseña")
                    } catch (e: IOException) {
                        if (password != null && e !is NeedsPassword) throw WrongPassword("la contraseña no es correcta (o el 7z está dañado)")
                        throw e
                    }
                }
                if (!keepGoing) break
            }
        }
    }

    /** RAR4 (y anteriores) con junrar, que también sabe contraseñas; RAR5 con libarchive. */
    private fun rar(file: File, password: String?, visit: Visitor) {
        val version = rarVersion(file)
        if (version == 5) {
            val open = rar5 ?: error("este teléfono no tiene el lector de RAR5")
            return open(file, visit)
        }
        if (version == 0) error("no es un .rar de verdad (no empieza como un rar)")
        val archive = try {
            com.github.junrar.Archive(file, password)
        } catch (e: WrongPasswordException) {
            throw WrongPassword("la contraseña no es correcta")
        } catch (e: UnsupportedRarEncryptedException) {
            throw if (password == null) NeedsPassword("este rar tiene la lista de archivos cifrada con contraseña") else WrongPassword("la contraseña no es correcta")
        } catch (e: RarException) {
            if (password != null && e.message.orEmpty().contains("password", true)) throw WrongPassword("la contraseña no es correcta")
            throw IOException("no pude abrir el rar: ${e.message ?: e.javaClass.simpleName}")
        }
        archive.use {
            if (archive.isEncrypted && password == null) throw NeedsPassword("este rar tiene la lista de archivos cifrada con contraseña")
            for (h in archive.fileHeaders) {
                if (h.isDirectory) continue
                if (h.isEncrypted && password == null) throw NeedsPassword("\"${h.fileName}\" está protegido con contraseña")
                val keepGoing = visit.visit(h.fileName.replace('\\', '/'), h.fullUnpackSize) { out ->
                    try {
                        archive.extractFile(h, out)
                    } catch (e: CrcErrorException) {
                        throw if (h.isEncrypted) WrongPassword("la contraseña no es correcta") else IOException("el rar está dañado")
                    } catch (e: WrongPasswordException) {
                        throw WrongPassword("la contraseña no es correcta")
                    } catch (e: RarException) {
                        throw IOException("no pude sacar ${h.fileName}: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                if (!keepGoing) break
            }
        }
    }

    /** 4 para RAR 1.5–4.x, 5 para RAR5, 0 si no parece un rar. */
    fun rarVersion(file: File): Int {
        val head = ByteArray(8)
        val n = RandomAccessFile(file, "r").use { it.read(head) }
        if (n < 7 || String(head, 0, 4, Charsets.ISO_8859_1) != "Rar!" || head[4] != 0x1a.toByte() || head[5] != 0x07.toByte()) return 0
        return if (head[6] == 0x01.toByte()) 5 else 4
    }

    private fun archive(stream: ArchiveInputStream<*>, visit: Visitor) = stream.use { a ->
        while (true) {
            val entry = a.nextEntry ?: break
            if (entry.isDirectory) continue
            if (!a.canReadEntryData(entry)) throw NeedsPassword("\"${entry.name}\" está protegido con contraseña o usa un formato que no sé abrir")
            if (!visit.visit(entry.name, entry.size) { out -> a.copyTo(out) }) break
        }
    }

    private fun decompress(suffix: String, input: InputStream): InputStream = when (suffix) {
        ".tar.gz", ".tgz", ".gz" -> GzipCompressorInputStream(input, true)
        ".tar.bz2", ".tbz2", ".tbz", ".bz2" -> BZip2CompressorInputStream(input, true)
        else -> XZCompressorInputStream(input, true)
    }

    /** Para no pasarse de [max] bytes al descomprimir (una "bomba zip" llenaría el teléfono). */
    class Limited(private val out: OutputStream, private val max: Long) : OutputStream() {
        var written = 0L
            private set

        override fun write(b: Int) {
            if (written + 1 > max) throw TooBig()
            out.write(b); written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (written + len > max) throw TooBig()
            out.write(b, off, len); written += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    class TooBig : IOException("descomprimido sería más de lo que dejo (2 GB); he parado")
}
