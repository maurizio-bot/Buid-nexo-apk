package com.ultrayo.app

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

/**
 * Copiar, mover, renombrar y borrar archivos del teléfono, solo cuando Maurizio lo pide.
 * Copiar dentro de su carpeta (Ultra Yo) se hace al momento; cualquier cambio fuera de ella,
 * y borrar siempre, espera su "sí". Fuera de Ultra Yo hace falta "acceso a todos los archivos".
 */
object FileOps {

    private const val NEED_ALL =
        "Para cambiar archivos fuera de mi carpeta necesito \"Acceso a todos los archivos\": actívalo en Memoria > Acceso al teléfono."

    fun handle(c: Context, action: String, path: String, target: String): String {
        val a = QuickActions.norm(action)
        val file = DeviceFiles.find(c, path) ?: return "No encontré ese archivo. Búscalo primero con buscarArchivos."
        val src = DeviceFiles.realFile(file)
        return when {
            a.startsWith("copi") -> {
                val dir = destination(c, target) ?: return "Dime a qué carpeta: por ejemplo Download, Documents/Facturas o Ultra Yo."
                val inside = inVault(c, dir)
                if (!inside && !DeviceFiles.allFiles()) return NEED_ALL
                val run = { "Copiado en ${show(c, copy(c, file, dir))}." }
                if (inside) run() else Control.ask("Voy a copiar ${file.name} a ${show(c, dir)}", run)
            }
            a.startsWith("mov") -> {
                val dir = destination(c, target) ?: return "Dime a qué carpeta lo muevo."
                if (src == null || !DeviceFiles.allFiles() && !(inVault(c, src) && inVault(c, dir))) return NEED_ALL
                Control.ask("Voy a mover ${file.name} a ${show(c, dir)}") { "Movido a ${show(c, move(c, src, dir, src.name))}." }
            }
            a.startsWith("renombr") || a.startsWith("cambi") -> {
                val name = target.trim().substringAfterLast('/').ifBlank { return "Dime el nombre nuevo." }
                if (src == null || !DeviceFiles.allFiles() && !inVault(c, src)) return NEED_ALL
                val ext = src.extension
                val full = if (ext.isNotEmpty() && !name.contains('.')) "$name.$ext" else name
                val run = { "Ahora se llama ${show(c, move(c, src, src.parentFile ?: src, full))}." }
                if (inVault(c, src)) run() else Control.ask("Voy a cambiar el nombre de ${file.name} a $full", run)
            }
            a.startsWith("borr") || a.startsWith("elimin") -> {
                if (src == null || !DeviceFiles.allFiles() && !inVault(c, src)) return NEED_ALL
                if (protected(c, src)) return "Ese archivo es parte de mi memoria; no lo borro desde aquí."
                Control.ask("Voy a borrar ${file.name} (${show(c, src)}) para siempre") {
                    if (src.delete()) {
                        scan(c, src)
                        "Borrado."
                    } else {
                        "No pude borrarlo."
                    }
                }
            }
            else -> "Puedo copiar, mover, renombrar o borrar archivos."
        }
    }

    /** "Download", "Documents/Facturas", "Ultra Yo", "descargas"… → la carpeta de verdad. Nunca fuera del almacenamiento. */
    private fun destination(c: Context, target: String): File? {
        val t = target.trim().trim('/').removePrefix("Teléfono").trim('/')
        if (t.isEmpty()) return null
        val first = QuickActions.norm(t.substringBefore('/'))
        val rest = if (t.contains('/')) t.substringAfter('/') else ""
        val vault = Vault.dir(c)
        val dir = when (first) {
            "ultra yo", "tu carpeta", "mi carpeta" -> File(vault, rest.ifEmpty { "archivos" })
            "descargas" -> File(DeviceFiles.phoneRoot(), "Download/$rest")
            "documentos" -> File(DeviceFiles.phoneRoot(), "Documents/$rest")
            "fotos", "camara" -> File(DeviceFiles.phoneRoot(), "DCIM/Camera/$rest")
            "imagenes" -> File(DeviceFiles.phoneRoot(), "Pictures/$rest")
            "musica" -> File(DeviceFiles.phoneRoot(), "Music/$rest")
            else -> File(DeviceFiles.phoneRoot(), t)
        }.canonicalFile
        val root = DeviceFiles.phoneRoot().canonicalFile
        return dir.takeIf { it.path == root.path || it.path.startsWith(root.path + "/") || inVault(c, it) }
    }

    private fun inVault(c: Context, f: File): Boolean {
        val vault = Vault.dir(c).canonicalFile.path
        val p = f.canonicalFile.path
        return p == vault || p.startsWith("$vault/")
    }

    /** La memoria, la base de datos y los recuerdos no se borran con esta herramienta. */
    private fun protected(c: Context, f: File): Boolean =
        inVault(c, f) && f.parentFile?.canonicalFile?.path == Vault.dir(c).canonicalFile.path

    private fun copy(c: Context, file: FoundFile, dir: File): File {
        dir.mkdirs()
        val out = free(dir, file.name)
        c.contentResolver.openInputStream(file.uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: error("no pude abrir ${file.name}")
        scan(c, out)
        return out
    }

    private fun move(c: Context, src: File, dir: File, name: String): File {
        dir.mkdirs()
        val out = free(dir, name)
        if (!src.renameTo(out)) {
            src.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            if (!src.delete()) error("lo copié pero no pude quitar el original")
        }
        scan(c, src, out)
        return out
    }

    /** Un nombre que no pise nada: "foto.jpg", "foto (2).jpg"… */
    private fun free(dir: File, name: String): File {
        val base = name.substringBeforeLast('.').ifBlank { "archivo" }
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var out = File(dir, name)
        var n = 2
        while (out.exists()) out = File(dir, "$base ($n)$ext").also { n++ }
        return out
    }

    /** Avisa a la galería de los cambios para que no enseñe archivos que ya no están. */
    private fun scan(c: Context, vararg files: File) {
        runCatching { MediaScannerConnection.scanFile(c, files.map { it.path }.toTypedArray(), null, null) }
        DeviceFiles.forget()
    }

    private fun show(c: Context, f: File): String {
        val vault = Vault.dir(c).canonicalFile.path
        val root = DeviceFiles.phoneRoot().canonicalFile.path
        val p = f.canonicalFile.path
        return when {
            p.startsWith(vault) -> "Ultra Yo" + p.removePrefix(vault)
            p.startsWith(root) -> p.removePrefix(root).trimStart('/').ifEmpty { "la raíz del teléfono" }
            else -> f.name
        }
    }
}
