package com.ultrayo.app

import android.Manifest
import android.app.KeyguardManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Órdenes del teléfono que se hacen al instante, sin pasar por el modelo: abrir apps, llamar,
 * mensajes, alarmas, temporizadores, Maps, linterna, volumen, botones del sistema y la pantalla.
 * Es el camino rápido: si la frase encaja aquí, Ultra Yo actúa en milisegundos.
 * Lo que no se puede deshacer (llamar, enviar, pulsar "pagar"…) espera el "sí" de Maurizio.
 */
object QuickActions {

    private val es = Locale.forLanguageTag("es-ES")

    /** Devuelve qué decir si [said] era una orden del teléfono, o null si hay que pasársela al modelo. */
    fun handle(context: Context, said: String): String? {
        val text = clean(said)
        if (text.isEmpty()) return null
        val c = context.applicationContext
        return try {
            info(c, text)
                ?: phoneInfo(c, text)
                ?: torch(c, text)
                ?: volume(c, text)
                ?: alarm(c, text)
                ?: timer(c, text)
                ?: call(c, text)
                ?: message(c, text)
                ?: navigate(c, text)
                ?: media(c, text)
                ?: search(c, text)
                ?: systemPanel(c, text)
                ?: systemButton(text)
                ?: screen(text)
                ?: openApp(c, text)
        } catch (e: SecurityException) {
            "Me falta un permiso para eso. Ábreme y te lo pido."
        } catch (e: Exception) {
            // Una app que no está, un dato raro del sistema…: se apunta y se dice, sin cerrar la app.
            ErrorLog.add("Fallo en la orden rápida \"${text.take(80)}\"", e)
            "No pude hacerlo: ${Safe.describe(e)}."
        }
    }

    /** Quita "Ultra", "oye", "por favor" y la puntuación del final. */
    fun clean(said: String): String = said.trim().trimStart('¿', '¡').trim()
        .replace(Regex("^(oye |hola |eh )?(ultra( yo)?)[,.]?\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(por favor|porfa)[,]?\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[,]?\\s*(por favor|porfa)$", RegexOption.IGNORE_CASE), "")
        .trim().trim('.', '!', '?', '¡', '¿').trim()

    // --- Al instante ---

    private fun info(c: Context, t: String): String? {
        if (Regex("^(qu[eé] hora es|dime la hora|la hora)$", RegexOption.IGNORE_CASE).matches(t)) {
            return "Son las ${SimpleDateFormat("H:mm", es).format(Date())}."
        }
        if (Regex("^(qu[eé] d[ií]a es( hoy)?|a qu[eé] (d[ií]a )?estamos( hoy)?|qu[eé] fecha es( hoy)?)$", RegexOption.IGNORE_CASE).matches(t)) {
            return "Hoy es ${SimpleDateFormat("EEEE d 'de' MMMM", es).format(Date())}."
        }
        if (Regex("^(cu[aá]nta bater[ií]a (me queda|tengo|hay|queda)|(c[oó]mo (va|est[aá]) la )?bater[ií]a)$", RegexOption.IGNORE_CASE).matches(t)) {
            val level = c.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return "Te queda un $level % de batería."
        }
        return null
    }

    /** Preguntas sobre el teléfono que se contestan leyendo, sin pasar por el modelo. */
    private fun phoneInfo(c: Context, t: String): String? {
        val n = norm(t)
        if (locked(c)) return null
        Regex("^(?:que |cuales )?(?:notificaciones|avisos) (?:tengo|hay|me han llegado)$|^(?:tengo notificaciones|que me ha llegado)$")
            .find(n)?.let { return UltraNotifications.list(c, "", limit = 5) }
        Regex("^(?:leeme|lee|dime|mira|ensename) (?:las |mis )?notificaciones(?: de (.+))?$").find(n)?.let {
            return UltraNotifications.list(c, it.groupValues[1], limit = 5)
        }
        Regex("^(?:quien me ha llamado|quien me llamo|tengo llamadas perdidas|llamadas perdidas)$").find(n)?.let {
            return PhoneData.calls(c, "perdidas", limit = 5)
        }
        Regex("^(?:ultimas llamadas|mis ultimas llamadas|registro de llamadas)$").find(n)?.let { return PhoneData.calls(c, "", limit = 5) }
        Regex("^(?:que tengo|tengo algo|que hay en (?:la|mi) agenda|(?:mi )?agenda(?: de)?) (hoy|manana|pasado manana|esta semana)(?: en la agenda)?$").find(n)?.let {
            return PhoneData.calendar(c, it.groupValues[1], limit = 6)
        }
        Regex("^(?:tengo )?sms sin leer$|^(?:tengo )?mensajes de texto sin leer$").find(n)?.let { return PhoneData.sms(c, "sin leer", limit = 5) }
        Regex("^(?:mis )?ultimos sms$").find(n)?.let { return PhoneData.sms(c, "", limit = 5) }
        if (Regex("^cuanto espacio (?:me queda|tengo|libre|hay|queda)(?: en el (?:telefono|movil))?$|^(?:cuanto )?almacenamiento(?: libre)?$").matches(n)) {
            return "Tienes ${PhoneData.storage()}."
        }
        if (Regex("^(?:estoy conectado|tengo internet|tengo conexion|a que red estoy conectado|tengo wifi|estoy con wifi o datos)$").matches(n)) {
            return "Estás con ${PhoneData.network(c)}."
        }
        if (Regex("^(?:como esta|estado del?) (?:el )?(?:telefono|movil|sistema)$").matches(n)) return PhoneData.system(c)
        return null
    }

    private fun torch(c: Context, t: String): String? {
        val m = Regex("^(enciende|activa|pon|prende|apaga|desactiva|quita)?\\s*(la )?linterna( (encendida|apagada))?$", RegexOption.IGNORE_CASE).find(t) ?: return null
        val verb = m.groupValues[1].lowercase()
        val on = when {
            verb in setOf("apaga", "desactiva", "quita") || m.groupValues[4] == "apagada" -> false
            verb.isEmpty() && m.groupValues[4].isEmpty() -> !torchOn
            else -> true
        }
        val camera = c.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Este teléfono no tiene linterna."
        camera.setTorchMode(id, on)
        torchOn = on
        Control.log(if (on) "Linterna encendida" else "Linterna apagada")
        return if (on) "Linterna encendida." else "Linterna apagada."
    }

    private var torchOn = false

    private fun volume(c: Context, t: String): String? {
        val audio = c.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        fun set(level: Int, what: String): String {
            audio.setStreamVolume(stream, level.coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
            Control.log(what)
            return "$what."
        }
        Regex("^(pon (el )?)?volumen (al |a )?(\\w+)( ?%| por ciento)?$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val word = m.groupValues[4].lowercase()
            val pct = when (word) {
                "maximo", "máximo", "tope" -> 100
                "minimo", "mínimo" -> 0
                "medio", "mitad" -> 50
                else -> number(word) ?: return null
            }.coerceIn(0, 100)
            return set(Math.round(max * pct / 100f), "Volumen al $pct %")
        }
        if (Regex("^(sube|subir|m[aá]s) (el )?(volumen|sonido)( un poco| mucho)?$|^m[aá]s alto$", RegexOption.IGNORE_CASE).matches(t)) {
            val step = if (t.contains("mucho")) max / 3 else max / 8
            return set(audio.getStreamVolume(stream) + step.coerceAtLeast(1), "Volumen subido")
        }
        if (Regex("^(baja|bajar|menos) (el )?(volumen|sonido)( un poco| mucho)?$|^m[aá]s bajo$", RegexOption.IGNORE_CASE).matches(t)) {
            val step = if (t.contains("mucho")) max / 3 else max / 8
            return set(audio.getStreamVolume(stream) - step.coerceAtLeast(1), "Volumen bajado")
        }
        if (Regex("^(silencio|silencia (el )?(m[oó]vil|tel[eé]fono)|pon(lo|te)? en silencio|modo silencio|modo vibraci[oó]n|pon (el m[oó]vil |el tel[eé]fono )?en vibraci[oó]n)$", RegexOption.IGNORE_CASE).matches(t)) {
            return try {
                audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                Control.log("Modo vibración")
                "Puesto en vibración."
            } catch (e: SecurityException) {
                "Android no me deja cambiar eso sin permiso de No molestar."
            }
        }
        if (Regex("^(quita el silencio|quita (el modo )?vibraci[oó]n|modo sonido|activa el sonido|pon el sonido)$", RegexOption.IGNORE_CASE).matches(t)) {
            return try {
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                Control.log("Modo sonido")
                "Sonido activado."
            } catch (e: SecurityException) {
                "Android no me deja cambiar eso sin permiso de No molestar."
            }
        }
        return null
    }

    private fun alarm(c: Context, t: String): String? {
        val m = Regex(
            "^(?:pon(?:me)? (?:una )?alarma|despi[eé]rtame|ll[aá]mame|av[ií]same|alarma)(?: (?:ma[nñ]ana|hoy))? (?:a las?|para las?) " +
                "(\\w+)(?:[:.h ](\\d{2}))?(?: y (media|cuarto|\\w+))?(?: menos (cuarto|\\w+))?" +
                "(?: (?:de la|por la) (ma[nñ]ana|tarde|noche|madrugada))?(?: (?:ma[nñ]ana|hoy))?$",
            RegexOption.IGNORE_CASE,
        ).find(t) ?: return null
        var hour = number(m.groupValues[1]) ?: return null
        var minute = m.groupValues[2].toIntOrNull() ?: 0
        when (val plus = m.groupValues[3].lowercase()) {
            "" -> {}
            "media" -> minute = 30
            "cuarto" -> minute = 15
            else -> minute = number(plus) ?: minute
        }
        when (val less = m.groupValues[4].lowercase()) {
            "" -> {}
            else -> {
                val sub = if (less == "cuarto") 15 else number(less) ?: 0
                hour -= 1
                minute = 60 - sub
            }
        }
        when (m.groupValues[5].lowercase()) {
            "tarde", "noche" -> if (hour in 1..11) hour += 12
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Ultra Yo")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val time = "%d:%02d".format(hour, minute)
        return start(c, intent, "No encuentro la app del reloj.")
            ?: "Alarma puesta a las $time.".also { Control.log("Alarma a las $time") }
    }

    private fun timer(c: Context, t: String): String? {
        val m = Regex(
            "^(?:pon(?:me)? )?(?:un )?(?:temporizador|cron[oó]metro|cuenta atr[aá]s|av[ií]same en|recu[eé]rdame en)(?: de)? (\\w+(?: y medio)?|media) (horas?|minutos?|segundos?|hora)?(?: y medio)?$",
            RegexOption.IGNORE_CASE,
        ).find(t) ?: return null
        val amount = m.groupValues[1].lowercase()
        val unit = m.groupValues[2].lowercase()
        val seconds = when {
            amount == "media" -> 1800
            else -> {
                val n = number(amount.removeSuffix(" y medio")) ?: return null
                val half = amount.endsWith("y medio") || t.endsWith("y medio")
                val base = when {
                    unit.startsWith("hora") -> 3600
                    unit.startsWith("segundo") -> 1
                    else -> 60
                }
                n * base + if (half) base / 2 else 0
            }
        }
        if (seconds <= 0) return null
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Ultra Yo")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val said = when {
            seconds % 3600 == 0 -> "${seconds / 3600} h"
            seconds % 60 == 0 -> "${seconds / 60} min"
            else -> "$seconds s"
        }
        return start(c, intent, "No encuentro la app del reloj.")
            ?: "Temporizador de $said en marcha.".also { Control.log("Temporizador de $said") }
    }

    // --- Llamadas y mensajes (siempre con "sí") ---

    private fun call(c: Context, t: String): String? {
        val m = Regex("^(?:llama|llamar|marca|haz una llamada)(?: a| al)? (.+?)(?: por (whatsapp|tel[eé]fono))?$", RegexOption.IGNORE_CASE).find(t) ?: return null
        val who = m.groupValues[1]
        if (who.startsWith("me ")) return null
        val viaWhatsApp = m.groupValues[2].lowercase() == "whatsapp"
        val contact = contact(c, who) ?: return missingContact(c, who)
        if (viaWhatsApp) {
            return Control.ask("Voy a llamar a ${contact.name} por WhatsApp") {
                openWhatsApp(c, contact.number, null)
                UltraAccessibility.instance?.clickWhenShown(WHATSAPP, listOf("voip_call", "menuitem_voice_call"), listOf("llamada de voz", "voice call", "llamar"))
                "Abriendo WhatsApp para llamar a ${contact.name}."
            }
        }
        return Control.ask("Voy a llamar a ${contact.name} (${contact.number})") {
            val canCall = granted(c, Manifest.permission.CALL_PHONE)
            val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(contact.number)}"))
            start(c, intent, "No puedo llamar desde aquí.")
                ?: if (canCall) "Llamando a ${contact.name}." else "Te dejo el número marcado; pulsa llamar."
        }
    }

    private fun message(c: Context, t: String): String? {
        val m = Regex(
            "^(?:m[aá]nda(?:le)?|env[ií]a(?:le)?|escr[ií]be(?:le)?|d[ií]le)(?: un)?(?: (whatsapp|wasap|guasap|mensaje|sms))?(?: de whatsapp)? a (.+?)" +
                "(?: por (whatsapp|wasap|sms|mensaje))?(?:,|:| que dice| diciendo| que| con el texto)\\s+(.+)$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(t) ?: return null
        val kind = (m.groupValues[1] + m.groupValues[3]).lowercase()
        val who = m.groupValues[2].trim()
        val body = m.groupValues[4].trim().replaceFirstChar { it.uppercase() }
        val contact = contact(c, who) ?: return missingContact(c, who)
        val hasWhatsApp = installed(c, WHATSAPP)
        val sms = kind.contains("sms") || (kind.contains("mensaje") && !hasWhatsApp)
        if (sms) {
            return Control.ask("Voy a mandar un SMS a ${contact.name}: «$body»") {
                if (!granted(c, Manifest.permission.SEND_SMS)) {
                    Control.permissionsNeeded = listOf(Manifest.permission.SEND_SMS)
                    start(c, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${contact.number}")).putExtra("sms_body", body), "No hay app de mensajes.")
                        ?: "Te lo dejo escrito; pulsa enviar."
                } else {
                    val manager = c.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
                    manager.sendMultipartTextMessage(contact.number, null, manager.divideMessage(body), null, null)
                    "Enviado a ${contact.name}."
                }
            }
        }
        if (!hasWhatsApp) return "No tienes WhatsApp instalado. Dime \"mándale un SMS a ${contact.name} que…\"."
        if (locked(c)) return "Desbloquea el teléfono y se lo mando."
        return Control.ask("Voy a escribir a ${contact.name} por WhatsApp: «$body»") {
            openWhatsApp(c, contact.number, body)
            val robot = UltraAccessibility.instance
            if (robot == null) {
                "Te dejo el mensaje escrito en WhatsApp; pulsa enviar. Si activas mi control del teléfono, lo envío yo."
            } else {
                robot.clickWhenShown(WHATSAPP, listOf("send"), listOf("enviar", "send")) { ok ->
                    Assistant.notify(if (ok) "Enviado a ${contact.name}." else "No encontré el botón de enviar; púlsalo tú.")
                }
                "Enviando…"
            }
        }
    }

    private fun openWhatsApp(c: Context, number: String, text: String?) {
        val digits = number.filter { it.isDigit() }
        val url = "https://api.whatsapp.com/send?phone=$digits" + if (text != null) "&text=${Uri.encode(text)}" else ""
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(WHATSAPP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(intent)
    }

    // --- Mapas, música y búsquedas ---

    private fun navigate(c: Context, t: String): String? {
        Regex("^(?:ll[eé]vame|navega|c[oó]mo (?:llego|voy|se va)|ruta|indicaciones)(?: hasta| hacia| para ir)? (?:a |al |a la |hasta )?(.+)$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val place = m.groupValues[1]
            if (locked(c)) return "Desbloquea el teléfono y te llevo."
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(place)}"))
            if (start(c, intent, null) == null) {
                Control.log("Navegación a $place")
                return "Vamos a $place."
            }
            return start(c, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(place)}")), "No tienes app de mapas.")
                ?: "Te enseño $place en el mapa."
        }
        Regex("^(?:busca|ens[eé][nñ]ame|d[oó]nde (?:est[aá]|hay)) (.+?) en (?:el |google )?mapas?$|^d[oó]nde hay (?:un |una )?(.+?) cerca$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val place = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (locked(c)) return "Desbloquea el teléfono y te lo enseño."
            return start(c, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(place)}")), "No tienes app de mapas.")
                ?: "Te enseño $place en el mapa."
        }
        return null
    }

    private fun media(c: Context, t: String): String? {
        val m = Regex("^(?:pon(?:me)?|reproduce|escuchar|quiero escuchar) (?:la canci[oó]n |m[uú]sica de |a |el disco |la playlist )?(.+?)(?: en (spotify|youtube music|youtube))?$", RegexOption.IGNORE_CASE).find(t) ?: return null
        val what = m.groupValues[1]
        val where = m.groupValues[2].lowercase()
        // "pon una alarma", "pon el volumen"… no son música.
        if (Regex("^(una |un |el |la )?(alarma|temporizador|volumen|linterna|silencio|vibraci|modo|sonido)", RegexOption.IGNORE_CASE).containsMatchIn(what)) return null
        if (where.isEmpty() && !t.lowercase().startsWith("reproduce") && !t.lowercase().contains("música") && !t.lowercase().contains("canci")) return null
        if (locked(c)) return "Desbloquea el teléfono y te la pongo."
        if (where == "youtube") return youtube(c, what)
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, what)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        val pkg = when {
            where == "spotify" || (where.isEmpty() && installed(c, "com.spotify.music")) -> "com.spotify.music"
            where == "youtube music" -> "com.google.android.apps.youtube.music"
            else -> null
        }
        if (pkg != null && installed(c, pkg)) intent.setPackage(pkg)
        return start(c, intent, null)?.let { youtube(c, what) } ?: "Poniendo $what."
    }

    private fun youtube(c: Context, what: String): String {
        val intent = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", what)
        return start(c, intent, null)?.let {
            start(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(what)}")), "No tienes YouTube.")
        } ?: "Buscando $what en YouTube."
    }

    private fun search(c: Context, t: String): String? {
        val m = Regex("^(?:busca|b[uú]scame|googlea) (.+?) en (google|internet|la web|youtube)$", RegexOption.IGNORE_CASE).find(t) ?: return null
        val what = m.groupValues[1]
        if (locked(c)) return "Desbloquea el teléfono y lo busco."
        if (m.groupValues[2].lowercase() == "youtube") return youtube(c, what)
        return start(c, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, what), null)
            ?.let { start(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(what)}")), "No tienes navegador.") }
            ?: "Buscando $what."
    }

    /** Wifi, Bluetooth y datos: Android no deja cambiarlos a las apps, así que abre su panel rápido. */
    private fun systemPanel(c: Context, t: String): String? {
        val m = Regex("^(?:enciende|activa|pon|apaga|desactiva|quita|abre)? ?(?:el |la |los )?(wifi|wi-fi|bluetooth|datos( m[oó]viles)?|modo avi[oó]n|ajustes|configuraci[oó]n)$", RegexOption.IGNORE_CASE).find(t) ?: return null
        if (locked(c)) return "Desbloquea el teléfono y te lo abro."
        val what = m.groupValues[1].lowercase()
        val intent = when {
            what.startsWith("wi") -> Intent(Settings.Panel.ACTION_WIFI)
            what == "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            what.startsWith("datos") -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            what.startsWith("modo") -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        return start(c, intent, "No puedo abrir eso.") ?: when {
            what.startsWith("ajustes") || what.startsWith("config") -> "Abriendo ajustes."
            else -> "Te abro el panel: Android solo deja que lo cambies tú con un toque."
        }
    }

    // --- Botones del sistema y la pantalla (control del teléfono) ---

    private fun systemButton(t: String): String? {
        val action = when {
            Regex("^(atr[aá]s|vuelve( atr[aá]s)?|ve atr[aá]s|retrocede)$", RegexOption.IGNORE_CASE).matches(t) -> "atras"
            Regex("^((ve |vete |ir )?(al |a la )?(inicio|pantalla de inicio|escritorio)|home)$", RegexOption.IGNORE_CASE).matches(t) -> "inicio"
            Regex("^((apps |aplicaciones )?recientes|multitarea)$", RegexOption.IGNORE_CASE).matches(t) -> "recientes"
            Regex("^((abre |baja |ens[eé][nñ]ame )?(las )?notificaciones)$", RegexOption.IGNORE_CASE).matches(t) -> "notificaciones"
            Regex("^((abre )?(los )?ajustes r[aá]pidos)$", RegexOption.IGNORE_CASE).matches(t) -> "ajustesrapidos"
            Regex("^(haz (una )?captura( de pantalla)?|captura( la)?( pantalla)?|pantallazo)$", RegexOption.IGNORE_CASE).matches(t) -> "captura"
            Regex("^(bloquea (el (tel[eé]fono|m[oó]vil)|la pantalla)|apaga la pantalla)$", RegexOption.IGNORE_CASE).matches(t) -> "bloquear"
            else -> return null
        }
        val robot = UltraAccessibility.instance ?: return NEEDS_CONTROL
        return robot.systemButton(action)
    }

    private fun screen(t: String): String? {
        Regex("^(?:pulsa|toca|dale a|presiona|haz clic en|clic en|selecciona)(?: el bot[oó]n| en)? (.+)$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val robot = UltraAccessibility.instance ?: return NEEDS_CONTROL
            return robot.tapSafely(m.groupValues[1].trim().trim('"', '«', '»'))
        }
        Regex("^(?:escribe|teclea|pon)(?: aqu[ií])?(?: el texto)?:? [\"«]?(.+?)[\"»]?$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            // "escribe a Juan…" es un mensaje, no teclear.
            if (Regex("^(a |al |una |un )", RegexOption.IGNORE_CASE).containsMatchIn(m.groupValues[1])) return null
            if (!t.lowercase().startsWith("escribe") && !t.lowercase().startsWith("teclea")) return null
            val robot = UltraAccessibility.instance ?: return NEEDS_CONTROL
            return robot.type(m.groupValues[1])
        }
        Regex("^(?:baja|desliza|desplaza|scroll|sube)(?: la pantalla)?(?: hacia)? ?(abajo|arriba|m[aá]s)?$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val up = t.lowercase().startsWith("sube") || m.groupValues[1].lowercase() == "arriba"
            val robot = UltraAccessibility.instance ?: return NEEDS_CONTROL
            return robot.scroll(forward = !up)
        }
        if (Regex("^(lee(me)? (la |lo que hay en (la )?)?pantalla|qu[eé] (hay|pone|sale) en (la )?pantalla|qu[eé] estoy viendo)$", RegexOption.IGNORE_CASE).matches(t)) {
            val robot = UltraAccessibility.instance ?: return NEEDS_CONTROL
            return robot.readScreen(maxChars = 600)
        }
        return null
    }

    private fun openApp(c: Context, t: String): String? {
        val m = Regex("^(?:abre|abrir|ábreme|abreme|lanza|inicia|arranca|entra en|ve a|vete a|ir a)(?: la app(?:licaci[oó]n)?(?: de)?)? (?:el |la |los |las |mi |mis )?(.+)$", RegexOption.IGNORE_CASE).find(t) ?: return null
        val name = m.groupValues[1]
        if (name.equals("editor", true) || name.startsWith("editor de")) return null
        val app = findApp(c, name) ?: return null
        Referencias.ultimaApp = app.first
        if (locked(c)) return "Desbloquea el teléfono y abro ${app.first}."
        val intent = c.packageManager.getLaunchIntentForPackage(app.second) ?: return "No se puede abrir ${app.first}."
        return start(c, intent, "No se puede abrir ${app.first}.") ?: "Abriendo ${app.first}.".also { Control.log("Abrió ${app.first}") }
    }

    // --- Ayudas ---

    private const val WHATSAPP = "com.whatsapp"
    private const val NEEDS_CONTROL =
        "Para eso necesito el control del teléfono. Actívalo en Ajustes de Ultra Yo, en Control del teléfono."

    private val aliases = mapOf(
        "wasap" to "whatsapp", "guasap" to "whatsapp", "whats" to "whatsapp",
        "camara" to "camara", "ajustes" to "ajustes", "configuracion" to "ajustes", "settings" to "ajustes",
        "mapas" to "maps", "google maps" to "maps", "navegador" to "chrome", "internet" to "chrome",
        "tienda" to "play store", "play" to "play store", "galeria" to "galeria", "fotos" to "galeria",
        "correo" to "gmail", "email" to "gmail", "mensajes" to "mensajes", "telefono" to "telefono",
        "calculadora" to "calculadora", "reloj" to "reloj", "calendario" to "calendario", "insta" to "instagram",
    )

    /** Busca una app instalada por su nombre, aunque se diga sin tildes o con otro nombre. */
    fun findApp(c: Context, said: String): Pair<String, String>? {
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val wanted = norm(said).let { aliases[it] ?: it }
        if (wanted.length < 2) return null
        return apps.firstOrNull { norm(it.first) == wanted }
            ?: apps.firstOrNull { norm(it.first).startsWith(wanted) }
            ?: apps.firstOrNull { norm(it.first).contains(wanted) }
            ?: apps.firstOrNull { wanted.contains(norm(it.first)) && norm(it.first).length >= 4 }
            ?: apps.firstOrNull { it.second.lowercase().contains(wanted.replace(" ", "")) }
    }

    data class Contact(val name: String, val number: String)

    /** Busca un contacto por su nombre, o usa el número si lo ha dicho. */
    fun contact(c: Context, said: String): Contact? {
        val digits = said.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 6) return Contact(said, international(c, digits))
        if (!granted(c, Manifest.permission.READ_CONTACTS)) return null
        val wanted = norm(said.removePrefix("mi ").removePrefix("a "))
        val found = mutableListOf<Contact>()
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                found += Contact(name, number)
            }
        }
        val best = found.firstOrNull { norm(it.name) == wanted }
            ?: found.firstOrNull { norm(it.name).split(" ").firstOrNull() == wanted }
            ?: found.firstOrNull { norm(it.name).startsWith(wanted) }
            ?: found.firstOrNull { norm(it.name).contains(wanted) }
            ?: soundsLike(found, wanted)
            ?: return null
        Referencias.ultimoContacto = best.name
        return best.copy(number = international(c, best.number))
    }

    /**
     * Si no está escrito igual, busca el contacto que suena parecido ("Javi" / "Xavi", "Yésica" / "Jessica",
     * o un nombre mal oído por el micrófono del coche). Solo si hay uno claro: con dudas, mejor no llamar a otro.
     */
    private fun soundsLike(found: List<Contact>, wanted: String): Contact? {
        val key = Spoken.key(wanted)
        if (key.length < 3) return null
        val heard = Spoken.words(wanted)
        val matches = found.filter { contact ->
            val first = Spoken.key(contact.name.split(" ").first())
            Spoken.key(contact.name) == key || first == key ||
                Spoken.close(Spoken.key(contact.name), key) || Spoken.close(first, key) || Spoken.find(heard, contact.name) > 0
        }.distinctBy { norm(it.name) }
        return matches.singleOrNull()
    }

    private fun missingContact(c: Context, who: String): String {
        if (!granted(c, Manifest.permission.READ_CONTACTS)) {
            Control.permissionsNeeded = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)
            return "Necesito ver tus contactos para encontrar a $who. Abre Ultra Yo y acepta el permiso."
        }
        return "No encuentro a $who en tus contactos."
    }

    /** Pone el prefijo del país (el de la SIM, o España) para que WhatsApp lo entienda. */
    private fun international(c: Context, number: String): String {
        val country = c.getSystemService(TelephonyManager::class.java)?.simCountryIso?.uppercase()?.ifEmpty { null } ?: "ES"
        return PhoneNumberUtils.formatNumberToE164(number, country) ?: number.filter { it.isDigit() || it == '+' }
    }

    private fun installed(c: Context, pkg: String) =
        runCatching { c.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun granted(c: Context, permission: String) =
        ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED

    private fun locked(c: Context) = c.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Abre [intent]. Devuelve null si fue bien, o [failure] (o "") si no hay app para ello. */
    private fun start(c: Context, intent: Intent, failure: String?): String? = try {
        c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: ActivityNotFoundException) {
        failure ?: ""
    }

    fun norm(s: String): String = Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9ñ ]"), "").replace(Regex("\\s+"), " ").trim()

    private val numbers = mapOf(
        "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6,
        "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13,
        "catorce" to 14, "quince" to 15, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18,
        "diecinueve" to 19, "veinte" to 20, "veinticinco" to 25, "treinta" to 30, "cuarenta" to 40,
        "cuarenta y cinco" to 45, "cincuenta" to 50, "sesenta" to 60, "noventa" to 90, "cien" to 100,
    )

    /** "7", "siete" o "veinte" → número. */
    fun number(word: String): Int? = word.trim().toIntOrNull() ?: numbers[norm(word)]
}
