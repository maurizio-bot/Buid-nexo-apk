package com.ultrayo.app

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Leer la información del teléfono: contactos, agenda, SMS, llamadas, notificaciones y el estado
 * del sistema. Todo es de solo lectura y solo cuando Maurizio lo pide. Cada respuesta se recorta
 * (pocas líneas y como mucho [MAX] letras) para que quepa en la memoria del modelo sin cerrarse.
 */
object PhoneData {

    const val MAX = 2400

    private val es = Locale.forLanguageTag("es-ES")
    private val dayTime = SimpleDateFormat("EEE d MMM HH:mm", es)
    private val day = SimpleDateFormat("EEEE d 'de' MMMM", es)
    private val hour = SimpleDateFormat("HH:mm", es)

    /** Los permisos de lectura que se piden juntos en Ajustes. */
    val readPermissions = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
    )

    fun granted(c: Context, permission: String) =
        ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED

    /** Qué tipo de dato es: contactos, agenda, sms, llamadas, notificaciones o sistema. */
    fun kind(what: String): String? {
        val w = QuickActions.norm(what)
        return when {
            w.startsWith("contact") || w.contains("telefono de") || w.contains("numero") -> "contactos"
            w.contains("agenda") || w.contains("calendario") || w.contains("evento") || w.contains("cita") -> "agenda"
            w.contains("sms") || w.contains("mensaje") -> "sms"
            w.contains("llamada") || w.contains("registro") -> "llamadas"
            w.contains("notifica") || w.contains("aviso") -> "notificaciones"
            w.contains("sistema") || w.contains("bateria") || w.contains("almacenamiento") || w.contains("espacio") ||
                w.contains("memoria") || w.contains("red") || w.contains("wifi") || w.contains("datos") ||
                w.contains("estado") || w.contains("ram") -> "sistema"
            else -> null
        }
    }

    fun query(c: Context, what: String, filter: String, voice: Boolean = false): String {
        val limit = if (voice) 5 else 12
        return try {
            read(c, what, filter, limit)
        } catch (e: SecurityException) {
            "Android no me deja leer eso ahora (falta un permiso). Ábreme y te lo pido."
        } catch (e: Exception) {
            // Algunos teléfonos tienen la agenda o el registro de llamadas con columnas distintas.
            ErrorLog.add("Fallo al consultar $what", e)
            "No pude leer $what: ${Safe.describe(e)}."
        }
    }

    private fun read(c: Context, what: String, filter: String, limit: Int): String {
        return when (kind(what)) {
            "contactos" -> contacts(c, filter, limit)
            "agenda" -> calendar(c, filter, limit)
            "sms" -> sms(c, filter, limit)
            "llamadas" -> calls(c, filter, limit)
            "notificaciones" -> UltraNotifications.list(c, filter, limit)
            "sistema" -> system(c)
            else -> "No sé qué es \"$what\". Puedo consultar contactos, agenda, sms, llamadas, notificaciones o sistema."
        }
    }

    // --- Contactos ---

    fun contacts(c: Context, filter: String, limit: Int = 12): String {
        if (!granted(c, Manifest.permission.READ_CONTACTS)) return missing(c, Manifest.permission.READ_CONTACTS, "tus contactos")
        val f = filter.trim()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = if (f.isEmpty()) null else "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = if (f.isEmpty()) null else arrayOf("%$f%")
        val lines = mutableListOf<String>()
        val seen = HashSet<String>()
        var total = 0
        c.contentResolver.query(uri, projection, selection, args, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0) ?: continue
                val number = cur.getString(1)?.replace(" ", "") ?: continue
                if (!seen.add(name + number.takeLast(9))) continue
                total++
                if (lines.size < limit) lines += "$name: $number"
            }
        }
        if (lines.isEmpty()) return if (f.isEmpty()) "No hay contactos." else "No encuentro a nadie que se llame $f en tus contactos."
        return clip(lines, total)
    }

    // --- Agenda ---

    /** [filter]: hoy, mañana, semana, mes, o palabras del título (busca en los próximos 90 días). */
    fun calendar(c: Context, filter: String, limit: Int = 12): String {
        if (!granted(c, Manifest.permission.READ_CALENDAR)) return missing(c, Manifest.permission.READ_CALENDAR, "tu agenda")
        val f = QuickActions.norm(filter)
        val start = Calendar.getInstance()
        val end = Calendar.getInstance()
        var words = ""
        var label: String
        when {
            f.contains("pasado manana") -> {
                start.startOfDay(2); end.startOfDay(3); label = "pasado mañana"
            }
            f.contains("manana") -> {
                start.startOfDay(1); end.startOfDay(2); label = "mañana"
            }
            f.isEmpty() || f.contains("hoy") -> {
                end.startOfDay(1); label = "hoy"
            }
            f.contains("semana") -> {
                end.startOfDay(7); label = "los próximos 7 días"
            }
            f.contains("mes") -> {
                end.startOfDay(31); label = "los próximos 30 días"
            }
            else -> {
                end.startOfDay(90); label = "los próximos 90 días"; words = filter.trim()
            }
        }
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start.timeInMillis)
        ContentUris.appendId(builder, end.timeInMillis)
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val selection = if (words.isEmpty()) null else "${CalendarContract.Instances.TITLE} LIKE ?"
        val args = if (words.isEmpty()) null else arrayOf("%$words%")
        val lines = mutableListOf<String>()
        var total = 0
        c.contentResolver.query(builder.build(), projection, selection, args, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
            while (cur.moveToNext()) {
                total++
                if (lines.size >= limit) continue
                val title = cur.getString(0)?.ifBlank { null } ?: "(sin título)"
                val begin = cur.getLong(1)
                val allDay = cur.getInt(2) == 1
                val place = cur.getString(3)?.trim().orEmpty()
                val time = when {
                    label == "hoy" || label == "mañana" || label == "pasado mañana" -> if (allDay) "todo el día" else hour.format(Date(begin))
                    allDay -> day.format(Date(begin))
                    else -> dayTime.format(Date(begin))
                }
                lines += "$time: $title" + if (place.isNotEmpty()) " ($place)" else ""
            }
        }
        if (lines.isEmpty()) {
            return if (words.isNotEmpty()) "No hay nada con \"$words\" en tu agenda en $label." else "No tienes nada en la agenda $label."
        }
        return "Agenda de $label:\n" + clip(lines, total)
    }

    private fun Calendar.startOfDay(plusDays: Int) {
        add(Calendar.DAY_OF_YEAR, plusDays)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /** Abre la agenda con el evento ya rellenado: Maurizio lo guarda (o no) con un toque. */
    fun addEvent(c: Context, title: String, date: String, time: String, place: String): String {
        val d = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(date)
            ?: return "Dame la fecha como AAAA-MM-DD, por ejemplo 2026-11-20."
        val cal = Calendar.getInstance()
        cal.set(d.groupValues[1].toInt(), d.groupValues[2].toInt() - 1, d.groupValues[3].toInt(), 9, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val t = Regex("(\\d{1,2})[:.](\\d{2})").find(time)
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (t != null) {
            cal.set(Calendar.HOUR_OF_DAY, t.groupValues[1].toInt())
            cal.set(Calendar.MINUTE, t.groupValues[2].toInt())
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cal.timeInMillis + 3_600_000)
        } else {
            intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
        }
        if (place.isNotBlank()) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, place)
        return try {
            c.startActivity(intent)
            Control.log("Agenda abierta para apuntar: $title")
            "Te abro la agenda con \"$title\" ya rellenado; pulsa Guardar si está bien."
        } catch (e: Exception) {
            "No encontré ninguna app de agenda."
        }
    }

    // --- SMS ---

    fun sms(c: Context, filter: String, limit: Int = 10): String {
        if (!granted(c, Manifest.permission.READ_SMS)) return missing(c, Manifest.permission.READ_SMS, "tus SMS", restricted = true)
        val f = QuickActions.norm(filter)
        val unreadOnly = f.contains("sin leer") || f.contains("no leido")
        val words = if (unreadOnly) "" else f
        val numbers = if (words.isEmpty()) emptySet() else numbersOf(c, words)
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ)
        val lines = mutableListOf<String>()
        var total = 0
        var scanned = 0
        val names = HashMap<String, String>()
        c.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { cur ->
            while (cur.moveToNext() && scanned < 3000) {
                scanned++
                val address = cur.getString(0).orEmpty()
                val body = cur.getString(1).orEmpty().replace('\n', ' ')
                if (unreadOnly && cur.getInt(4) == 1) continue
                val who = names.getOrPut(address) { nameOf(c, address) ?: address }
                if (words.isNotEmpty()) {
                    val hit = QuickActions.norm(who).contains(words) || QuickActions.norm(body).contains(words) ||
                        numbers.any { address.filter(Char::isDigit).endsWith(it) }
                    if (!hit) continue
                }
                total++
                if (lines.size >= limit) continue
                val sent = cur.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT
                val text = if (body.length > 160) body.take(160) + "…" else body
                lines += "${dayTime.format(Date(cur.getLong(2)))} ${if (sent) "tú a" else "de"} $who: $text"
            }
        }
        if (lines.isEmpty()) return if (unreadOnly) "No tienes SMS sin leer." else "No encontré SMS" + if (words.isNotEmpty()) " de \"$filter\"." else "."
        return clip(lines, total)
    }

    // --- Llamadas ---

    fun calls(c: Context, filter: String, limit: Int = 10): String {
        if (!granted(c, Manifest.permission.READ_CALL_LOG)) return missing(c, Manifest.permission.READ_CALL_LOG, "tu historial de llamadas", restricted = true)
        val f = QuickActions.norm(filter)
        val missedOnly = f.contains("perdida")
        val words = if (missedOnly) "" else f
        val numbers = if (words.isEmpty()) emptySet() else numbersOf(c, words)
        val projection = arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        val selection = if (missedOnly) "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}" else null
        val lines = mutableListOf<String>()
        var total = 0
        var scanned = 0
        c.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, selection, null, "${CallLog.Calls.DATE} DESC")?.use { cur ->
            while (cur.moveToNext() && scanned < 2000) {
                scanned++
                val number = cur.getString(1).orEmpty()
                val who = cur.getString(0)?.ifBlank { null } ?: number.ifBlank { "número oculto" }
                if (words.isNotEmpty()) {
                    val hit = QuickActions.norm(who).contains(words) || numbers.any { number.filter(Char::isDigit).endsWith(it) }
                    if (!hit) continue
                }
                total++
                if (lines.size >= limit) continue
                val type = when (cur.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "recibida de"
                    CallLog.Calls.OUTGOING_TYPE -> "hecha a"
                    CallLog.Calls.MISSED_TYPE -> "perdida de"
                    CallLog.Calls.REJECTED_TYPE -> "rechazada de"
                    CallLog.Calls.BLOCKED_TYPE -> "bloqueada de"
                    else -> "de"
                }
                val secs = cur.getLong(4)
                val length = if (secs > 0) " (${secs / 60} min ${secs % 60} s)" else ""
                lines += "${dayTime.format(Date(cur.getLong(3)))}: $type $who$length"
            }
        }
        if (lines.isEmpty()) return if (missedOnly) "No tienes llamadas perdidas." else "No encontré llamadas" + if (words.isNotEmpty()) " de \"$filter\"." else "."
        return clip(lines, total)
    }

    /** Los últimos 9 dígitos de los números de los contactos que se llaman así (para buscar SMS y llamadas). */
    private fun numbersOf(c: Context, name: String): Set<String> {
        if (!granted(c, Manifest.permission.READ_CONTACTS)) return emptySet()
        val out = HashSet<String>()
        runCatching {
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null,
            )?.use { cur ->
                while (cur.moveToNext() && out.size < 20) {
                    val digits = cur.getString(0).orEmpty().filter(Char::isDigit)
                    if (digits.length >= 6) out += digits.takeLast(9)
                }
            }
        }
        return out
    }

    private fun nameOf(c: Context, number: String): String? {
        if (number.isBlank() || !granted(c, Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(number).build()
            c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        }.getOrNull()
    }

    // --- Sistema ---

    fun system(c: Context): String = buildString {
        val battery = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = c.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        append("Batería: $level %").append(if (bm.isCharging) ", cargando" else "")
        if (temp > 0) append(", ").append(String.format(es, "%.0f", temp)).append(" °C")
        append('\n')
        storage().let { append("Almacenamiento: ").append(it).append('\n') }
        val am = c.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        append("Memoria RAM libre: ${gb(mem.availMem)} de ${gb(mem.totalMem)}\n")
        append("Red: ").append(network(c)).append('\n')
        val dnd = c.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        if (dnd != NotificationManager.INTERRUPTION_FILTER_ALL && dnd != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) append("No molestar: activado\n")
        val up = SystemClock.elapsedRealtime() / 3_600_000
        append("Teléfono: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, encendido hace ")
        append(if (up >= 48) "${up / 24} días" else "$up h")
    }

    fun storage(): String {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        return "${gb(stat.availableBytes)} libres de ${gb(stat.totalBytes)}"
    }

    fun network(c: Context): String {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "sin conexión"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "datos móviles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "cable"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "otra red"
        }
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val speed = caps.linkDownstreamBandwidthKbps / 1000
        return kind + (if (internet) ", con internet" else ", sin internet") + if (speed > 0) " (~$speed Mbps)" else ""
    }

    private fun gb(bytes: Long): String = String.format(es, "%.1f GB", bytes / 1_073_741_824.0)

    // --- Comunes ---

    /** Une las líneas sin pasar de [MAX] letras y dice cuántas se han quedado fuera. */
    fun clip(lines: List<String>, total: Int = lines.size): String {
        val out = StringBuilder()
        var shown = 0
        for (line in lines) {
            if (out.length + line.length + 1 > MAX) break
            out.append(line).append('\n')
            shown++
        }
        if (total > shown) out.append("(y ${total - shown} más; pide con un nombre o palabra para afinar)")
        return out.toString().trimEnd()
    }

    private fun missing(c: Context, permission: String, what: String, restricted: Boolean = false): String {
        Control.permissionsNeeded = listOf(permission)
        return "Necesito permiso para leer $what: abre Ultra Yo y acéptalo (o en Memoria > Acceso al teléfono)." +
            if (restricted) " Si Android no te deja, ve a Ajustes > Aplicaciones > Ultra Yo > ⋮ > Permitir ajustes restringidos." else ""
    }
}
