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
