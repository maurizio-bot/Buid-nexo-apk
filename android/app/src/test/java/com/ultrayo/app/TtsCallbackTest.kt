package com.ultrayo.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La librería de voz (sherpa-onnx) llama desde C++ a `invoke(float[]): Integer` en la clase del callback.
 * Si ese método no existe con esa firma exacta, la app se cierra de golpe al hablar (pasó en el S24 con la 0.28.1).
 */
class TtsCallbackTest {

    @Test fun tieneElMetodoQueBuscaLaLibreriaNativa() {
        val callback = TtsCallback { it.size }
        val method = callback.javaClass.getMethod("invoke", FloatArray::class.java)
        assertEquals(Integer::class.java, method.returnType)
        assertEquals(3, method.invoke(callback, floatArrayOf(1f, 2f, 3f)))
    }
}
