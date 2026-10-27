package com.ultrayo.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

private const val MAX_RESULTS = 40
private const val MAX_TEXT = 6000
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "csv", "json", "xml", "html", "htm", "log", "kt", "java", "py", "js", "ts",
    "c", "cpp", "h", "yml", "yaml", "ini", "cfg", "properties", "gradle", "kts", "sh", "bat", "sql",
)

/**
 * Lo que la IA puede hacer en el teléfono. Todo es de solo lectura: ver y abrir apps,
 * y buscar y leer archivos en las carpetas que Maurizio le ha permitido.
 */
class PhoneTools(context: Context) : ToolSet {

    private val context = context.applicationContext

    @Tool(description = "Lista los nombres de las apps instaladas en el teléfono.")
    fun listarApps(): List<String> {
        if (locked()) return listOf(LOCKED)
        return launchableApps().keys.sorted()
    }

    @Tool(description = "Abre una app del teléfono por su nombre, por ejemplo WhatsApp o Cámara.")
    fun abrirApp(
        @ToolParam(description = "Nombre de la app que hay que abrir") nombre: String,
    ): String {
        if (locked()) return LOCKED
        val apps = launchableApps()
        val wanted = nombre.trim().lowercase()
        val match = apps.entries.firstOrNull { it.key.lowercase() == wanted }
            ?: apps.entries.firstOrNull { it.key.lowercase().contains(wanted) }
            ?: return "No encontré ninguna app llamada $nombre."
        val intent = context.packageManager.getLaunchIntentForPackage(match.value)
            ?: return "No se puede abrir ${match.key}."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Abriendo ${match.key}."
    }

    @Tool(description = "Busca archivos por nombre en las carpetas permitidas. Devuelve sus rutas.")
    fun buscarArchivos(
        @ToolParam(description = "Parte del nombre del archivo; vacío para listar todo") texto: String,
    ): List<String> {
        if (locked()) return listOf(LOCKED)
        val roots = Folders.list(context)
        if (roots.isEmpty()) return listOf("No tengo acceso a ninguna carpeta. Maurizio puede dármelo en Memoria.")
        val wanted = texto.trim().lowercase()
        val found = mutableListOf<String>()
        for (uri in roots) {
            val root = DocumentFile.fromTreeUri(context, uri) ?: continue
            walk(root, root.name ?: "carpeta", depth = 0) { path, file ->
                if (wanted.isEmpty() || (file.name ?: "").lowercase().contains(wanted)) found += path
                found.size < MAX_RESULTS
            }
            if (found.size >= MAX_RESULTS) break
        }
        return found.ifEmpty { listOf("No encontré archivos con ese nombre.") }
    }

    @Tool(description = "Lee el contenido de un archivo de texto usando la ruta que devolvió buscarArchivos.")
    fun leerArchivo(
        @ToolParam(description = "Ruta del archivo, tal como la devolvió buscarArchivos") ruta: String,
    ): String {
        if (locked()) return LOCKED
        for (uri in Folders.list(context)) {
            val root = DocumentFile.fromTreeUri(context, uri) ?: continue
            var target: DocumentFile? = null
            walk(root, root.name ?: "carpeta", depth = 0) { path, file ->
                if (path == ruta.trim()) target = file
                target == null
            }
            val file = target ?: continue
            val extension = (file.name ?: "").substringAfterLast('.', "").lowercase()
            if (extension !in TEXT_EXTENSIONS) return "Solo sé leer archivos de texto, no .$extension."
            val text = context.contentResolver.openInputStream(file.uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: return "No pude abrir el archivo."
            return if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "\n[…archivo recortado…]" else text
        }
        return "No encontré ese archivo."
    }

    private fun walk(dir: DocumentFile, path: String, depth: Int, visit: (String, DocumentFile) -> Boolean): Boolean {
        if (depth > 6) return true
        for (child in dir.listFiles()) {
            val childPath = "$path/${child.name}"
            if (child.isDirectory) {
                if (!walk(child, childPath, depth + 1, visit)) return false
            } else if (!visit(childPath, child)) {
                return false
            }
        }
        return true
    }

    private fun launchableApps(): Map<String, String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .associate { it.loadLabel(pm).toString() to it.activityInfo.packageName }
    }

    private fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private companion object {
        const val LOCKED = "Para eso hay que desbloquear el teléfono."
    }
}

/** Carpetas a las que Maurizio ha dado permiso de lectura. */
object Folders {
    fun list(context: Context): List<Uri> =
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }

    fun add(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun remove(context: Context, uri: Uri) {
        context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun name(context: Context, uri: Uri): String =
        DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: uri.toString()
}
