package com.ultrayo.app

import com.ultrayo.app.apk.ApkInspector
import com.ultrayo.app.apk.Decompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Entender una APK en el ordenador: mini.apk lleva el manifiesto, resources.arsc y un dex
 * (el de sherpa-onnx) de una versión de Ultra Yo, una librería nativa y un recurso.
 */
class ApkTest {

    private val apk: File = Files.createTempFile("mini", ".apk").toFile().also { out ->
        javaClass.getResourceAsStream("/apk/mini.apk")!!.use { input -> out.outputStream().use { input.copyTo(it) } }
    }

    @Test fun manifiesto() {
        val r = ApkInspector.analyze(apk, "mini.apk")
        assertEquals("com.ultrayo.app", r.packageName)
        assertTrue(r.versionName.isNotEmpty())
        assertEquals(29, r.minSdk)
        assertTrue("android.permission.RECORD_AUDIO" in r.permissions)
        assertEquals("com.ultrayo.app.MainActivity", r.launcher)
        assertEquals("com.ultrayo.app.App", r.appClass)
        assertTrue(r.components.any { it.kind == "service" })
        assertTrue(r.manifestXml.contains("<manifest") && r.manifestXml.contains("android:name=\"com.ultrayo.app.MainActivity\""))
    }

    @Test fun codigoLibreriasEIdiomas() {
        val r = ApkInspector.analyze(apk, "mini.apk")
        assertEquals(1, r.dexFiles)
        assertTrue(r.classCount > 10)
        assertTrue("sherpa-onnx (voz)" in r.sdks)
        assertEquals(listOf("libprueba.so"), r.abis["arm64-v8a"])
        assertTrue("es" in r.languages && "en" in r.languages)
        assertEquals(1, r.resources["layout"])
        val text = ApkInspector.summary(r)
        assertTrue(text.contains("Paquete: com.ultrayo.app"))
        assertTrue("el resumen tiene que caber en la conversación: ${text.length}", text.length < 3500)
    }

    @Test fun buscarYDecompilarUnaClase() {
        val found = ApkInspector.findClasses(apk, "Recognizer")
        assertTrue(found.toString(), found.isNotEmpty() && found.all { it.contains("Recognizer", ignoreCase = true) })
        val name = found.first()
        assertEquals(listOf(name), ApkInspector.findClasses(apk, name))
        val code = ApkInspector.decompile(apk, name, Files.createTempDirectory("jadx").toFile())
        assertTrue(code.take(300), code.contains(name.substringAfterLast('.').substringAfterLast('$')))
        assertFalse("sin imports", code.lines().any { it.startsWith("import ") })
    }

    @Test fun paqueteXapkConVariasApk() {
        val bundle = Files.createTempFile("app", ".xapk").toFile()
        ZipOutputStream(bundle.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write("{}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("com.ultrayo.app.apk")); apk.inputStream().use { it.copyTo(z) }; z.closeEntry()
            z.putNextEntry(ZipEntry("config.arm64_v8a.apk")); z.write(ByteArray(10)); z.closeEntry()
        }
        val (base, splits) = ApkInspector.baseApk(bundle, Files.createTempDirectory("base").toFile())
        assertEquals(listOf("config.arm64_v8a.apk"), splits)
        assertEquals("com.ultrayo.app", ApkInspector.analyze(base, "app.xapk", splits).packageName)
    }

    @Test fun importsResumidos() {
        val code = "package a;\n\nimport b.C;\nimport d.E;\n\n@Metadata(d1 = {\"x\"}, k = 1)\nclass X {}\n"
        assertEquals("package a;\n// (2 imports)\n\nclass X {}\n", Decompiler.withoutImports(code))
    }

    @Test fun versionesDeAndroid() {
        assertEquals("Android 14 (API 34)", ApkInspector.androidName(34))
        assertEquals("Android 10 (API 29)", ApkInspector.androidName(29))
    }
}
