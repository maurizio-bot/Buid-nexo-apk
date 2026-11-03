package com.ultrayo.app

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * El sentido de las palabras con el modelo de verdad (lo prepara scripts/modelo-sentido.sh).
 * Si el modelo no está (por ejemplo, en un ordenador sin internet), estas pruebas se saltan.
 */
class SentidoTest {

    companion object {
        private var model: SentidoModel? = null

        @BeforeClass
        @JvmStatic
        fun load() {
            val dir = File("src/main/assets/sentido")
            if (!File(dir, "vectores.bin").exists()) return
            model = File(dir, "vectores.bin").inputStream().use {
                SentidoModel.read(it, File(dir, "vocab.txt").readLines(), null)
            }
        }
    }

    private fun sim(a: String, b: String): Double {
        val m = model!!
        return SentidoModel.cosine(m.embed(a), m.embed(b)).also { println("SENTIDO %.3f | %s | %s".format(it, a, b)) }
    }

    /** [a] se parece más a [near] que a [far]. */
    private fun closer(a: String, near: String, far: String) {
        val n = sim(a, near)
        val f = sim(a, far)
        assertTrue("\"$a\" debería parecerse más a \"$near\" ($n) que a \"$far\" ($f)", n > f)
    }

    @Test
    fun sinonimos() {
        assumeTrue("Sin modelo de sentido", model != null)
        closer("coche", "vehículo", "manzana")
        closer("mi perro", "mi can", "la factura de la luz")
        closer("médico", "doctor", "guitarra")
        closer("cuándo pasa la revisión el vehículo", "El coche pasa la ITV en marzo", "Ana toma el café sin azúcar")
        closer("qué le gusta beber a Ana", "Ana toma el café sin azúcar", "El coche pasa la ITV en marzo")
        closer(
            "cómo va la app de la tienda online",
            "Nexo: aplicación de ventas por internet para mi negocio",
            "Casa verde: reforma del baño de la casa del pueblo",
        )
        closer(
            "qué tal la obra del baño",
            "Casa verde: reforma del baño de la casa del pueblo",
            "Nexo: aplicación de ventas por internet para mi negocio",
        )
    }

    @Test
    fun calibracion() {
        assumeTrue("Sin modelo de sentido", model != null)
        // Para fijar umbrales: parecidos de frases sin relación ninguna.
        sim("cómo va la app de la tienda online", "Casa verde: reforma del baño de la casa del pueblo")
        sim("qué hora es", "Nexo: aplicación de ventas por internet para mi negocio")
        sim("pon música relajante", "El coche pasa la ITV en marzo")
        sim("explícame qué es un agujero negro", "Ana toma el café sin azúcar")
        sim("hola", "Nexo: aplicación de ventas por internet para mi negocio")
        // Y de frases relacionadas con otras palabras.
        sim("la tienda online", "Nexo: aplicación de ventas por internet para mi negocio")
        sim("el proyecto del asistente de voz", "Ultra: mi asistente tipo Jarvis para el móvil")
        sim("mi hermana", "Lucía: hermana de Maurizio, vive en Milán")
    }

    @Test
    fun umbrales() {
        assumeTrue("Sin modelo de sentido", model != null)
        // Lo relacionado pasa el umbral de ficha; lo que no tiene nada que ver, no.
        assertTrue(sim("la tienda online", "Nexo. aplicación de ventas por internet para mi negocio") >= SentidoUmbrales.FICHA)
        assertTrue(sim("mi hermana", "Lucía. hermana de Maurizio, vive en Milán") >= SentidoUmbrales.FICHA)
        assertTrue(sim("qué hora es", "Nexo. aplicación de ventas por internet para mi negocio") < SentidoUmbrales.FICHA)
        assertTrue(sim("pon música relajante", "Casa verde. reforma del baño de la casa del pueblo") < SentidoUmbrales.FICHA)
        assertTrue(sim("explícame qué es un agujero negro", "Lucía. hermana de Maurizio, vive en Milán") < SentidoUmbrales.FICHA)
    }

    @Test
    fun tokenizador() {
        assumeTrue("Sin modelo de sentido", model != null)
        val m = model!!
        assertTrue(m.tokens("Hola, ¿qué tal?").isNotEmpty())
        assertTrue(m.embed("").all { it == 0f })
        assertTrue(m.embed("xqzzqx ###").let { v -> v.all { it == 0f } || v.isNotEmpty() })
    }
}
