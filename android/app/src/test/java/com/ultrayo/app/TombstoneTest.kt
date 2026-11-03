package com.ultrayo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * El lector del informe de cierre nativo de Android. Se construye un informe falso con el mismo
 * formato (protobuf de debuggerd) y se comprueba que el resumen dice la señal, la librería y la función.
 */
class TombstoneTest {

    // --- Un escritor mínimo de protobuf, solo para las pruebas ---
    private class W {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (true) {
                if (x and 0x7fL.inv() == 0L) { out.write(x.toInt()); return }
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
        }
        fun int(num: Int, v: Long) = apply { varint((num shl 3).toLong()); varint(v) }
        fun bytes(num: Int, b: ByteArray) = apply { varint(((num shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        fun str(num: Int, s: String) = bytes(num, s.toByteArray())
        fun msg(num: Int, m: W) = bytes(num, m.out.toByteArray())
        fun build(): ByteArray = out.toByteArray()
    }

    private fun frame(pc: Long, fn: String, file: String) = W().int(1, pc).str(4, fn).int(5, 16).str(6, file)

    private fun sample(gpu: Boolean): ByteArray {
        val signal = W().int(1, 11).str(2, "SIGSEGV").int(3, 1).str(4, "SEGV_MAPERR").int(8, 1).int(9, 0x10)
        val crashing = W().int(1, 4321).str(2, "pool-3-thread-1")
            .msg(4, frame(0x1234, "litert::lm::Session::Decode", if (gpu) "/data/app/x/lib/arm64/libLiteRtGpuAccelerator.so" else "/data/app/x/lib/arm64/liblitertlm_jni.so"))
            .msg(4, frame(0x99, "start_thread", "/apex/com.android.runtime/lib64/bionic/libc.so"))
        val other = W().int(1, 1).str(2, "main").msg(4, frame(0x1, "nativePollOnce", "/system/lib64/libandroid_runtime.so"))
        val logs = W().str(1, "main")
            .msg(2, W().str(1, "00:27:14").int(3, 4321).int(4, 6).str(5, "litert").str(6, "Decode failed"))
            .msg(2, W().str(1, "00:27:14").int(3, 7).int(4, 3).str(5, "ruido").str(6, "nada"))
        return W()
            .int(6, 4321)
            .msg(10, signal)
            .msg(15, W().str(1, "null pointer dereference"))
            .msg(16, W().int(1, 1).msg(2, other))
            .msg(16, W().int(1, 4321).msg(2, crashing))
            .msg(18, logs)
            .build()
    }

    @Test fun resumeElCierre() {
        val info = Tombstone.parse(sample(gpu = false))
        assertNotNull(info)
        info!!
        assertEquals(4321, info.tid)
        assertEquals("pool-3-thread-1", info.threadName)
        assertTrue(info.signal, info.signal.startsWith("SIGSEGV (SEGV_MAPERR)"))
        assertTrue(info.signal.endsWith("0x10"))
        assertEquals("liblitertlm_jni.so", info.suspect)
        assertTrue(info.frames.first().contains("litert::lm::Session::Decode+16"))
        assertEquals(listOf("null pointer dereference"), info.causes)
        // Del registro, solo lo del hilo que falló o avisos/errores.
        assertEquals(listOf("E litert: Decode failed"), info.logs)
        assertFalse(info.looksLikeGpu)
        assertTrue(info.text().contains("Librería sospechosa: liblitertlm_jni.so"))
    }

    @Test fun reconoceLaGpu() {
        assertTrue(Tombstone.parse(sample(gpu = true))!!.looksLikeGpu)
    }

    @Test fun datosRotosNoLanzan() {
        assertNull(Tombstone.parse(ByteArray(0)))
        assertNull(Tombstone.read(java.io.ByteArrayInputStream(byteArrayOf(0x0a, 0x7f, 0x01))))
        assertNull(Tombstone.read(null))
        // Un informe cortado por la mitad sigue sin lanzar.
        val cut = sample(gpu = false).let { it.copyOf(it.size / 2) }
        Tombstone.parse(cut)
    }
}
