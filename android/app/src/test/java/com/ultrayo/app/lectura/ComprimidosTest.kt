package com.ultrayo.app

import com.ultrayo.app.comprimidos.Unpack
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** Comprimidos con y sin contraseña: zip (AES), 7z, rar4 (rar5 necesita el teléfono). */
class ComprimidosTest {

    private fun fixture(name: String): File {
        val out = Files.createTempFile("ultra", name).toFile()
        javaClass.getResourceAsStream("/comprimidos/$name")!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    /** Nombre -> contenido de todo lo que hay dentro. */
    private fun read(file: File, name: String, password: String? = null): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        Unpack.forEachEntry(file, name, password) { entry, _, write ->
            val out = ByteArrayOutputStream()
            write(out)
            found[entry] = out.toString(Charsets.UTF_8)
            true
        }
        return found
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
            fail("tenía que fallar con ${T::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw AssertionError("falló con ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    @Test fun zipConContrasenaAes() {
        val dir = Files.createTempDirectory("zip").toFile()
        val note = File(dir, "nota.txt").apply { writeText("hola desde un zip cifrado") }
        val zipFile = File(dir, "secreto.zip")
        ZipFile(zipFile, "clave123".toCharArray()).use {
            it.addFile(note, ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            })
        }
        assertEquals("hola desde un zip cifrado", read(zipFile, "secreto.zip", "clave123")["nota.txt"])
        expect<Unpack.NeedsPassword> { read(zipFile, "secreto.zip") }
        expect<Unpack.WrongPassword> { read(zipFile, "secreto.zip", "otra") }
    }

    @Test fun zipNormal() {
        val dir = Files.createTempDirectory("zip").toFile()
        val zipFile = File(dir, "normal.zip")
        ZipFile(zipFile).use { it.addFile(File(dir, "a.txt").apply { writeText("abc") }) }
        assertEquals(mapOf("a.txt" to "abc"), read(zipFile, "normal.zip"))
    }

    @Test fun sieteZetaConContrasena() {
        val f = fixture("secreto.7z")
        assertEquals("hola mundo secreto", read(f, "secreto.7z", "clave123")["dentro/nota.txt"])
        expect<Unpack.NeedsPassword> { read(f, "secreto.7z") }
        expect<Unpack.WrongPassword> { read(f, "secreto.7z", "mala") }
        val oculto = fixture("secreto-cabecera.7z")
        expect<Unpack.NeedsPassword> { read(oculto, "secreto-cabecera.7z") }
        assertEquals("lista oculta", read(oculto, "secreto-cabecera.7z", "clave123")["oculto.txt"])
    }

    @Test fun rar4() {
        val f = fixture("rar4.rar")
        assertEquals(4, Unpack.rarVersion(f))
        val files = read(f, "rar4.rar")
        assertTrue("debería tener archivos: $files", files.isNotEmpty())
    }

    @Test fun rar4ConContrasena() {
        val f = fixture("rar4-contrasena.rar")
        expect<Unpack.NeedsPassword> { read(f, "x.rar") }
        val files = read(f, "x.rar", "junrar")
        assertTrue("debería sacar texto: $files", files.values.any { it.isNotBlank() })
        expect<Unpack.WrongPassword> { read(f, "x.rar", "mala") }
        val cifrado = fixture("rar4-cabecera-cifrada.rar")
        expect<Unpack.NeedsPassword> { read(cifrado, "y.rar") }
        assertTrue(read(cifrado, "y.rar", "junrar").isNotEmpty())
    }

    @Test fun rar5SinLectorDaUnErrorClaro() {
        val f = fixture("rar5.rar")
        assertEquals(5, Unpack.rarVersion(f))
        Unpack.rar5 = null
        expect<IllegalStateException> { read(f, "z.rar") }
    }

    @Test fun limiteDeTamano() {
        val out = Unpack.Limited(ByteArrayOutputStream(), 10)
        out.write(ByteArray(10))
        expect<Unpack.TooBig> { out.write(1) }
    }
}
