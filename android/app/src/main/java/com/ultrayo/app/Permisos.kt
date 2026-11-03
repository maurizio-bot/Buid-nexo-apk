package com.ultrayo.app

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Asistente de permisos: todo lo que Ultra puede necesitar, en una lista, para darlo de una vez
 * y que Android no vuelva a preguntar en mitad de una orden.
 *
 * Android no deja conceder permisos al instalar una app (desde Android 6 se piden al usarla).
 * Lo más parecido es esto: pedirlos todos juntos la primera vez que se abre. Hay dos clases:
 * - [Paso.Dialogo]: Android enseña su ventana de "Permitir" y ya está.
 * - [Paso.Ajuste]: Android obliga a activarlos en una pantalla de Ajustes; se abre directamente.
 */
object Permisos {

    sealed class Paso(val id: String, val titulo: String, val para: String, val restringido: Boolean) {
        /** Se concede con la ventana de Android. */
        class Dialogo(id: String, titulo: String, para: String, val permisos: Array<String>, restringido: Boolean = false) :
            Paso(id, titulo, para, restringido)

        /** Se activa en una pantalla de Ajustes de Android. */
        class Ajuste(id: String, titulo: String, para: String, val abrir: (Context) -> Intent, restringido: Boolean = false) :
            Paso(id, titulo, para, restringido)
    }

    fun pasos(): List<Paso> = buildList {
        add(Paso.Dialogo("mic", "Micrófono", "Para hablarle y para la palabra \"Ultra\".", arrayOf(Manifest.permission.RECORD_AUDIO)))
        if (Build.VERSION.SDK_INT >= 33) {
            add(Paso.Dialogo("avisos", "Avisos", "Para el aviso fijo mientras escucha \"Ultra\" y para avisarte cuando termina algo.", arrayOf(Manifest.permission.POST_NOTIFICATIONS)))
        }
        add(
            Paso.Dialogo(
                "telefono", "Contactos, llamar y enviar SMS", "Para \"llama a mamá\" o \"mándale un SMS a Juan\" (siempre te pregunta antes).",
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS),
            )
        )
        add(Paso.Dialogo("agenda", "Agenda", "Para \"¿qué tengo mañana?\".", arrayOf(Manifest.permission.READ_CALENDAR)))
        add(
            Paso.Dialogo(
                "sms", "Leer SMS y llamadas", "Para \"¿quién me ha llamado?\" o \"¿tengo SMS sin leer?\".",
                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG), restringido = true,
            )
        )
        add(Paso.Dialogo("galeria", "Fotos, vídeos y audio", "Para buscar en tu galería.", DeviceFiles.mediaPermissions()))
        if (Build.VERSION.SDK_INT >= 30) {
            add(
                Paso.Ajuste("archivos", "Todos los archivos", "Para buscar, leer y ordenar cualquier archivo (lo que no sea de su carpeta te lo pregunta antes).") { c ->
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${c.packageName}"))
                }
            )
        }
        add(
            Paso.Ajuste("notificaciones", "Leer notificaciones", "Para \"léeme las notificaciones\". Solo las guarda en memoria, no en disco.", { c ->
                UltraNotifications.settingsIntent(c)
            }, restringido = true)
        )
        add(
            Paso.Ajuste("control", "Control del teléfono (accesibilidad)", "Para pulsar y escribir en otras apps cuando se lo pides. En la lista, entra en Ultra Yo y actívalo.", {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }, restringido = true)
        )
        add(
            Paso.Ajuste("bateria", "Sin límite de batería", "Para que Android no la duerma y oiga \"Ultra\" con la pantalla apagada.") { c ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))
            }
        )
        if (Build.VERSION.SDK_INT >= 33) {
            add(
                Paso.Ajuste(
                    "restringidos", "Ajustes restringidos",
                    "Como el APK no viene de Play Store, Android 13+ bloquea accesibilidad, notificaciones y a veces SMS/llamadas. " +
                        "Primero intenta activar uno de esos (saldrá gris); luego aquí: ⋮ arriba a la derecha > Permitir ajustes restringidos.",
                ) { c -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")) }
            )
        }
    }

    /** true concedido, false falta, null no se puede saber. */
    fun estado(c: Context, paso: Paso): Boolean? = runCatching {
        when (paso) {
            is Paso.Dialogo -> when (paso.id) {
                // En la galería basta con alguno (Android 14 deja dar solo algunas fotos).
                "galeria" -> DeviceFiles.hasMedia(c)
                else -> paso.permisos.all { granted(c, it) }
            }
            is Paso.Ajuste -> when (paso.id) {
                "archivos" -> DeviceFiles.allFiles()
                "notificaciones" -> UltraNotifications.enabled(c)
                "control" -> UltraAccessibility.instance != null || accessibilityOn(c)
                "bateria" -> c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
                "restringidos" -> restrictedAllowed(c)
                else -> null
            }
        }
    }.getOrNull()

    fun faltan(c: Context): Int = pasos().count { estado(c, it) == false }

    /** Todos los permisos de ventana que faltan, para pedirlos de una vez. */
    fun dialogosQueFaltan(c: Context): Array<String> =
        pasos().filterIsInstance<Paso.Dialogo>().flatMap { p -> p.permisos.filter { !granted(c, it) } }.distinct().toTypedArray()

    private fun granted(c: Context, permission: String) =
        ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED

    private fun accessibilityOn(c: Context): Boolean {
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.startsWith(c.packageName + "/") }
    }

    /** Android no lo dice de forma oficial; se mira el permiso interno y, si no se puede, se deja en "no se sabe". */
    private fun restrictedAllowed(c: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 33) return true
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return null
        val mode = runCatching {
            ops.unsafeCheckOpNoThrow("android:access_restricted_settings", c.applicationInfo.uid, c.packageName)
        }.getOrNull() ?: return null
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_ERRORED, AppOpsManager.MODE_IGNORED -> false
            else -> null
        }
    }

    // --- La primera vez ---

    private const val VISTO = "asistente_permisos_visto"

    fun visto(c: Context) = c.getSharedPreferences("ultrayo", Context.MODE_PRIVATE).getBoolean(VISTO, false)

    fun marcarVisto(c: Context) = c.getSharedPreferences("ultrayo", Context.MODE_PRIVATE).edit().putBoolean(VISTO, true).apply()

    /** Para saber si una ventana ya se pidió (si Android no la vuelve a enseñar, hay que ir a Ajustes). */
    fun pedido(c: Context, paso: Paso) = c.getSharedPreferences("ultrayo", Context.MODE_PRIVATE).getBoolean("pedido_${paso.id}", false)

    fun marcarPedido(c: Context, paso: Paso) =
        c.getSharedPreferences("ultrayo", Context.MODE_PRIVATE).edit().putBoolean("pedido_${paso.id}", true).apply()
}

/**
 * La pantalla del asistente. [tick] cambia al volver de Ajustes o de una ventana de permisos,
 * para volver a mirar el estado. [pedir] lanza la ventana de Android; [abrir] una pantalla de Ajustes.
 */
@Composable
fun PermisosScreen(
    context: Context,
    tick: Int,
    pedir: (Permisos.Paso.Dialogo) -> Unit,
    pedirTodos: () -> Unit,
    abrir: (Intent) -> Unit,
    onListo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val estados = androidx.compose.runtime.remember(tick) { Permisos.pasos().map { it to Permisos.estado(context, it) } }
    val faltanVentanas = androidx.compose.runtime.remember(tick) { Permisos.dialogosQueFaltan(context).isNotEmpty() }
    val faltan = estados.count { it.second == false }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(
                    "Permisos de Ultra",
                    "Dáselos ahora todos y Android no te interrumpirá después. Android no deja darlos al instalar: " +
                        "desde Android 6 se piden al usar la app, así que esto es lo más parecido. Los de ventana se aceptan " +
                        "con un toque; los de Ajustes Android obliga a activarlos en su pantalla (te llevo directo). " +
                        "Ultra solo los usa cuando tú se lo pides.",
                )
                Text(
                    if (faltan == 0) "✓ Tiene todo lo que necesita." else "Faltan $faltan.",
                    color = if (faltan == 0) UltraColors.Accent else UltraColors.Text,
                    fontWeight = FontWeight.SemiBold,
                )
                if (faltanVentanas) {
                    GlowButton("Pedir todos los de ventana de una vez", onClick = pedirTodos, Modifier.fillMaxWidth())
                }
                Text(
                    "Bluetooth (coche y cascos) no necesita permiso: Ultra usa el audio que Android ya le da.",
                    color = UltraColors.Muted,
                    fontSize = 12.sp,
                )
            }
        }
        items(estados, key = { it.first.id }) { (paso, ok) ->
            Row(
                Modifier.fillMaxWidth().glass(16.dp).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (ok) {
                        true -> "✓"
                        false -> "○"
                        null -> "?"
                    },
                    color = if (ok == true) UltraColors.Accent else UltraColors.Muted,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(paso.titulo, color = UltraColors.Text, fontWeight = FontWeight.SemiBold)
                    Text(paso.para, color = UltraColors.Muted, fontSize = 12.sp)
                    val tipo = when {
                        paso is Permisos.Paso.Ajuste -> "Se activa en Ajustes de Android"
                        else -> "Ventana de Android"
                    } + if (paso.restringido) " · puede salir gris al instalar el APK a mano (mira «Ajustes restringidos»)" else ""
                    Text(tipo, color = UltraColors.Muted, fontSize = 11.sp)
                }
                if (ok != true) {
                    TextButton(onClick = {
                        when (paso) {
                            is Permisos.Paso.Dialogo -> pedir(paso)
                            is Permisos.Paso.Ajuste -> abrir(paso.abrir(context))
                        }
                    }) { Text(if (paso is Permisos.Paso.Ajuste) "Abrir" else "Permitir") }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlowButton(if (faltan == 0) "Listo" else "Listo por ahora", onClick = onListo, Modifier.fillMaxWidth())
                Text(
                    "Puedes volver aquí cuando quieras: Ajustes (⚙) > Permisos de Ultra.",
                    color = UltraColors.Muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
