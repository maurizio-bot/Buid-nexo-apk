package com.ultrayo.app

import android.app.KeyguardManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_FOUND = 30
private const val DEFAULT_COLLECTION = "notas"

/** Un dato guardado: un texto dentro de una colección (por ejemplo "gastos" o "ideas"). */
data class Datum(val id: Long, val collection: String, val text: String, val created: Long) {
    fun line(): String = "#$id [$collection, ${DATE.format(Date(created))}] $text"

    private companion object {
        val DATE = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("es-ES"))
    }
}

/**
 * Base de datos del propio teléfono (SQLite). Guarda lo que Maurizio pide guardar y sobrevive
 * a reinicios y actualizaciones de la app. A diferencia de los recuerdos, no va entera en cada
 * conversación: la IA la consulta cuando hace falta, así que puede crecer mucho.
 */
class DataStore private constructor(context: Context, path: String) :
    SQLiteOpenHelper(context, path, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE datos (id INTEGER PRIMARY KEY AUTOINCREMENT, coleccion TEXT NOT NULL, " +
                "texto TEXT NOT NULL, creado INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX datos_coleccion ON datos(coleccion)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Guarda [text] en [collection] y devuelve su número. */
    fun add(collection: String, text: String): Long {
        val values = ContentValues().apply {
            put("coleccion", normalize(collection))
            put("texto", text.trim())
            put("creado", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("datos", null, values)
    }

    /** Los datos que contienen todas las palabras de [query] (o todos si está vacío), los más nuevos primero. */
    fun search(query: String, collection: String? = null, limit: Int = MAX_FOUND): List<Datum> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        collection?.takeIf { it.isNotBlank() }?.let {
            where += "coleccion = ?"
            args += normalize(it)
        }
        query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach {
            where += "(LOWER(texto) LIKE ? OR coleccion LIKE ?)"
            args += "%$it%"
            args += "%$it%"
        }
        return readableDatabase.query(
            "datos", null, where.joinToString(" AND ").ifEmpty { null }, args.toTypedArray(),
            null, null, "creado DESC", limit.toString(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Datum(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
            }
        }
    }

    fun get(id: Long): Datum? =
        readableDatabase.query("datos", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) Datum(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)) else null
        }

    /** Cada colección con cuántos datos tiene. */
    fun collections(): Map<String, Int> =
        readableDatabase.rawQuery("SELECT coleccion, COUNT(*) FROM datos GROUP BY coleccion ORDER BY coleccion", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }

    fun delete(id: Long): Boolean = writableDatabase.delete("datos", "id = ?", arrayOf(id.toString())) > 0

    fun deleteCollection(collection: String): Int =
        writableDatabase.delete("datos", "coleccion = ?", arrayOf(normalize(collection)))

    private fun normalize(collection: String) = collection.trim().lowercase().ifBlank { DEFAULT_COLLECTION }

    companion object {
        @Volatile
        private var instance: DataStore? = null

        /** La base de datos vive en la carpeta de Ultra Yo. Si había una dentro de la app (versión 0.9.0), se mueve allí. */
        private fun databaseFile(context: Context): java.io.File {
            val file = java.io.File(Vault.dir(context), "datos.db")
            val old = context.getDatabasePath("ultrayo_datos.db")
            if (!file.exists() && old.exists()) runCatching { old.copyTo(file) }
            return file
        }

        fun get(context: Context): DataStore =
            instance ?: synchronized(this) {
                instance ?: DataStore(context.applicationContext, databaseFile(context).path).also { instance = it }
            }
    }
}

/** Herramientas para que la IA guarde y consulte datos en su carpeta. Guardar es directo; borrar lo confirma Maurizio. */
class DataTools(context: Context) : ToolSet {

    private val context = context.applicationContext
    private val store get() = DataStore.get(context)

    @Tool(description = "Guarda un dato en la base de datos del teléfono cuando Maurizio te pide guardar, apuntar o registrar algo.")
    fun guardarDato(
        @ToolParam(description = "Colección donde guardarlo, en una palabra, por ejemplo gastos, ideas, contactos o notas") coleccion: String,
        @ToolParam(description = "El dato completo que hay que guardar") texto: String,
    ): String {
        if (locked()) return LOCKED
        if (texto.isBlank()) return "No hay nada que guardar."
        val id = store.add(coleccion, texto)
        return "Guardado en ${coleccion.trim().lowercase().ifBlank { DEFAULT_COLLECTION }} con el número $id."
    }

    @Tool(description = "Revisa tu memoria: busca en tu carpeta de almacenamiento (datos guardados, recuerdos y archivos guardados).")
    fun revisarMemoria(
        @ToolParam(description = "Palabras a buscar; vacío para ver lo último que guardaste") texto: String,
    ): List<String> {
        if (locked()) return listOf(LOCKED)
        return MemoryCheck.search(context, texto)
    }

    @Tool(description = "Guarda en tu carpeta una copia de un archivo del teléfono (foto, vídeo, documento…) usando la ruta que devolvió buscarArchivos. El original no se toca.")
    fun guardarArchivo(
        @ToolParam(description = "Ruta del archivo, tal como la devolvió buscarArchivos") ruta: String,
    ): String {
        if (locked()) return LOCKED
        val file = DeviceFiles.find(context, ruta) ?: return "No encontré ese archivo."
        return try {
            "Guardado en ${Vault.keepCopy(context, file.uri, file.name).path}."
        } catch (e: Exception) {
            "No pude guardarlo: ${e.message}"
        }
    }

    @Tool(description = "Busca en los datos guardados. Devuelve cada dato con su número, colección y fecha.")
    fun buscarDatos(
        @ToolParam(description = "Palabras a buscar; vacío para ver los últimos") texto: String,
        @ToolParam(description = "Colección donde buscar; vacío para buscar en todas") coleccion: String,
    ): List<String> {
        if (locked()) return listOf(LOCKED)
        val found = store.search(texto, coleccion)
        if (found.isNotEmpty()) return found.map { it.line() }
        val collections = store.collections()
        return listOf(
            if (collections.isEmpty()) "Todavía no hay nada guardado."
            else "No encontré nada. Colecciones que hay: " + collections.entries.joinToString { "${it.key} (${it.value})" }
        )
    }

    @Tool(description = "Propone borrar un dato guardado por su número. Maurizio debe aceptarlo en la pantalla.")
    fun proponerBorrarDato(
        @ToolParam(description = "Número del dato, tal como lo devolvió buscarDatos") numero: String,
    ): String {
        if (locked()) return LOCKED
        val datum = numero.filter { it.isDigit() }.toLongOrNull()?.let { store.get(it) }
            ?: return "No hay ningún dato con ese número."
        SelfEdits.propose(SelfChange("Borrar el dato #${datum.id} de ${datum.collection}", datum.text, undoable = false) {
            store.delete(datum.id)
        })
        return "Propuesta enviada. No se borra hasta que Maurizio pulse Aplicar. Díselo."
    }

    private fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private companion object {
        const val LOCKED = "Para eso hay que desbloquear el teléfono."
    }
}

/** Órdenes directas para la base de datos, sin pasar por el modelo: "guarda en gastos: 20 € comida", "¿qué tengo guardado de gastos?". */
object DataCommands {

    private val saveIn = Regex(
        "^(?:guarda|registra|anota)(?: esto| este dato)? en (?:la |mi |mis |el |los |las )?(?:colecci[oó]n |lista )?(?:de )?([\\p{L}\\d_-]+)\\s*[:,]\\s*(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val save = Regex(
        "^(?:guarda|registra|anota)(?: esto| este dato| que)?[:,]?\\s+(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val review = Regex(
        "^(?:revisa|mira|busca en) (?:tu|la|en tu) memoria(?:\\s*[,:]?\\s*(?:y )?(?:busca |buscando |sobre |de |por )?(.+))?$",
        RegexOption.IGNORE_CASE,
    )
    private val where = Regex("d[oó]nde (?:guardas|est[aá]n?|tienes) (?:las cosas|tu (?:memoria|carpeta)|lo que (?:te pido|guardas)|tus datos)", RegexOption.IGNORE_CASE)
    private val list = Regex(
        "^(?:¿\\s*)?(?:qu[eé] (?:tengo|hay) guardado|mu[eé]strame (?:lo guardado|mis datos|los datos)|busca en (?:mis |los )?(?:datos|lo guardado))(?:\\s+(?:en|de|sobre|con)\\s+(?:la |el |los |las |mis )?(.+))?$",
        RegexOption.IGNORE_CASE,
    )

    /** Devuelve la respuesta si [text] era una orden de datos, o null si no lo era. */
    fun handle(text: String, context: Context): String? {
        val store = DataStore.get(context)
        val t = text.trim().trimStart('¿').trimEnd('.', '!', '?')
        if (where.containsMatchIn(t)) {
            return "Todo lo guardo en mi carpeta: ${Vault.path(context)}. Ahí están mi memoria, mis datos y los archivos que me pides guardar."
        }
        review.find(t)?.let { m ->
            return MemoryCheck.search(context, m.groupValues[1], limit = 8).joinToString(". ")
        }
        saveIn.find(t)?.let { m ->
            val id = store.add(m.groupValues[1], m.groupValues[2])
            return "Guardado en ${m.groupValues[1].lowercase()}, es el dato número $id."
        }
        save.find(t)?.let { m ->
            // "guarda la foto…" o "guarda los cambios" no son datos: que lo decida el modelo.
            val what = m.groupValues[1].trim()
            if (Regex("^(la|esta|esa|las) (foto|imagen)|^(los )?cambios", RegexOption.IGNORE_CASE).containsMatchIn(what)) return null
            val id = store.add(DEFAULT_COLLECTION, what.replaceFirstChar { it.uppercase() })
            return "Guardado en notas, es el dato número $id."
        }
        list.find(t)?.let { m ->
            val about = m.groupValues[1].trim()
            val collection = store.collections().keys.firstOrNull { it.equals(about, ignoreCase = true) }
            val found = if (collection != null) store.search("", collection, 10) else store.search(about, limit = 10)
            if (found.isEmpty()) return if (about.isEmpty()) "Todavía no hay nada guardado." else "No tengo nada guardado sobre $about."
            return found.joinToString(". ") { "${it.text} (${it.collection}, número ${it.id})" }
        }
        return null
    }
}
