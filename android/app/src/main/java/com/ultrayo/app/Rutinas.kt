package com.ultrayo.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Una rutina que Maurizio ha encargado. */
data class Rutina(
    val id: Long,
    /** "resumen" (resumen de la mañana a una hora) o "aviso" (avisar si escribe alguien). */
    val tipo: String,
    val hora: Int = 8,
    val minuto: Int = 0,
    val quien: String = "",
    /** Decirlo también en voz alta (si no, solo un aviso en el teléfono). */
    val voz: Boolean = false,
) {
    fun describir(): String = when (tipo) {
        RESUMEN -> "Resumen de la mañana a las %d:%02d (agenda, tiempo y mensajes)%s".format(hora, minuto, if (voz) ", en voz alta" else "")
        else -> "Avisarte si te escribe $quien${if (voz) ", en voz alta" else ""}"
    }

    companion object {
        const val RESUMEN = "resumen"
        const val AVISO = "aviso"
    }
}

/**
 * Rutinas que Maurizio activa (Ultra nunca se las pone sola): un resumen por la mañana de la agenda,
 * el tiempo y los mensajes, y "avísame si me escribe X". Se guardan en el teléfono y se quitan con
 * "quita la rutina…" o en Ajustes > Rutinas.
 */
object Rutinas {

    private fun prefs(context: Context) = context.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)

    fun lista(context: Context): List<Rutina> {
        val array = runCatching { JSONArray(prefs(context).getString("rutinas", "[]")) }.getOrDefault(JSONArray())
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Rutina(o.getLong("id"), o.getString("tipo"), o.optInt("hora", 8), o.optInt("minuto", 0), o.optString("quien"), o.optBoolean("voz"))
        }
    }

    private fun guardar(context: Context, rutinas: List<Rutina>) {
        val array = JSONArray()
        rutinas.forEach {
            array.put(JSONObject().put("id", it.id).put("tipo", it.tipo).put("hora", it.hora).put("minuto", it.minuto).put("quien", it.quien).put("voz", it.voz))
        }
        prefs(context).edit().putString("rutinas", array.toString()).apply()
        programar(context)
    }

    fun quitar(context: Context, id: Long) = guardar(context, lista(context).filterNot { it.id == id })

    // --- Lo que dice Maurizio ---

    /** Lo que quiere hacer con las rutinas. */
    sealed class Pedido {
        data class Resumen(val hora: Int, val minuto: Int, val voz: Boolean) : Pedido()
        data class Aviso(val quien: String, val voz: Boolean) : Pedido()
        object Listar : Pedido()
        object ResumenAhora : Pedido()
        object QuitarResumen : Pedido()
        data class QuitarAviso(val quien: String) : Pedido()
    }

    private val resumen = Regex(
        "^(?:(?:cada|todas las) ma[nñ]anas?|(?:cada d[ií]a|todos los d[ií]as))?,? ?(?:a las ([\\wáé]+)(?:[:.](\\d{2})| y (media|cuarto))?)?,? ?" +
            "(?:d[aá]me|hazme|ponme|l[eé]eme|quiero|activa|pon)? ?(?:un|el)? ?resumen(?: matinal| de la ma[nñ]ana| del d[ií]a| diario)?" +
            "(?: cada ma[nñ]ana| todas las ma[nñ]anas| cada d[ií]a| todos los d[ií]as)?(?: a las ([\\wáé]+)(?:[:.](\\d{2})| y (media|cuarto))?)?(?: en voz alta)?$",
        RegexOption.IGNORE_CASE,
    )
    private val aviso = Regex(
        "^av[ií]same (?:si|cuando) (?:me )?(?:escrib[ae]n?|manda(?:n)? (?:un )?(?:mensaje|whatsapp)|llega (?:un )?mensaje de|llegue (?:un )?mensaje de|escriba) ?(?:a m[ií] )?(?:de )?(.+?)(?: en voz alta)?$",
        RegexOption.IGNORE_CASE,
    )
    private val listar = Regex("^(?:(?:cu[aá]les son |dime )?mis rutinas|qu[eé] rutinas tengo|rutinas)$", RegexOption.IGNORE_CASE)
    private val ahora = Regex("^(?:d[aá]me|hazme|l[eé]eme)? ?(?:el )?resumen (?:de hoy|del d[ií]a|de la ma[nñ]ana)(?: ahora)?$|^resumen ahora$", RegexOption.IGNORE_CASE)
    private val quitarResumen = Regex(
        "^(?:quita|borra|cancela|desactiva|elimina|apaga)(?: la rutina del?)? (?:el )?resumen(?: matinal| de la ma[nñ]ana| del d[ií]a| diario)?$",
        RegexOption.IGNORE_CASE,
    )
    private val quitarAviso = Regex(
        "^(?:deja de avisarme (?:de|si me escribe|cuando me escriba)|no me avises (?:m[aá]s )?(?:de|si me escribe)|(?:quita|borra|cancela|desactiva|elimina) (?:la rutina|el aviso) de) (.+)$",
        RegexOption.IGNORE_CASE,
    )

    private fun hora(palabra: String): Int? = QuickActions.number(palabra)?.takeIf { it in 0..23 }

    fun entender(said: String): Pedido? {
        val t = QuickActions.clean(said)
        val voz = Regex("en voz alta", RegexOption.IGNORE_CASE).containsMatchIn(t)
        if (listar.matches(t)) return Pedido.Listar
        if (ahora.matches(t)) return Pedido.ResumenAhora
        if (quitarResumen.matches(t)) return Pedido.QuitarResumen
        quitarAviso.find(t)?.let { return Pedido.QuitarAviso(it.groupValues[1].trim()) }
        aviso.find(t)?.let { m ->
            val quien = m.groupValues[1].trim().removePrefix("a ").trim()
            if (quien.isNotEmpty() && quien.split(" ").size <= 4) return Pedido.Aviso(quien, voz)
        }
        resumen.find(t)?.let { m ->
            // Tiene que decir cuándo o que es de la mañana/diario: "resumen" a secas no es una rutina.
            val cuando = Regex("ma[nñ]ana|d[ií]a|matinal|diario|a las", RegexOption.IGNORE_CASE).containsMatchIn(t)
            if (!cuando) return null
            val h = m.groupValues[1].ifEmpty { m.groupValues[4] }
            val mm = m.groupValues[2].ifEmpty { m.groupValues[5] }
            val extra = m.groupValues[3].ifEmpty { m.groupValues[6] }
            val hour = if (h.isEmpty()) 8 else hora(h) ?: return null
            val minute = mm.toIntOrNull() ?: when (extra.lowercase()) {
                "media" -> 30
                "cuarto" -> 15
                else -> 0
            }
            return Pedido.Resumen(hour, minute.coerceIn(0, 59), voz)
        }
        return null
    }

    /** Hace lo que pide y devuelve qué decir, o null si no era sobre rutinas. */
    fun atender(context: Context, said: String, onSummary: (String) -> Unit): String? {
        val c = context.applicationContext
        return when (val p = entender(said) ?: return null) {
            is Pedido.Resumen -> {
                val rest = lista(c).filterNot { it.tipo == Rutina.RESUMEN }
                guardar(c, rest + Rutina(System.currentTimeMillis(), Rutina.RESUMEN, p.hora, p.minuto, voz = p.voz))
                "Hecho: cada mañana a las %d:%02d te dejo un resumen de tu agenda, el tiempo y tus mensajes%s. Dime «quita el resumen» para quitarlo."
                    .format(p.hora, p.minuto, if (p.voz) " y te lo leo en voz alta si estoy escuchando" else "")
            }
            is Pedido.Aviso -> {
                val rest = lista(c).filterNot { it.tipo == Rutina.AVISO && QuickActions.norm(it.quien) == QuickActions.norm(p.quien) }
                guardar(c, rest + Rutina(System.currentTimeMillis(), Rutina.AVISO, quien = p.quien, voz = p.voz))
                val access = if (UltraNotifications.enabled(c)) "" else " Para eso necesito leer tus notificaciones: actívalo en Ajustes > Revisar permisos."
                "Vale, te aviso cuando te escriba ${p.quien}.$access"
            }
            Pedido.Listar -> {
                val all = lista(c)
                if (all.isEmpty()) "No tienes rutinas. Por ejemplo: «cada mañana a las 8 dame un resumen» o «avísame si me escribe Juan»."
                else "Tus rutinas:\n" + all.joinToString("\n") { "• " + it.describir() }
            }
            Pedido.ResumenAhora -> {
                Thread { onSummary(Safe.run("preparar el resumen") { resumenDelDia(c) } ?: "No pude preparar el resumen.") }.start()
                "Te lo preparo…"
            }
            Pedido.QuitarResumen -> {
                val all = lista(c)
                if (all.none { it.tipo == Rutina.RESUMEN }) "No tenías el resumen de la mañana puesto."
                else {
                    guardar(c, all.filterNot { it.tipo == Rutina.RESUMEN })
                    "Quitado el resumen de la mañana."
                }
            }
            is Pedido.QuitarAviso -> {
                val all = lista(c)
                val gone = all.filter { it.tipo == Rutina.AVISO && QuickActions.norm(it.quien).contains(QuickActions.norm(p.quien)) }
                if (gone.isEmpty()) "No tenía ningún aviso de ${p.quien}."
                else {
                    guardar(c, all - gone.toSet())
                    "Ya no te aviso de ${gone.joinToString(", ") { it.quien }}."
                }
            }
        }
    }

    // --- El resumen de la mañana ---

    /** Agenda de hoy, el tiempo y lo que ha llegado. Usa red: fuera del hilo principal. */
    fun resumenDelDia(context: Context): String = buildString {
        append("Buenos días. ")
        append(runCatching { PhoneData.calendar(context, "hoy", limit = 6) }.getOrDefault("No pude mirar tu agenda."))
        append("\n\n")
        append(runCatching { LiveInfo.weather(context, null, tomorrow = false) }.getOrDefault("No pude mirar el tiempo (¿hay conexión?)."))
        if (UltraNotifications.instance != null) {
            append("\n\nMensajes y avisos: ")
            append(runCatching { UltraNotifications.list(context, "", limit = 6) }.getOrDefault("no pude leerlos."))
        }
    }

    // --- Programar ---

    /** Vuelve a programar el resumen (al abrir la app, al cambiar rutinas o al encender el teléfono). */
    fun programar(context: Context) {
        val c = context.applicationContext
        val alarms = c.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            c, 7, Intent(c, RutinasReceiver::class.java).setAction(ACCION_RESUMEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val r = lista(c).firstOrNull { it.tipo == Rutina.RESUMEN }
        if (r == null) {
            alarms.cancel(intent)
            return
        }
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, r.hora)
            set(Calendar.MINUTE, r.minuto)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 30_000) add(Calendar.DAY_OF_YEAR, 1)
        }
        // Sin alarma exacta (necesitaría otro permiso): Android la da en esa hora, a veces con unos minutos de retraso.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, intent)
    }

    // --- Avisos ---

    private val lastAlert = HashMap<String, Long>()

    /** Le llega una notificación: si es de alguien por quien pidió que le avisara, le avisa. */
    fun alLlegar(context: Context, app: String, title: String, text: String) {
        val avisos = lista(context).filter { it.tipo == Rutina.AVISO }
        if (avisos.isEmpty()) return
        val from = QuickActions.norm(title)
        val r = avisos.firstOrNull { from.isNotEmpty() && from.contains(QuickActions.norm(it.quien)) } ?: return
        val now = System.currentTimeMillis()
        synchronized(lastAlert) {
            // Una vez por minuto por persona, aunque mande varios mensajes seguidos.
            if (now - (lastAlert[r.quien] ?: 0L) < 60_000) return
            lastAlert[r.quien] = now
        }
        val said = "Te ha escrito $title por $app" + if (text.isNotBlank()) ": ${text.take(200)}" else "."
        avisar(context, "Te ha escrito ${title}", said, id = 300 + (r.quien.hashCode() and 0xff))
        if (r.voz && (UltraService.instance != null || Assistant.visible)) Assistant.notify(said)
        Control.log("Aviso: $said".take(120))
    }

    fun avisar(context: Context, title: String, text: String, id: Int) {
        val c = context.applicationContext
        val manager = c.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CANAL, "Rutinas de Ultra", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CANAL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(c).notify(id, n) }
    }

    const val ACCION_RESUMEN = "com.ultrayo.app.RESUMEN"
    private const val CANAL = "rutinas"
}

/** Despierta a la hora del resumen (y al encender el teléfono, para volver a programarlo). */
class RutinasReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        if (intent.action != Rutinas.ACCION_RESUMEN) {
            Safe.run("programar rutinas") { Rutinas.programar(c) }
            return
        }
        val pending = goAsync()
        Thread {
            try {
                val r = Rutinas.lista(c).firstOrNull { it.tipo == Rutina.RESUMEN }
                if (r != null) {
                    val text = Safe.run("preparar el resumen de la mañana") { Rutinas.resumenDelDia(c) } ?: "No pude preparar el resumen."
                    Rutinas.avisar(c, "Tu resumen de la mañana", text, id = 299)
                    if (r.voz && UltraService.instance != null) Assistant.notify(text)
                }
                Safe.run("programar el siguiente resumen") { Rutinas.programar(c) }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
