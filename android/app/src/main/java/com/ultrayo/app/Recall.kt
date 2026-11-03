package com.ultrayo.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

/** Algo que Ultra Yo ha aprendido por su cuenta. [tier]: 2 importante, 1 útil, 0 trivial. */
data class Learned(
    val id: Long,
    val text: String,
    val kind: String,
    val tier: Int,
    val pinned: Boolean,
    val source: String,
    val created: Long,
    val used: Long,
    val uses: Int,
) {
    val tierName get() = if (pinned) "fijado" else when (tier) { 2 -> "importante"; 1 -> "útil"; else -> "trivial" }

    /** Cuánto merece quedarse: importancia, uso y lo reciente que es. */
    fun score(now: Long = System.currentTimeMillis()): Double {
        val days = (now - used).coerceAtLeast(0) / 86_400_000.0
        return (if (pinned) 100.0 else 0.0) + tier * 3 + Math.log1p(uses.toDouble()) - days / 30
    }
}

/**
 * La memoria que Ultra Yo va llenando sola (además de lo que Maurizio le enseña con "recuerda que").
 * Por capas: lo importante y lo fijado se tiene siempre en cuenta; lo útil se busca por parecido
 * cuando viene al caso; lo trivial que no se usa se olvida con el tiempo. Cada recuerdo guarda un
 * vector pequeño (256 bytes) para encontrarlo por parecido y fusionar los repetidos.
 *
 * Los vectores se calculan con trozos de palabras, sin modelo: encuentran "me gusta el café solo"
 * al preguntar por "cafés", pero no saben que "perro" y "can" son lo mismo.
 */
class Recall private constructor(private val context: Context, file: File) :
    SQLiteOpenHelper(context, file.path, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE recuerdos (id INTEGER PRIMARY KEY AUTOINCREMENT, texto TEXT NOT NULL, tipo TEXT NOT NULL, " +
                "capa INTEGER NOT NULL, fijado INTEGER NOT NULL DEFAULT 0, fuente TEXT NOT NULL DEFAULT '', " +
                "creado INTEGER NOT NULL, usado INTEGER NOT NULL, usos INTEGER NOT NULL DEFAULT 0, vector BLOB NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private val prefs = context.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)

    /** Aprender sola de las conversaciones (por defecto sí). */
    var learning: Boolean
        get() = prefs.getBoolean("aprender_sola", true)
        set(value) = prefs.edit().putBoolean("aprender_sola", value).apply()

    /** Espacio máximo para lo que aprende y la caché de internet, en MB. */
    var limitMb: Int
        get() = prefs.getInt("limite_memoria_mb", 500)
        set(value) = prefs.edit().putInt("limite_memoria_mb", value).apply()

    private data class Row(val learned: Learned, val vec: ByteArray)

    @Synchronized
    private fun rows(): List<Row> =
        readableDatabase.rawQuery("SELECT id, texto, tipo, capa, fijado, fuente, creado, usado, usos, vector FROM recuerdos", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Row(
                        Learned(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4) == 1, c.getString(5), c.getLong(6), c.getLong(7), c.getInt(8)),
                        c.getBlob(9),
                    )
                )
            }
        }

    /**
     * Guarda un recuerdo. Si ya había uno casi igual, lo refuerza en vez de repetirlo (y se queda
     * con el texto más completo). Devuelve false si solo reforzó uno que ya existía.
     */
    @Synchronized
    fun add(text: String, kind: String, tier: Int, source: String = ""): Boolean {
        val clean = text.trim().removePrefix("-").trim()
        if (clean.length < 6) return false
        val vec = vector(clean)
        val now = System.currentTimeMillis()
        val twin = rows().maxByOrNull { similarity(it.vec, vec) }?.takeIf { similarity(it.vec, vec) > SAME }
        if (twin != null) {
            val old = twin.learned
            val values = ContentValues().apply {
                put("capa", maxOf(old.tier, tier))
                put("usado", now)
                put("usos", old.uses + 1)
                if (!old.pinned && clean.length > old.text.length) {
                    put("texto", clean)
                    put("vector", vec)
                }
            }
            writableDatabase.update("recuerdos", values, "id = ?", arrayOf(old.id.toString()))
            return false
        }
        writableDatabase.insert("recuerdos", null, ContentValues().apply {
            put("texto", clean)
            put("tipo", kind)
            put("capa", tier)
            put("fuente", source)
            put("creado", now)
            put("usado", now)
            put("vector", vec)
        })
        maintain()
        return true
    }

    /** Vector de sentido de cada recuerdo (por id), guardado mientras su texto no cambie. */
    private val senses = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, FloatArray>>()

    /** Parecido de [row] con la pregunta: por letras y, si está el modelo, también por sentido (sinónimos). */
    private fun likeness(row: Row, q: ByteArray, qSense: FloatArray?): Double {
        val letters = similarity(row.vec, q)
        if (qSense == null) return letters
        val text = row.learned.text
        val v = senses[row.learned.id]?.takeIf { it.first == text }?.second
            ?: Sentido.embed(text)?.also { senses[row.learned.id] = text to it }
        return SentidoUmbrales.mezcla(letters, v?.let { SentidoModel.cosine(qSense, it) })
    }

    private fun senseOf(query: String): FloatArray? = Sentido.embed(query)?.takeIf { v -> v.any { it != 0f } }

    /** Los recuerdos que más se parecen a [query]. Cuentan como usados (así no se olvidan). */
    @Synchronized
    fun relevant(query: String, k: Int = 4, min: Double = 0.30, exclude: Set<Long> = emptySet()): List<Learned> {
        val q = vector(query)
        val qSense = senseOf(query)
        val found = rows().filter { it.learned.id !in exclude }
            .map { it to likeness(it, q, qSense) }.filter { it.second >= min }
            .sortedByDescending { it.second }.take(k).map { it.first.learned }
        if (found.isNotEmpty()) {
            writableDatabase.execSQL(
                "UPDATE recuerdos SET usado = ?, usos = usos + 1 WHERE id IN (${found.joinToString(",") { it.id.toString() }})",
                arrayOf<Any>(System.currentTimeMillis()),
            )
        }
        return found
    }

    /**
     * Los recuerdos cuyo texto cumple [belongs] (por ejemplo, los de una ficha), los más parecidos a
     * [query] primero. Cuentan como usados.
     */
    @Synchronized
    fun matching(query: String, k: Int, belongs: (String) -> Boolean): List<Learned> {
        val q = vector(query)
        val qSense = senseOf(query)
        val found = rows().filter { belongs(it.learned.text) }
            .sortedByDescending { likeness(it, q, qSense) + it.learned.tier * 0.05 }.take(k).map { it.learned }
        if (found.isNotEmpty()) {
            writableDatabase.execSQL(
                "UPDATE recuerdos SET usado = ?, usos = usos + 1 WHERE id IN (${found.joinToString(",") { it.id.toString() }})",
                arrayOf<Any>(System.currentTimeMillis()),
            )
        }
        return found
    }

    /** Lo que se tiene siempre en cuenta: fijado, reglas e importante (lo más valioso primero). */
    fun alwaysOn(limit: Int = 30): List<Learned> =
        rows().map { it.learned }.filter { it.pinned || it.tier >= 2 || it.kind == "regla" }
            .sortedByDescending { it.score() }.take(limit)

    /** Todo, lo más valioso primero, opcionalmente filtrado por palabras. */
    fun all(filter: String = ""): List<Learned> {
        val words = filter.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        return rows().map { it.learned }.filter { l -> words.all { l.text.lowercase(Locale.ROOT).contains(it) } }
            .sortedByDescending { it.score() }
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM recuerdos", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    @Synchronized
    fun setPinned(id: Long, pinned: Boolean) {
        writableDatabase.execSQL("UPDATE recuerdos SET fijado = ? WHERE id = ?", arrayOf<Any>(if (pinned) 1 else 0, id))
    }

    @Synchronized
    fun delete(id: Long) {
        writableDatabase.delete("recuerdos", "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("recuerdos", "fijado = 0", null)
    }

    /** Espacio que ocupa lo aprendido más la caché de internet y el índice de documentos, en bytes. */
    fun usedBytes(): Long {
        val db = File(readableDatabase.path)
        val extra = listOf(File(db.path + "-journal"), File(db.path + "-wal"), File(context.cacheDir, "indice-documentos.json"))
        return db.length() + extra.sumOf { it.length() } + WebCache.bytes()
    }

    /**
     * Ordena la memoria: lo útil que lleva 90 días sin usarse pasa a trivial, lo trivial que lleva 30
     * días sin usarse se olvida, y si se acerca al límite de espacio vacía la caché de internet,
     * fusiona recuerdos casi iguales y borra los que menos valen hasta quedarse en el 80 %.
     */
    @Synchronized
    fun maintain() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        writableDatabase.execSQL("UPDATE recuerdos SET capa = 0 WHERE fijado = 0 AND capa = 1 AND usado < ?", arrayOf<Any>(now - 90 * day))
        writableDatabase.execSQL("DELETE FROM recuerdos WHERE fijado = 0 AND capa = 0 AND usado < ?", arrayOf<Any>(now - 30 * day))
        val limit = limitMb * 1024L * 1024L
        if (usedBytes() < limit * 0.9) return
        WebCache.trim(limit / 10)
        mergeTwins()
        if (usedBytes() < limit * 0.8) return
        val doomed = rows().map { it.learned }.filterNot { it.pinned }.sortedBy { it.score(now) }
        for (l in doomed) {
            if (usedBytes() < limit * 0.8) break
            delete(l.id)
        }
        writableDatabase.execSQL("VACUUM")
    }

    /** Junta los recuerdos que dicen casi lo mismo. */
    private fun mergeTwins() {
        val all = rows().toMutableList()
        val gone = mutableSetOf<Long>()
        for (i in all.indices) {
            val a = all[i]
            if (a.learned.id in gone) continue
            for (j in i + 1 until all.size) {
                val b = all[j]
                if (b.learned.id in gone || similarity(a.vec, b.vec) < MERGE) continue
                val (keep, drop) = if (b.learned.pinned || (!a.learned.pinned && b.learned.text.length > a.learned.text.length)) b to a else a to b
                writableDatabase.execSQL(
                    "UPDATE recuerdos SET usos = usos + ?, capa = MAX(capa, ?) WHERE id = ?",
                    arrayOf<Any>(drop.learned.uses, drop.learned.tier, keep.learned.id),
                )
                delete(drop.learned.id)
                gone += drop.learned.id
                if (drop === a) break
            }
        }
    }

    companion object {
        private const val DIM = 256
        private const val SAME = 0.86
        private const val MERGE = 0.80

        @Volatile
        private var instance: Recall? = null

        fun get(context: Context): Recall = instance ?: synchronized(this) {
            instance ?: Recall(context.applicationContext, File(Vault.dir(context), "recuerdos.db")).also {
                WebCache.init(context)
                instance = it
            }
        }

        /** Un vector de 256 números (de -127 a 127) hecho con palabras y trozos de tres letras. */
        fun vector(text: String): ByteArray {
            val v = FloatArray(DIM)
            val words = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .split(Regex("[^a-z0-9ñ]+")).filter { it.length > 2 }
            for (w in words) {
                add(v, "w:$w", 2f)
                val padded = "^$w$"
                for (i in 0..padded.length - 3) add(v, padded.substring(i, i + 3), 1f)
            }
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: 1f
            return ByteArray(DIM) { (v[it] / norm * 127).toInt().coerceIn(-127, 127).toByte() }
        }

        private fun add(v: FloatArray, feature: String, weight: Float) {
            val h = feature.hashCode()
            v[Math.floorMod(h, DIM)] += if ((h ushr 31) == 0) weight else -weight
        }

        fun similarity(a: ByteArray, b: ByteArray): Double {
            if (a.size != b.size) return 0.0
            var dot = 0L
            for (i in a.indices) dot += a[i] * b[i]
            return dot / (127.0 * 127.0)
        }
    }
}
