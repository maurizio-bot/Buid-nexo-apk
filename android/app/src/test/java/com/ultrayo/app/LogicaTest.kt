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

class TareasTest {

    @Test fun vueloYWhatsApp() {
        val pasos = Tareas.plan("Ultra, busca el vuelo más barato a Madrid y mándaselo a Juan por WhatsApp")!!
        assertEquals(2, pasos.size)
        assertEquals("el vuelo más barato a Madrid", (pasos[0] as Paso.Buscar).consulta)
        val enviar = pasos[1] as Paso.Enviar
        assertEquals("Juan", enviar.quien)
        assertFalse(enviar.sms)
    }

    @Test fun variosPasosConLuego() {
        val pasos = Tareas.plan("abre WhatsApp, luego pulsa Chats y después lee la pantalla")!!
        assertEquals(3, pasos.size)
        assertTrue(pasos.all { it is Paso.Orden })
    }

    @Test fun enviarPorSms() {
        val pasos = Tareas.plan("mira el tiempo en Sevilla mañana y envíaselo a mamá por SMS")!!
        assertTrue(pasos[0] is Paso.Buscar)
        assertTrue((pasos[1] as Paso.Enviar).sms)
        assertEquals("mamá", (pasos[1] as Paso.Enviar).quien)
    }

    @Test fun unaSolaOrdenNoEsPlan() {
        assertNull(Tareas.plan("mándale un whatsapp a Juan que llego tarde y que me espere"))
        assertNull(Tareas.plan("busca recetas de paella en google"))
        assertNull(Tareas.plan("pon una alarma a las 7 y media"))
        assertNull(Tareas.plan("qué hora es"))
        assertNull(Tareas.plan("busca vuelos de ida y vuelta a Roma"))
    }

    @Test fun mensajeConTextoEsOrden() {
        val pasos = Tareas.plan("abre WhatsApp y mándale un whatsapp a Juan que llego tarde")!!
        assertTrue(pasos[1] is Paso.Orden)
    }

    @Test fun planDelModelo() {
        val pasos = Tareas.desdeModelo("1. busca hoteles en Roma baratos\n2. mándaselo a Pedro")!!
        assertEquals(2, pasos.size)
        assertTrue(pasos[0] is Paso.Buscar)
        assertTrue(pasos[1] is Paso.Enviar)
        assertTrue(Tareas.textoDelPlan(pasos).startsWith("Plan de 2 pasos"))
    }
}

class ReferenciasTest {

    @Test fun mandaselo() {
        val r = Referencias.resolver("mándaselo a Juan por WhatsApp", "El vuelo más barato es de Iberia, 49 euros.")
        assertEquals(
            "mándale un whatsapp a Juan que El vuelo más barato es de Iberia, 49 euros.",
            (r as Referencias.Resuelto.Orden).texto,
        )
        val sms = Referencias.resolver("envíaselo a mamá por SMS", "Mañana llueve.") as Referencias.Resuelto.Orden
        assertTrue(sms.texto.startsWith("mándale un sms a mamá que"))
    }

    @Test fun alDeAntes() {
        Referencias.ultimoContacto = "Pepe Pérez"
        assertEquals("llama a Pepe Pérez", (Referencias.resolver("llámale", null) as Referencias.Resuelto.Orden).texto)
        assertEquals(
            "mándale un whatsapp a Pepe Pérez que llego tarde",
            (Referencias.resolver("mándale un whatsapp que llego tarde", null) as Referencias.Resuelto.Orden).texto,
        )
        assertEquals(
            "mándale un whatsapp a Pepe Pérez que Son las 5.",
            (Referencias.resolver("mándaselo", "Son las 5.") as Referencias.Resuelto.Orden).texto,
        )
        Referencias.ultimoContacto = null
        assertNull(Referencias.resolver("llámale", null))
    }

    @Test fun repetirCopiarYEnlace() {
        assertTrue(Referencias.resolver("repítelo", "Hola") is Referencias.Resuelto.Repetir)
        assertTrue(Referencias.resolver("cópialo", "Hola") is Referencias.Resuelto.Copiar)
        val link = Referencias.resolver("ábrelo", "Mira aquí: https://ejemplo.com/vuelos.") as Referencias.Resuelto.AbrirEnlace
        assertEquals("https://ejemplo.com/vuelos", link.url)
    }

    @Test fun noSeRefiereANada() {
        assertNull(Referencias.resolver("mándale un whatsapp a Juan que llego tarde", "Hola"))
        assertNull(Referencias.resolver("manda a Juan", "Hola"))
        assertNull(Referencias.resolver("qué hora es", "Hola"))
    }

    @Test fun despedidas() {
        assertTrue(Referencias.esDespedida("Gracias"))
        assertTrue(Referencias.esDespedida("vale, gracias"))
        assertTrue(Referencias.esDespedida("nada más"))
        assertFalse(Referencias.esDespedida("gracias, ahora pon música"))
    }
}
