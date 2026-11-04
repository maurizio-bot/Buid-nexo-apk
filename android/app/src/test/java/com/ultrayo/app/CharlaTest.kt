package com.ultrayo.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Hola" y "¿cómo estás?" se contestan al instante; lo que lleva una petición de verdad, no. */
class CharlaTest {

    @Test fun saludosYComoEstas() {
        for (s in listOf("hola", "Hola!", "¡Hola, Ultra!", "buenos días", "Buenas", "¿Cómo estás?", "hola qué tal", "oye ultra, ¿y tú qué tal?",
            "¿estás ahí?", "¿quién eres?", "hola, ¿cómo estás?", "Ultra, buenas noches")) {
            assertNotNull(s, Charla.responder(s, hour = 10))
        }
    }

    @Test fun peticionesNoSonCharla() {
        for (s in listOf("hola, qué tiempo hace", "cómo estás de batería", "buenos días, llama a mamá", "qué tal funciona el proyecto1",
            "hola busca en internet recetas", "qué hora es", "abre whatsapp", "gracias")) {
            assertNull(s, Charla.responder(s, hour = 10))
        }
    }

    @Test fun valeTrasUnaPreguntaLoVeElModelo() {
        assertNull(Charla.responder("vale", anterior = "¿Quieres que lo busque en internet?", hour = 10))
        assertNotNull(Charla.responder("vale", anterior = "Hecho, alarma a las 7.", hour = 10))
        assertNotNull(Charla.responder("bien", anterior = "Bien, con todo listo. ¿Y tú?", hour = 10))
        assertNotNull(Charla.responder("hola", anterior = "¿Quieres que lo busque?", hour = 10))
    }

    @Test fun buenosDiasPorLaTarde() {
        assertTrue(Charla.responder("buenos días", hour = 17)!!.contains("tarde"))
    }
}
