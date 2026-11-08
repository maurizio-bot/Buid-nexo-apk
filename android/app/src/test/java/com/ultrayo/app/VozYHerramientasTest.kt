package com.ultrayo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arreglos de la 0.31.0: nombres de herramientas mal escritos, pausas al hablar y texto para la voz. */
class VozYHerramientasTest {

    private val tools = listOf("verMiMente", "proponerReglas", "proponerPersonalidad", "buscarArchivos", "leerFicha")

    @Test fun encuentraLaHerramientaAunqueElModeloEscribaMalElNombre() {
        assertEquals("verMiMente", ToolNames.resolve("vermi_mente", tools))
        assertEquals("verMiMente", ToolNames.resolve("ver_mi_mente", tools))
        assertEquals("verMiMente", ToolNames.resolve("verMiMente", tools))
        assertEquals("proponerReglas", ToolNames.resolve("proponer_regla", tools))
        assertNull(ToolNames.resolve("pintarCuadro", tools))
        assertNull(ToolNames.resolve("", tools))
    }

    @Test fun nombreEnFormaDeGuionesBajos() {
        assertEquals("ver_mi_mente", ToolNames.snake("verMiMente"))
    }

    @Test fun esperaMasSiLaFraseQuedaAMedias() {
        assertTrue(Pausas.looksUnfinished("quiero que me digas cuántos proyectos tengo y"))
        assertTrue(Pausas.looksUnfinished("apúntame que mañana tengo que ir a"))
        assertTrue(Pausas.looksUnfinished("pon una alarma para"))
        assertFalse(Pausas.looksUnfinished("qué hora es"))
        assertFalse(Pausas.looksUnfinished("llama a mamá"))
        assertTrue(Pausas.extraWaitMs("pon una alarma para", longPauses = false) > Pausas.extraWaitMs("qué hora es", longPauses = false))
        assertTrue(Pausas.extraWaitMs("qué hora es", longPauses = true) > 0)
        assertEquals(0L, Pausas.extraWaitMs("qué hora es", longPauses = false))
    }

    @Test fun dejaElTextoListoParaDecirlo() {
        assertEquals("Sube un 20 por ciento.", Habla.limpiar("Sube un 20%."))
        assertEquals("Cuesta 15 euros.", Habla.limpiar("Cuesta 15 €."))
        assertEquals("Hecho.", Habla.limpiar("**Hecho.** ✅"))
        assertEquals("Mira el enlace", Habla.limpiar("Mira https://example.com/a/b"))
        assertEquals("Es el 8 del 11 de 2026", Habla.limpiar("Es el 8/11/2026"))
        assertEquals("Ve a 100 kilómetros por hora", Habla.limpiar("Ve a 100 km/h"))
        assertEquals("Uno. Dos", Habla.limpiar("- Uno\n- Dos"))
        assertEquals("Entrada o salida", Habla.limpiar("entrada/salida").replaceFirstChar { it.uppercase() })
    }
}

/** El eco de la propia voz de Ultra no debe tomarse como Maurizio (0.31.1). */
class EcoTest {

    private val said = Eco.words(
        "Claro, te cuento lo que tengo apuntado de tus proyectos. Ahora mismo estás contestando los correos " +
            "del cliente y preparando la presentación para el viernes.",
    ).toSet()

    @Test fun suPropiaVozMalOidaEsEco() {
        assertTrue(Eco.isEcho("con testando los correos del cliente y prepara", said))
        assertTrue(Eco.isEcho("te cuento lo que tengo apuntao de tus proyecto", said))
        assertTrue(Eco.isEcho("para el viernes", said))
    }

    @Test fun maurizioHablandoNoEsEco() {
        assertFalse(Eco.isEcho("espera quiero otra cosa distinta", said))
        assertFalse(Eco.isEcho("del cliente y preparando oye cambia de tema ahora", said))
        assertFalse(Eco.isEcho("calla", said))
    }

    @Test fun quitaElEcoDeDelante() {
        assertEquals("Oye cambia de tema", Eco.stripEcho("contestando los correos oye cambia de tema", said))
        assertEquals("", Eco.stripEcho("los correos del cliente", said))
    }
}

/** La puerta del micrófono mientras Ultra habla (0.32.0). */
class EchoGateTest {

    /** Su voz por el altavoz llega al micrófono al 40 % (con altibajos): nunca abre. */
    @Test fun suEcoNoAbreLaPuerta() {
        val gate = EchoGate()
        val rnd = java.util.Random(1)
        repeat(400) {
            val ref = 0.05f + rnd.nextFloat() * 0.25f
            val mic = ref * (0.3f + rnd.nextFloat() * 0.2f)
            assertFalse(gate.feed(mic, ref))
        }
    }

    /** Maurizio habla encima (mucho más fuerte que el eco esperado): abre en 150 ms. */
    @Test fun unaVozDeFueraAbreLaPuerta() {
        val gate = EchoGate()
        repeat(40) { gate.feed(0.04f, 0.1f) }
        assertFalse(gate.feed(0.3f, 0.1f))
        assertFalse(gate.feed(0.3f, 0.1f))
        assertTrue(gate.feed(0.3f, 0.1f))
    }

    /** Un golpe suelto (un trozo fuerte) no la abre. */
    @Test fun unGolpeSueltoNoAbre() {
        val gate = EchoGate()
        repeat(40) { gate.feed(0.04f, 0.1f) }
        assertFalse(gate.feed(0.5f, 0.1f))
        assertFalse(gate.feed(0.04f, 0.1f))
        assertFalse(gate.feed(0.5f, 0.1f))
    }

    /** Cuando Ultra calla y la sala está en silencio, hablar abre. */
    @Test fun conEllaCalladaHablarAbre() {
        val gate = EchoGate()
        repeat(20) { gate.feed(0.002f, 0f) }
        gate.feed(0.1f, 0f)
        gate.feed(0.1f, 0f)
        assertTrue(gate.feed(0.1f, 0f))
    }
}
