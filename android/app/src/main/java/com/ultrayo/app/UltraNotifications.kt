package com.ultrayo.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Para que Ultra Yo pueda leerte las notificaciones cuando se lo pides ("¿qué me ha llegado?").
 * Se activa en Ajustes > Notificaciones > Acceso a notificaciones. Solo lee: no las borra ni
 * contesta, y lo que ve se queda en memoria (no se guarda en ningún archivo).
 */
class UltraNotifications : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { note(this, sbn)?.let(::remember) }
    }

    data class Note(val key: String, val app: String, val title: String, val text: String, val time: Long)

    companion object {
        @Volatile
        var instance: UltraNotifications? = null
            private set

        private const val KEEP = 80
        private val recent = ArrayDeque<Note>()
        private val time = SimpleDateFormat("HH:mm", Locale.forLanguageTag("es-ES"))
        private val labels = HashMap<String, String>()

        fun enabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun settingsIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, UltraNotifications::class.java).flattenToString(),
                    )
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }

        private fun remember(note: Note) = synchronized(recent) {
            recent.removeAll { it.key == note.key && it.text == note.text }
            recent.addFirst(note)
            while (recent.size > KEEP) recent.removeLast()
        }

        private fun note(context: Context, sbn: StatusBarNotification): Note? {
            val n = sbn.notification ?: return null
            if (sbn.packageName == context.packageName) return null
            if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
            val extras = n.extras ?: return null
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString().orEmpty().replace('\n', ' ').trim()
            if (title.isEmpty() && text.isEmpty()) return null
            return Note(sbn.key, label(context, sbn.packageName), title, text, sbn.postTime)
        }

        private fun label(context: Context, pkg: String): String = synchronized(labels) {
            labels.getOrPut(pkg) {
                runCatching {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg.substringAfterLast('.'))
            }
        }

        /** Las notificaciones de ahora y las que han llegado desde que se activó, las más nuevas primero. */
        fun list(context: Context, filter: String, limit: Int = 12): String {
            val service = instance
            if (service == null) {
                return if (enabled(context)) {
                    "El acceso a notificaciones está activado pero Android aún no me ha conectado; prueba en unos segundos."
                } else {
                    "No puedo ver tus notificaciones: actívalo en Memoria > Acceso al teléfono > Notificaciones."
                }
            }
            val active = runCatching { service.activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
                .mapNotNull { runCatching { note(context, it) }.getOrNull() }
            val all = (active + synchronized(recent) { recent.toList() })
                .distinctBy { it.key + it.text }
                .sortedByDescending { it.time }
            val words = QuickActions.norm(filter)
            val chosen = if (words.isEmpty()) all else all.filter {
                QuickActions.norm("${it.app} ${it.title} ${it.text}").contains(words)
            }
            if (chosen.isEmpty()) return if (words.isEmpty()) "No tienes notificaciones." else "No hay notificaciones de \"$filter\"."
            val lines = chosen.take(limit).map {
                val text = if (it.text.length > 200) it.text.take(200) + "…" else it.text
                "${time.format(Date(it.time))} ${it.app}: ${it.title}" + if (text.isNotEmpty()) " — $text" else ""
            }
            return PhoneData.clip(lines, chosen.size)
        }
    }
}
