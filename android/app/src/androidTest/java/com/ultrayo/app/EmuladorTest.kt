package com.ultrayo.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Prueba de humo en un emulador Android (GitHub Actions), sin el modelo grande:
 * abre la app, recorre todos los ajustes y lanza órdenes directas. Si algo cierra la app,
 * la prueba falla. Deja capturas y las respuestas en Android/data/com.ultrayo.app/files/prueba.
 *
 * Lo que un emulador NO puede comprobar: la GPU/NPU del S24 (modelo e imágenes), el micrófono
 * real, el Bluetooth ni el oído/voz rápidos (sus librerías son solo para el procesador del teléfono).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EmuladorTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = context.packageName
    private val out = File(context.getExternalFilesDir(null), "prueba").apply { mkdirs() }

    private fun openApp() {
        device.wakeUp()
        runCatching { device.executeShellCommand("wm dismiss-keyguard") }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        assertTrue("La app no se abrió", device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 20_000))
        assertTrue("No sale la cabecera ULTRA YO", device.wait(Until.hasObject(By.text("ULTRA YO")), 20_000))
    }

    private fun shot(name: String) {
        runCatching { device.takeScreenshot(File(out, "$name.png")) }
    }

    private fun note(line: String) {
        File(out, "respuestas.txt").appendText(line + "\n")
    }

    private fun assertAlive(where: String) {
        // Si la app se hubiera cerrado, esta prueba (que corre dentro de ella) ya habría muerto;
        // además miramos que no haya un informe de cierre guardado.
        val crash = App.crashFile(context)
        if (crash.exists()) fail("La app guardó un cierre en $where:\n${crash.readText().take(2000)}")
    }

    /** Le dice algo a Ultra como si lo escribiera Maurizio y devuelve lo que contesta. */
    private fun ask(text: String, timeoutMs: Long = 20_000): String {
        var before = 0
        instrumentation.runOnMainSync {
            before = Assistant.turns.size
            Assistant.send(text)
        }
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            var reply: String? = null
            instrumentation.runOnMainSync {
                reply = Assistant.turns.drop(before).lastOrNull { it.role == "assistant" }?.content
            }
            if (!reply.isNullOrBlank()) {
                note("> $text\n$reply\n")
                assertAlive("\"$text\"")
                return reply!!
            }
            SystemClock.sleep(200)
        }
        note("> $text\n(sin respuesta en ${timeoutMs / 1000} s)\n")
        fail("Ultra no contestó a \"$text\"")
        error("")
    }

    @Test
    fun a_abreSinCerrarse() {
        App.crashFile(context).delete()
        openApp()
        shot("01-inicio")
        assertAlive("el arranque")
    }

    @Test
    fun b_recorreTodosLosAjustes() {
        openApp()
        val settings = device.wait(Until.findObject(By.desc("Memoria y ajustes")), 10_000)
            ?: run {
                shot("02-sin-ajustes")
                fail("No encuentro el botón de ajustes")
                return
            }
        settings.click()
        SystemClock.sleep(1500)
        shot("02-ajustes-arriba")
        // Baja por todas las tarjetas: cada una se dibuja al aparecer, así que si alguna falla se cierra aquí.
        val w = device.displayWidth
        val h = device.displayHeight
        for (i in 1..40) {
            device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 20)
            SystemClock.sleep(300)
            if (i % 8 == 0) shot("03-ajustes-$i")
            assertEquals("La app dejó de estar delante al bajar por los ajustes", pkg, device.currentPackageName)
        }
        assertAlive("los ajustes")
        device.findObject(By.desc("Cerrar"))?.click()
        SystemClock.sleep(800)
        shot("04-ajustes-cerrados")
        assertAlive("cerrar los ajustes")
    }

    @Test
    fun c_ordenesAlInstante() {
        openApp()
        assertTrue(ask("qué hora es").startsWith("Son las"))
        assertTrue(ask("Ultra, qué día es hoy").startsWith("Hoy es"))
        assertEquals("Son 103.", ask("cuánto es 25 por 4 más 3"))
        assertTrue(ask("cuánta batería me queda").contains("%"))
        ask("cómo está el teléfono")
        ask("cuánto espacio me queda")
        ask("tengo internet")
        ask("qué tengo hoy en la agenda")
        ask("mis últimos sms")
        ask("últimas llamadas")
        ask("quién me ha llamado")
        ask("tengo notificaciones")
        ask("enciende la linterna")
        ask("apaga la linterna")
        ask("sube el volumen")
        assertEquals("Parado.", ask("para"))
        shot("05-ordenes")
    }

    @Test
    fun d_ordenesQuePidenConfirmacion() {
        openApp()
        // Sin contactos en el emulador: tiene que decir que no lo encuentra, nunca llamar ni cerrarse.
        ask("llama a Pepe Pérez")
        ask("mándale un whatsapp a Pepe que llego tarde")
        ask("mándale un SMS a Pepe que llego tarde")
        // Una acción con confirmación: se cancela.
        ask("crea una habilidad llamada saludo: di hola con energía")
        ask("no")
        shot("06-confirmaciones")
    }

    @Test
    fun e_memoriaYFichasSinModelo() {
        openApp()
        ask("nuevo proyecto Prueba Emulador: una app para comprobar que todo abre")
        ask("recuerdas proyecto prueba emulador")
        ask("apunta en Prueba Emulador: el emulador no tiene GPU")
        // Sin modelo descargado tiene que contestar algo con sentido, no cerrarse.
        ask("explícame qué es un agujero negro")
        shot("07-fichas")
    }

    @Test
    fun f_ordenesQueAbrenOtrasApps() {
        openApp()
        ask("pon una alarma a las 7 y media")
        device.pressHome()
        openApp()
        ask("abre ajustes")
        device.pressHome()
        openApp()
        ask("llévame a la Puerta del Sol")
        device.pressHome()
        openApp()
        ask("busca recetas de paella en google")
        device.pressHome()
        openApp()
        shot("08-vuelta")
        assertAlive("las órdenes que abren otras apps")
    }
}
