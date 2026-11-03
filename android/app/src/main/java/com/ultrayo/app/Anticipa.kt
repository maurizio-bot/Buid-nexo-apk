package com.ultrayo.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Adelantarse mientras Maurizio habla, para que todo parezca instantáneo. Solo prepara cosas que no
 * cambian nada (nunca abre, envía ni pulsa nada antes de que termine la frase):
 * - Al empezar a escuchar: tiene a mano la lista de apps y contactos y deja el modelo preparado.
 * - Si lo que va diciendo ya es una orden corta y completa ("qué hora es", "sí", "para") y se calla
 *   un momento, no espera al silencio final: la da por terminada.
 * - Si es una pregunta de internet (el tiempo, la moneda, noticias, "busca en internet…"), empieza a
 *   buscar mientras termina de hablar; si al final dice lo mismo, la respuesta ya está casi lista.
 */
object Anticipa {

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val pool = Executors.newFixedThreadPool(2)
    private var stable: Runnable? = null
    private val pending = ConcurrentHashMap<String, Pair<Long, Future<String>>>()

    /** Cuánto tiene que estar quieta la frase para darla por terminada o buscar por adelantado. */
    private const val QUIETA_MS = 400L

    private val completas = Regex(
        "^(?:qu[eé] hora es|qu[eé] d[ií]a es(?: hoy)?|s[ií]|no|vale|para|calla|c[aá]llate|basta|stop|silencio|atr[aá]s|" +
            "enciende la linterna|apaga la linterna|sube el volumen|baja el volumen|gracias|nada m[aá]s|rep[ií]te(?:lo)?|" +
            "c[oó]pialo|ll[aá]male|hazlo|adelante|cancela|s[ií],? hazlo|s[ií],? env[ií]alo|no,? d[eé]jalo)$",
        RegexOption.IGNORE_CASE,
    )

    /** Una orden corta que no puede seguir (no hace falta esperar al silencio final). */
    fun esOrdenCompleta(said: String): Boolean = completas.matches(QuickActions.clean(said))

    /** Empieza a escuchar: prepara lo que suele hacer falta. */
    fun onListenStart(context: Context) {
        val app = context.applicationContext
        pool.execute(Safe.task("tener a mano apps y contactos") { QuickActions.warm(app) })
        Assistant.prewarm()
    }

    /** Lo que va oyendo. Se llama en el hilo principal. */
    fun onPartial(context: Context, text: String) {
        stable?.let { main.removeCallbacks(it) }
        val app = context.applicationContext
        val r = Runnable { Safe.run("adelantarse") { onStable(app, text) } }
        stable = r
        main.postDelayed(r, QUIETA_MS)
    }

    fun onEnd() {
        stable?.let { main.removeCallbacks(it) }
        stable = null
    }

    private fun onStable(context: Context, text: String) {
        if (!Assistant.listening) return
        if (esOrdenCompleta(text)) {
            NeuralEar.finish()
            return
        }
        prefetch(context, text)
    }

    private fun key(text: String) = QuickActions.norm(QuickActions.clean(text))

    /** Busca por adelantado si es una pregunta de internet. */
    private fun prefetch(context: Context, text: String) {
        val k = key(text)
        if (k.split(" ").size < 2 || pending.containsKey(k)) return
        val clean = QuickActions.clean(text)
        val work: (() -> String)? = LiveInfo.direct(context, clean)
            ?: Assistant.webQueryOf(clean)?.let { q -> { WebSearch.readTop(q, pages = 3) } }
        if (work == null) return
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.first > 60_000 }
        pending[k] = now to pool.submit(Callable { work() })
    }

    /** Lo que ya buscó mientras hablaba, si la frase final es la misma; null si no hay nada. */
    fun take(text: String): Future<String>? = pending.remove(key(text))?.second

    /** Espera lo buscado por adelantado (como mucho [seconds]); null si no estaba o falló. */
    fun result(text: String, seconds: Long = 20): String? =
        take(text)?.let { runCatching { it.get(seconds, TimeUnit.SECONDS) }.getOrNull() }
}
