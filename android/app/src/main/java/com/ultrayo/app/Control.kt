package com.ultrayo.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Una acción en el teléfono que espera el "sí" de Maurizio: enviar, llamar, pagar, borrar…
 * [plan] es lo que se le enseña ("Voy a enviar a Juan por WhatsApp: llego tarde").
 * [run] la hace y devuelve qué decirle.
 */
class PendingAction(val plan: String, val run: () -> String)

/**
 * El mando del control del teléfono: la acción pendiente de confirmar, el botón de parar
 * y el registro de todo lo que Ultra Yo ha hecho. Ultra Yo nunca actúa por su cuenta:
 * solo cuando Maurizio se lo pide, y lo que no se puede deshacer espera su "sí".
 */
object Control {

    private val main = Handler(Looper.getMainLooper())

    /** La acción que espera confirmación. Solo hay una a la vez. */
    var pending by mutableStateOf<PendingAction?>(null)
        private set

    /** Está haciendo algo en otra app (pulsando, escribiendo…). */
    var acting by mutableStateOf(false)

    /** Maurizio ha pulsado Parar: no hacer nada más hasta su próxima orden. */
    @Volatile
    var stopped = false
        private set

    /** Permisos que hay que pedir en la pantalla (la app los pide al abrirse). */
    var permissionsNeeded by mutableStateOf<List<String>>(emptyList())

    private val yes = Regex(
        "^(s[ií]+|vale|venga|dale|ok(ay)?|claro|hazlo|adelante|env[ií]a(lo|la)?|ll[aá]ma(le|la)?|confirmo|correcto|de acuerdo|perfecto|s[ií],? (hazlo|env[ií]alo|claro|por favor|adelante))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val no = Regex("^(no|nada|cancela|d[eé]jalo|olv[ií]dalo|espera|mejor no|para)\\b", RegexOption.IGNORE_CASE)
    private val stopWords = Regex(
        "^(para(lo)?|detente|basta|stop|c[aá]llate|calla|cancela todo|qu[ií]eto|alto)[.!]*$",
        RegexOption.IGNORE_CASE,
    )

    /** Palabras de botones que no se pulsan sin preguntar. */
    private val risky = Regex(
        "enviar|env[ií]a|send|mandar|publicar|post|pagar|pago|pay|comprar|compra|buy|confirmar pedido|" +
            "borrar|eliminar|delete|remove|vaciar|desinstalar|uninstall|transferir|bizum|llamar|call|aceptar y pagar",
        RegexOption.IGNORE_CASE,
    )

    fun isRisky(label: String) = risky.containsMatchIn(label)

    fun isStop(text: String) = stopWords.matches(text.trim())

    /** Deja una acción esperando el "sí" y devuelve la pregunta para Maurizio. Se puede llamar desde cualquier hilo. */
    fun ask(plan: String, run: () -> String): String {
        val action = PendingAction(plan, run)
        if (Looper.myLooper() == Looper.getMainLooper()) pending = action else main.post { pending = action }
        return "$plan. ¿Lo hago?"
    }

    /**
     * Si hay una acción pendiente y [text] es un sí o un no, la hace o la descarta y devuelve qué decir.
     * Si es otra cosa, la pendiente se olvida (Maurizio ha pasado a otro tema) y devuelve null.
     */
    fun answer(text: String): String? {
        val action = pending ?: return null
        val t = text.trim().trimEnd('.', '!')
        return when {
            yes.containsMatchIn(t) -> confirm()
            no.containsMatchIn(t) -> cancel()
            else -> {
                pending = null
                null
            }
        }
    }

    /**
     * Encadena [next] detrás de la acción pendiente: se hace solo si Maurizio dice que sí
     * (por ejemplo, los pasos que quedan de un plan después de enviar). Desde el hilo principal.
     */
    fun thenAfter(next: () -> Unit) {
        val action = pending ?: return
        pending = PendingAction(action.plan) { action.run().also { main.post(next) } }
    }

    fun confirm(): String {
        val action = pending ?: return "No había nada pendiente."
        pending = null
        stopped = false
        return try {
            action.run().also { log("✓ ${action.plan}") }
        } catch (e: Exception) {
            log("✗ ${action.plan}: ${e.message}")
            "No pude hacerlo: ${e.message}"
        }
    }

    fun cancel(): String {
        val action = pending ?: return "Vale."
        pending = null
        log("Cancelado: ${action.plan}")
        return "Vale, no lo hago."
    }

    /** El botón de parar: corta lo que esté haciendo en otras apps, la voz y lo pendiente. */
    fun stop() {
        Diag.log("parar", "PARAR pulsado o dicho")
        stopped = true
        acting = false
        if (pending != null) log("Cancelado: ${pending?.plan}")
        pending = null
        UltraAccessibility.instance?.hideStopButton()
        Assistant.stopSpeaking()
        log("■ Parado por Maurizio")
    }

    /** Una orden nueva de Maurizio vuelve a permitir actuar. */
    fun resume() {
        stopped = false
    }

    // --- Registro ---

    val logLines = mutableStateListOf<String>()
    private var logFile: File? = null
    private val time = SimpleDateFormat("dd/MM HH:mm", Locale.forLanguageTag("es-ES"))
    private const val MAX_LOG = 300

    fun init(context: Context) {
        if (logFile != null) return
        val file = File(context.filesDir, "registro-acciones.txt")
        logFile = file
        if (file.exists()) logLines.addAll(file.readLines().takeLast(MAX_LOG))
    }

    /** Apunta algo que ha hecho. Se puede llamar desde cualquier hilo. */
    fun log(what: String) {
        val line = "${time.format(Date())}  $what"
        main.post {
            logLines.add(line)
            while (logLines.size > MAX_LOG) logLines.removeAt(0)
            runCatching { logFile?.writeText(logLines.joinToString("\n")) }
        }
    }

    fun clearLog() {
        logLines.clear()
        runCatching { logFile?.delete() }
    }
}
