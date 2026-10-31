package com.ultrayo.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * La carpeta propia de Ultra Yo en la memoria del teléfono (Documentos/Ultra Yo). Se crea la
 * primera vez, su ruta se guarda para siempre y ahí va todo lo que Maurizio le pide guardar:
 * su memoria (memoria.json), la base de datos (datos.db) y los archivos que le pide guardar
 * (archivos/). Es la única carpeta en la que escribe; el resto del teléfono solo lo lee.
 * Al estar en Documentos, Maurizio la ve con cualquier explorador de archivos y no se borra
 * aunque desinstale la app.
 */
object Vault {

    private const val FOLDER = "Ultra Yo"

    @Volatile
    private var root: File? = null

    /** La carpeta, creándola si hace falta. Siempre la misma una vez elegida. */
    fun dir(context: Context): File {
        root?.let { if (it.isDirectory || it.mkdirs()) return it }
        synchronized(this) {
            root?.let { if (it.isDirectory || it.mkdirs()) return it }
            val prefs = context.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)
            val saved = prefs.getString("carpeta", null)?.let(::File)
            val chosen = saved?.takeIf { usable(it) } ?: candidates(context).first { usable(it) }
            if (saved?.path != chosen.path) prefs.edit().putString("carpeta", chosen.path).apply()
            root = chosen
            return chosen
        }
    }

    fun path(context: Context): String = dir(context).path

    /** Donde van los archivos que Maurizio pide guardar. */
    fun files(context: Context): File = File(dir(context), "archivos").apply { mkdirs() }

    /** Primero Documentos (visible para Maurizio); si Android no deja, la carpeta de la app. */
    private fun candidates(context: Context): List<File> = listOfNotNull(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER),
        context.getExternalFilesDir(null)?.let { File(it, FOLDER) },
        File(context.filesDir, FOLDER),
    )

    /** Si se puede crear y escribir de verdad (tras reinstalar, Android a veces no deja tocar la carpeta antigua). */
    private fun usable(dir: File): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val probe = File(dir, ".prueba")
        probe.writeText("ok")
        probe.delete()
        true
    }.getOrDefault(false)

    /** Copia a la carpeta un archivo del teléfono (el original no se toca). Devuelve la ruta de la copia. */
    fun keepCopy(context: Context, uri: Uri, name: String): File {
        val dir = files(context)
        val base = name.substringBeforeLast('.').ifBlank { "archivo" }
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var target = File(dir, name.ifBlank { "archivo" })
        var n = 2
        while (target.exists()) target = File(dir, "$base ($n)$ext").also { n++ }
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: error("no pude abrir el archivo")
        return target
    }

    /** Los archivos de la carpeta (sin la memoria ni la base de datos), los más nuevos primero. */
    fun listFiles(context: Context): List<File> =
        files(context).walkTopDown().maxDepth(10).filter { it.isFile && !it.name.startsWith(".") }
            .sortedByDescending { it.lastModified() }.toList()
}

/**
 * Un almacén de ajustes en un archivo JSON dentro de la carpeta de Ultra Yo, con la misma forma
 * de uso que SharedPreferences. Así la memoria vive en la carpeta y no escondida dentro de la app.
 */
class VaultPrefs private constructor(private val file: File) {

    private val data: JSONObject = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    fun getString(key: String, default: String?): String? = synchronized(this) {
        if (data.has(key)) data.optString(key) else default
    }

    fun getBoolean(key: String, default: Boolean): Boolean = synchronized(this) { data.optBoolean(key, default) }

    fun edit() = Editor()

    inner class Editor {
        private val changes = mutableMapOf<String, Any?>()
        fun putString(key: String, value: String?): Editor {
            changes[key] = value
            return this
        }

        fun putBoolean(key: String, value: Boolean): Editor {
            changes[key] = value
            return this
        }

        fun apply() = synchronized(this@VaultPrefs) {
            changes.forEach { (k, v) -> if (v == null) data.remove(k) else data.put(k, v) }
            // Se escribe entero en un archivo aparte y luego se cambia por el bueno, para no dejarlo a medias.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(data.toString(2))
            if (!tmp.renameTo(file)) {
                file.writeText(data.toString(2))
                tmp.delete()
            }
        }
    }

    companion object {
        @Volatile
        private var instance: VaultPrefs? = null

        /** La memoria de Ultra Yo. La primera vez se trae lo que había guardado dentro de la app. */
        fun memory(context: Context): VaultPrefs = instance ?: synchronized(this) {
            instance ?: run {
                val file = File(Vault.dir(context), "memoria.json")
                val isNew = !file.exists()
                VaultPrefs(file).also { prefs ->
                    if (isNew) {
                        val old = context.getSharedPreferences("ultrayo_memoria", Context.MODE_PRIVATE).all
                        val editor = prefs.edit()
                        old.forEach { (k, v) ->
                            when (v) {
                                is String -> editor.putString(k, v)
                                is Boolean -> editor.putBoolean(k, v)
                            }
                        }
                        editor.apply()
                    }
                    instance = prefs
                }
            }
        }
    }
}

/** "Revisa tu memoria": busca solo en la carpeta de Ultra Yo (recuerdos, datos guardados y archivos guardados). */
object MemoryCheck {

    fun search(context: Context, query: String, limit: Int = 20): List<String> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        fun matches(text: String) = words.all { text.lowercase().contains(it) }
        val out = mutableListOf<String>()
        Memory(context).facts().filter(::matches).forEach { out += "Recuerdo: $it" }
        DataStore.get(context).search(query, limit = limit).forEach { out += "Dato ${it.line()}" }
        for (file in Vault.listFiles(context)) {
            if (out.size >= limit) break
            val text = if (words.isNotEmpty() && !matches(file.name) && DocReader.extension(file.name) in DocReader.TEXT) {
                runCatching { file.bufferedReader().use { r -> CharArray(20_000).let { String(it, 0, r.read(it).coerceAtLeast(0)) } } }.getOrDefault("")
            } else ""
            if (matches(file.name) || (text.isNotEmpty() && matches(text))) out += "Archivo guardado: ${file.path}"
        }
        if (out.isEmpty()) {
            out += if (words.isEmpty()) "Mi carpeta (${Vault.path(context)}) está vacía todavía."
            else "No encontré nada sobre eso en mi carpeta (${Vault.path(context)})."
        }
        return out.take(limit)
    }
}
