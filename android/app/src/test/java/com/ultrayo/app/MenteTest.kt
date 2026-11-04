package com.ultrayo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Que entienda cuando Maurizio le habla de su personalidad y sus reglas, y nada más. */
class MenteTest {

    private fun e(text: String, justAnalyzed: Boolean = false) = Mente.entender(text, justAnalyzed)

    @Test fun preguntaSiPuede() {
        assertEquals(Mente.Pedido.Capacidad, e("¿Eres capaz de autoanalizarte?"))
        assertEquals(Mente.Pedido.Capacidad, e("puedes modificar tu personalidad"))
        assertEquals(Mente.Pedido.Capacidad, e("¿Puedes cambiar tus reglas?"))
        assertTrue(e("¿Puedes cambiar tu personalidad para que seas más directo?") is Mente.Pedido.Cambiar)
    }

    @Test fun analizarse() {
        assertEquals(Mente.Pedido.Analizar, e("Analízate"))
        assertEquals(Mente.Pedido.Analizar, e("hazte un autoanálisis"))
        assertEquals(Mente.Pedido.Analizar, e("¿Qué mejorarías de ti?"))
        assertEquals(Mente.Pedido.Analizar, e("revisa tu forma de responder"))
    }

    @Test fun aplicarSoloTrasAnalizarse() {
        assertEquals(Mente.Pedido.AplicarAnalisis, e("Aplica esos cambios", justAnalyzed = true))
        assertEquals(Mente.Pedido.AplicarAnalisis, e("sí, hazlo", justAnalyzed = true))
        assertNull(e("hazlo"))
    }

    @Test fun cambios() {
        assertEquals(Mente.Pedido.Cambiar("Sé más directo"), e("Sé más directo."))
        assertTrue(e("cambia tu personalidad, quiero que seas más seria") is Mente.Pedido.Cambiar)
        assertTrue(e("Quiero que me hables de usted") is Mente.Pedido.Cambiar)
        assertTrue(e("háblame más corto") is Mente.Pedido.Cambiar)
    }

    @Test fun reglas() {
        assertEquals(Mente.Pedido.NuevaRegla("Nunca me digas que no puedes"), e("Añade la regla: nunca me digas que no puedes"))
        assertEquals(Mente.Pedido.NuevaRegla("Llámame jefe"), e("a partir de ahora llámame jefe"))
        assertEquals(Mente.Pedido.QuitarRegla("llamarme jefe"), e("quita la regla de llamarme jefe"))
        assertEquals(Mente.Pedido.Ver, e("¿Cuáles son tus reglas?"))
        assertEquals("- Llámame jefe", Mente.findRule("- Sé breve\n- Llámame jefe", "llamarme jefe")?.let { "- $it" })
    }

    @Test fun loDemasNoEsSuyo() {
        for (t in listOf("cambia el volumen", "puedes llamar a Juan", "habla con Juan", "revisa tu memoria", "qué tiempo hace", "sube el brillo")) {
            assertNull(t, e(t))
        }
    }

    @Test fun leeLaReescritura() {
        val r = Mente.parseRewrite("PERSONALIDAD:\nEres Ultra Yo, directo y serio.\nREGLAS:\n- Sé breve\n- Di cuando no sabes", "")
        assertNotNull(r)
        assertEquals("Eres Ultra Yo, directo y serio.", r!!.first)
        assertEquals("- Sé breve\n- Di cuando no sabes", r.second)
        val sinReglas = Mente.parseRewrite("PERSONALIDAD:\nEres Ultra Yo, directo y serio.\nREGLAS:\n- (ninguna)", "- vieja")
        assertEquals("", sinReglas!!.second)
        assertNull(Mente.parseRewrite("vale", ""))
    }
}
