package com.ultrayo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas rápidas de la lógica que no necesita teléfono. Corren en el ordenador (y en GitHub
 * Actions) en segundos: si alguien rompe las cuentas, el nombre "Ultra" o las fichas, se ve aquí.
 */
class CalculadoraTest {

    private fun calc(text: String): Double = Calculator.eval(Calculator.fromSpeech(text)!!)

    @Test fun cuentasDichas() {
        assertEquals(103.0, calc("Cuánto es 25 por 4 más 3?"), 1e-9)
        assertEquals(5.0, calc("calcula 10 entre 2"), 1e-9)
        assertEquals(1024.0, calc("cuánto es 2 elevado a 10"), 1e-9)
    }

    @Test fun cuentasEscritas() {
        assertEquals(14.0, Calculator.eval("2+3*4"), 1e-9)
        assertEquals(512.0, Calculator.eval("2^3^2"), 1e-9)
        assertEquals(220.0, Calculator.eval("200+10%"), 1e-9)
        assertEquals(4.0, Calculator.eval("raiz(16)"), 1e-9)
        assertEquals(7.0, Calculator.eval("3,5*2"), 1e-9)
        assertEquals(9.0, Calculator.eval("(1+2)*3"), 1e-9)
    }

    @Test fun noEsUnaCuenta() {
        assertNull(Calculator.fromSpeech("cuánto es la vida"))
        assertNull(Calculator.fromSpeech("qué tal estás"))
    }

    @Test fun cuentaRotaNoCierraNada() {
        // Lanza un error normal (que Assistant captura), nunca algo que cierre la app.
        for (bad in listOf("2+", "(((", "1.2.3", "foo(2)", "")) {
            val result = runCatching { Calculator.eval(bad) }
            assertTrue("\"$bad\" debería fallar con una excepción normal", result.exceptionOrNull() is Exception)
        }
    }

    @Test fun formato() {
        assertEquals("1.234", Calculator.format(1234.0))
        assertEquals("0,5", Calculator.format(0.5))
        assertEquals("no tiene un resultado definido", Calculator.format(Double.NaN))
    }
}

class PalabraUltraTest {

    @Test fun laLlaman() {
        assertEquals("qué hora es", WakeWord.find("Ultra, qué hora es"))
        assertEquals("qué hora es", WakeWord.find("ulta qué hora es"))
        assertEquals("pon música", WakeWord.find("Ultra Yo, pon música"))
        assertEquals("", WakeWord.find("ultra"))
        assertEquals("abre whatsapp", WakeWord.find("oye ultra abre whatsapp"))
    }

    @Test fun noLaLlaman() {
        assertNull(WakeWord.find("ultravioleta"))
        assertNull(WakeWord.find("ultra violeta es un color"))
        assertNull(WakeWord.find("otra vez lo mismo"))
        assertNull(WakeWord.find("me han puesto una multa"))
        assertNull(WakeWord.find(""))
    }
}

class NombresOidosTest {

    @Test fun cifrasDichasYEscritasSonIguales() {
        assertEquals(Spoken.key("Proyecto 1"), Spoken.key("proyecto uno"))
        assertEquals(Spoken.key("proyecto1"), Spoken.key("Proyecto 1"))
    }

    @Test fun encuentraNombresMalOidos() {
        assertTrue(Spoken.find(Spoken.words("abre el proyecto uno"), "Proyecto 1") > 0)
        assertTrue(Spoken.find(Spoken.words("háblame de Kasa Verde"), "Casa verde") > 0)
    }

    @Test fun noConfundeNumeros() {
        assertFalse(Spoken.close(Spoken.key("proyecto 1"), Spoken.key("proyecto 2")))
        assertEquals(0, Spoken.find(Spoken.words("abre el proyecto dos"), "Proyecto 1"))
    }

    @Test fun frasesVaciasOCortas() {
        assertEquals(0, Spoken.find(emptyList(), "Proyecto 1"))
        assertEquals(0, Spoken.find(Spoken.words("hola"), "ab"))
    }
}

class PensarTest {

    @Test fun quitaElRazonamiento() {
        assertEquals("Respuesta", Thinking.strip("<pensar>razono mucho</pensar>Respuesta"))
        assertEquals("Hola", Thinking.strip("Hola"))
        // Si solo pensó y no contestó, se enseña lo pensado (limpio) antes que nada.
        assertEquals("solo pensé", Thinking.strip("<pensar>solo pensé</pensar>"))
    }

    @Test fun filtroPorTrozos() {
        val f = Thinking.Filter()
        val out = listOf("Vale. <pen", "sar>secreto</pe", "nsar>\nHola", " Maurizio").joinToString("") { f.feed(it) }
        assertEquals("Vale. Hola Maurizio", out)
    }

    @Test fun soloPiensaEnLoDificil() {
        assertFalse(Thinking.isHard("qué hora es"))
        assertTrue(Thinking.isHard("piensa en cómo organizar la semana"))
    }
}

class OrdenesTest {

    @Test fun limpiaLaOrden() {
        assertEquals("qué hora es", QuickActions.clean("Ultra, qué hora es?"))
        assertEquals("abre whatsapp", QuickActions.clean("oye ultra abre whatsapp por favor"))
    }

    @Test fun normaliza() {
        assertEquals("como esta el telefono", QuickActions.norm("¿Cómo está el teléfono?"))
    }

    @Test fun tipoDeDato() {
        assertEquals("contactos", PhoneData.kind("contactos"))
        assertEquals("agenda", PhoneData.kind("Agenda"))
        assertEquals("sms", PhoneData.kind("SMS"))
        assertEquals("llamadas", PhoneData.kind("llamadas perdidas"))
        assertEquals("sistema", PhoneData.kind("batería"))
        assertNull(PhoneData.kind("pizza"))
    }

    @Test fun recortaListasLargas() {
        val lines = List(500) { "Línea número $it con bastante texto para ocupar sitio" }
        val clipped = PhoneData.clip(lines)
        assertTrue(clipped.length <= PhoneData.MAX + 80)
        assertTrue(clipped.contains("más; pide con un nombre"))
    }

    @Test fun crearImagen() {
        val command = ImageCommands.parse("crea una imagen de un gato astronauta", hasImage = false)
        assertNotNull(command)
        assertEquals("un gato astronauta", (command as ImageCommand.Create).description)
        assertNull(ImageCommands.parse("qué hora es", hasImage = false))
    }
}
