package com.ultrayo.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Lo que la IA puede hacer en el teléfono. Todo es de solo lectura: ver y abrir apps,
 * y buscar, enseñar y leer fotos, vídeos y documentos que Maurizio le ha permitido ver.
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

    @Tool(description = "Busca en el teléfono fotos, vídeos, audios, documentos o cualquier archivo por su nombre o carpeta. Devuelve sus rutas.")
    fun buscarArchivos(
        @ToolParam(description = "Palabras del nombre o de la carpeta, por ejemplo factura, WhatsApp o Camera; vacío para ver los últimos") texto: String,
        @ToolParam(description = "Tipo: foto, vídeo, audio, documento, o vacío para cualquiera") tipo: String,
    ): List<String> {
        if (locked()) return listOf(LOCKED)
        val canMedia = DeviceFiles.hasMedia(context)
        if (!canMedia && Folders.list(context).isEmpty()) {
            return listOf("No tengo permiso para ver archivos. Maurizio puede dármelo en Memoria, en Archivos que puede leer.")
        }
        val found = DeviceFiles.search(context, texto, tipo)
        if (found.isNotEmpty()) return found.map { it.line() }
        return listOf(
            "No encontré nada con ese nombre." +
                if (!canMedia) " Aún no tengo permiso para ver la galería (fotos y vídeos)." else ""
        )
    }

    @Tool(description = "Lee el texto de un documento (txt, pdf, doc, docx, xlsx, pptx, odt, rtf, epub, html…) o el texto escrito en una foto o captura, usando la ruta que devolvió buscarArchivos.")
    fun leerArchivo(
        @ToolParam(description = "Ruta del archivo, tal como la devolvió buscarArchivos") ruta: String,
    ): String {
        if (locked()) return LOCKED
        val file = DeviceFiles.find(context, ruta) ?: return "No encontré ese archivo. Búscalo primero con buscarArchivos."
        if (file.kind == "vídeo") return "Es un vídeo: usa mostrarEnPantalla para enseñárselo a Maurizio o abrirArchivo para verlo."
        return try {
            DeviceFiles.readText(context, file)
        } catch (e: Exception) {
            "No pude leerlo: ${e.message}"
        }
    }

    @Tool(description = "Enseña en el chat una foto (o un fotograma de un vídeo) usando la ruta que devolvió buscarArchivos. Después Maurizio puede preguntarte por ella.")
    fun mostrarEnPantalla(
        @ToolParam(description = "Ruta de la foto o del vídeo, tal como la devolvió buscarArchivos") ruta: String,
    ): String {
        if (locked()) return LOCKED
        val file = DeviceFiles.find(context, ruta) ?: return "No encontré ese archivo."
        if (file.kind != "foto" && file.kind != "vídeo") return "Solo puedo enseñar fotos y vídeos; para documentos usa leerArchivo."
        return try {
            val path = DeviceFiles.copyPreview(context, file)
            Assistant.show(file.name, path)
            "Ya aparece en la pantalla."
        } catch (e: Exception) {
            "No pude abrirla: ${e.message}"
        }
    }

    @Tool(description = "Abre un archivo (vídeo, audio, documento…) con la app que corresponda del teléfono, para verlo o escucharlo.")
    fun abrirArchivo(
        @ToolParam(description = "Ruta del archivo, tal como la devolvió buscarArchivos") ruta: String,
    ): String {
        if (locked()) return LOCKED
        val file = DeviceFiles.find(context, ruta) ?: return "No encontré ese archivo."
        if (file.uri.scheme == "file") return "Está en mi carpeta, ${file.uri.path}. Maurizio puede abrirlo desde su explorador de archivos."
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(file.uri, file.mime.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(intent)
            "Abriendo ${file.name}."
        } catch (e: Exception) {
            "No hay ninguna app para abrir ${file.name}."
        }
    }

    @Tool(description = "Pasa una pregunta a Claude, la IA de Anthropic, abriendo su app. Úsalo solo si Maurizio pide preguntarle a Claude.")
    fun preguntarAClaude(
        @ToolParam(description = "La pregunta completa para Claude, con todo el contexto necesario") pregunta: String,
    ): String {
        if (locked()) return LOCKED
        return ClaudeLink.open(context, pregunta)
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
