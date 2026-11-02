package com.ultrayo.app

import android.app.KeyguardManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Una ficha: un proyecto, una persona, un lugar o un tema que Ultra Yo conoce. */
data class Ficha(
    val id: Long,
    val name: String,
    val kind: String,
    val aliases: List<String>,
    val summary: String,
    val created: Long,
    val used: Long,
    val uses: Int,
) {
    val names get() = listOf(name) + aliases

    fun score(now: Long = System.currentTimeMillis()): Double =
        Math.log1p(uses.toDouble()) - (now - used).coerceAtLeast(0) / 86_400_000.0 / 30

    /** Una línea para el índice que el modelo tiene siempre presente. */
    fun indexLine(): String = "$name ($kind)" + (if (summary.isNotBlank()) ": ${summary.take(110)}" else "")
}

/**
 * La memoria "como humana" de Ultra Yo, en dos niveles:
 * - Memoria rápida: el índice de fichas (nombre, tipo, alias y un resumen de una frase). Está
 *   siempre en memoria y el modelo la tiene presente; se consulta en microsegundos.
 * - Memoria profunda: los recuerdos ([Recall]), los datos ([DataStore]) y lo que Maurizio le
 *   enseñó ([Memory]). Un recuerdo pertenece a una ficha cuando la nombra (Ultra los apunta como
 *   "Proyecto1: …"), así que el enlace se mantiene solo, también con lo que ya había.
 *
 * Lo importante para la rapidez: la ficha se busca con código en la frase, antes de que el modelo
 * piense, y se le pasa su detalle ya preparado; el modelo no tiene que dar una vuelta para buscar.
 * La búsqueda perdona errores de voz ("proyecto uno", "proyeto1", b/v, s/z/c, h…), que en el coche
 * con el micro del manos libres pasan a menudo.
 */
class Cards private constructor(context: Context, file: File) :
    SQLiteOpenHelper(context, file.path, null, 1) {

    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE fichas (id INTEGER PRIMARY KEY AUTOINCREMENT, nombre TEXT NOT NULL, tipo TEXT NOT NULL, " +
                "alias TEXT NOT NULL DEFAULT '', resumen TEXT NOT NULL DEFAULT '', creado INTEGER NOT NULL, " +
                "usado INTEGER NOT NULL, usos INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** El índice entero en memoria: se lee de disco una vez y se rehace solo al cambiar algo. */
    @Volatile
    private var cache: List<Ficha>? = null

    @Synchronized
    fun all(): List<Ficha> = cache ?: readableDatabase.rawQuery(
        "SELECT id, nombre, tipo, alias, resumen, creado, usado, usos FROM fichas", null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                Ficha(
                    c.getLong(0), c.getString(1), c.getString(2),
                    c.getString(3).split('|').map { it.trim() }.filter { it.isNotEmpty() },
                    c.getString(4), c.getLong(5), c.getLong(6), c.getInt(7),
                )
            )
        }
    }.also { cache = it }

    fun ranked(): List<Ficha> = all().sortedByDescending { it.score() }

    fun get(id: Long): Ficha? = all().firstOrNull { it.id == id }

    /** La ficha que se llama (o casi) [name]. */
    fun named(name: String): Ficha? {
        val key = Spoken.key(name)
        if (key.isEmpty()) return null
        return all().firstOrNull { f -> f.names.any { Spoken.key(it) == key } }
            ?: all().firstOrNull { f -> f.names.any { Spoken.close(Spoken.key(it), key) } }
    }

    /**
     * Crea la ficha o, si ya existe una con ese nombre (aunque esté mal escrito), la completa.
     * El resumen solo se cambia si [replaceSummary] o si no tenía. Devuelve la ficha y si es nueva.
     */
    @Synchronized
    fun upsert(name: String, kind: String = "", summary: String = "", aliases: List<String> = emptyList(), replaceSummary: Boolean = false): Pair<Ficha, Boolean> {
        val cleanName = name.trim().trim('"', '«', '»', '.', ',').replaceFirstChar { it.uppercase() }
        val cleanKind = normalKind(kind)
        val now = System.currentTimeMillis()
        val old = named(cleanName)
        if (old != null) {
            val newAliases = (old.aliases + aliases + (if (cleanName.equals(old.name, true)) emptyList() else listOf(cleanName)))
                .map { it.trim() }.filter { it.isNotEmpty() && !it.equals(old.name, true) }.distinctBy { Spoken.key(it) }
            val values = ContentValues().apply {
                put("alias", newAliases.joinToString("|"))
                put("usado", now)
                if (summary.isNotBlank() && (replaceSummary || old.summary.isBlank())) put("resumen", summary.trim().take(300))
                if (old.kind == "tema" && cleanKind != "tema") put("tipo", cleanKind)
            }
            writableDatabase.update("fichas", values, "id = ?", arrayOf(old.id.toString()))
            changed()
            return get(old.id)!! to false
        }
        val id = writableDatabase.insert("fichas", null, ContentValues().apply {
            put("nombre", cleanName)
            put("tipo", cleanKind)
            put("alias", aliases.filter { it.isNotBlank() }.joinToString("|"))
            put("resumen", summary.trim().take(300))
            put("creado", now)
            put("usado", now)
        })
        changed()
        return get(id)!! to true
    }

    @Synchronized
    fun setSummary(id: Long, summary: String) {
        writableDatabase.execSQL("UPDATE fichas SET resumen = ? WHERE id = ?", arrayOf<Any>(summary.trim().take(300), id))
        changed()
    }

    @Synchronized
    fun delete(id: Long) {
        writableDatabase.delete("fichas", "id = ?", arrayOf(id.toString()))
        changed()
    }

    /** Marca la ficha como usada (las que más usa salen antes en el índice). */
    @Synchronized
    fun touch(id: Long) {
        writableDatabase.execSQL("UPDATE fichas SET usado = ?, usos = usos + 1 WHERE id = ?", arrayOf<Any>(System.currentTimeMillis(), id))
        cache = null
    }

    private fun changed() {
        cache = null
        Assistant.brainDirty = true
    }

    /** Las fichas que se nombran en [text], la más larga primero (como mucho [max]). */
    fun detect(text: String, max: Int = 2): List<Ficha> {
        val words = Spoken.words(text)
        if (words.isEmpty()) return emptyList()
        return all().mapNotNull { f -> f.names.maxOfOrNull { Spoken.find(words, it) }?.takeIf { it > 0 }?.let { f to it } }
            .sortedByDescending { it.second }.take(max).map { it.first }
    }

    /** ¿[text] habla de [f]? */
    fun mentions(f: Ficha, text: String): Boolean {
        val words = Spoken.words(text)
        return f.names.any { Spoken.find(words, it) > 0 }
    }

    /** La memoria profunda de una ficha: lo que más se parece a [query] primero. */
    fun details(f: Ficha, query: String = "", k: Int = 5): Details {
        val recall = Recall.get(appContext)
        val learned = runCatching { recall.matching(query.ifBlank { f.name }, k) { mentions(f, it) } }.getOrDefault(emptyList())
        val taught = runCatching { Memory(appContext).facts().filter { mentions(f, it) } }.getOrDefault(emptyList())
        val data = runCatching {
            f.names.flatMap { DataStore.get(appContext).search(it, limit = 4) }.distinctBy { it.id }.take(4)
        }.getOrDefault(emptyList())
        return Details(learned.map { it.text }, learned.map { it.id }.toSet(), taught, data.map { "${it.text} (${it.collection})" })
    }

    data class Details(val learned: List<String>, val learnedIds: Set<Long>, val taught: List<String>, val data: List<String>) {
        val all get() = taught + learned + data
    }

    /** Lo que se le pasa al modelo cuando sale una ficha en la frase. */
    fun context(f: Ficha, query: String): Pair<String, Set<Long>> {
        val d = details(f, query)
        val text = buildString {
            append("[Ficha de ${f.name} (${f.kind})")
            if (f.summary.isNotBlank()) append(": ${f.summary}")
            append('\n')
            d.all.take(8).forEach { append("- ").append(it).append('\n') }
            if (d.all.isEmpty()) append("- (No tienes más detalle guardado; si te pregunta algo concreto que no está aquí, dilo.)\n")
            append(']')
        }
        return text to d.learnedIds
    }

    /** Respuesta hablada e instantánea a "¿recuerdas X?", sin modelo. */
    fun spoken(f: Ficha): String {
        val d = details(f, f.name, k = 6)
        val head = if (f.summary.isNotBlank()) "Sí, ${f.name}: ${f.summary.trimEnd('.')}."
        else d.all.firstOrNull()?.let { "Sí, ${f.name}. ${it.trimEnd('.')}." } ?: "Sí, tengo la ficha de ${f.name}, pero todavía sin detalles."
        val more = d.all.size - (if (f.summary.isBlank() && d.all.isNotEmpty()) 1 else 0)
        return head + when {
            more <= 0 -> ""
            more == 1 -> " Tengo un detalle más; pregúntame lo que quieras."
            else -> " Tengo $more detalles más; pregúntame lo concreto."
        }
    }

    companion object {
        val KINDS = listOf("proyecto", "persona", "lugar", "empresa", "tema")

        fun normalKind(kind: String): String {
            val k = Spoken.plain(kind)
            return when {
                k.startsWith("proyect") || k.startsWith("app") || k.startsWith("programa") -> "proyecto"
                k.startsWith("person") || k.startsWith("amig") || k.startsWith("famil") || k.startsWith("client") -> "persona"
                k.startsWith("lugar") || k.startsWith("sitio") || k.startsWith("ciudad") -> "lugar"
                k.startsWith("empresa") || k.startsWith("negocio") || k.startsWith("compan") -> "empresa"
                else -> "tema"
            }
        }

        @Volatile
        private var instance: Cards? = null

        fun get(context: Context): Cards = instance ?: synchronized(this) {
            instance ?: Cards(context.applicationContext, File(Vault.dir(context), "fichas.db")).also { instance = it }
        }
    }
}

/**
 * Comparar nombres como suenan, para que los errores del reconocimiento de voz no importen:
 * sin acentos, "uno" = "1", b = v, s = z = c(e/i), k = c(a/o/u) = qu, y = ll, sin h, sin letras dobles.
 */
object Spoken {

    private val numbers = mapOf(
        "cero" to "0", "uno" to "1", "una" to "1", "dos" to "2", "tres" to "3", "cuatro" to "4", "cinco" to "5",
        "seis" to "6", "siete" to "7", "ocho" to "8", "nueve" to "9", "diez" to "10",
    )
    private val filler = setOf("el", "la", "los", "las", "de", "del", "mi", "tu", "un", "lo", "al")

    fun plain(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun sound(word: String): String = numbers[word] ?: word
        .replace("qu", "k").replace(Regex("c(?=[eiy])"), "s").replace("c", "k").replace("z", "s")
        .replace("v", "b").replace("ll", "y").replace("w", "u").replace(Regex("(?<!c)h"), "")
        .replace(Regex("(.)\\1+"), "$1")

    /** Las palabras de [text] tal como suenan; las cifras pegadas a letras se separan ("proyecto1" → proyekto 1). */
    fun words(text: String): List<String> =
        plain(text).replace(Regex("(?<=[a-z])(?=\\d)|(?<=\\d)(?=[a-z])"), " ")
            .split(Regex("[^a-z0-9ñ]+")).filter { it.isNotEmpty() }.map(::sound)

    /** Una clave para comparar nombres enteros. */
    fun key(name: String): String = words(name).filterNot { it in filler }.joinToString("")

    private fun tolerance(length: Int) = when {
        length <= 4 -> 0
        length <= 8 -> 1
        else -> 2
    }

    /** Casi iguales; las cifras tienen que coincidir ("proyecto 1" no es "proyecto 2"). */
    fun close(a: String, b: String): Boolean =
        a.isNotEmpty() && b.isNotEmpty() && a.filter { it.isDigit() } == b.filter { it.isDigit() } &&
            kotlin.math.abs(a.length - b.length) <= 2 && distance(a, b) <= tolerance(maxOf(a.length, b.length))

    /**
     * Si el nombre [name] aparece en [heard] (con algo de margen para errores), devuelve lo largo
     * que es lo encontrado; si no, 0.
     */
    fun find(heard: List<String>, name: String): Int {
        val target = key(name)
        if (target.length < 3) return 0
        val count = words(name).filterNot { it in filler }.size.coerceAtLeast(1)
        for (size in maxOf(1, count - 1)..count + 1) {
            for (start in 0..heard.size - size) {
                val window = heard.subList(start, start + size)
                if (window.first() in filler) continue
                val joined = window.joinToString("")
                if (joined == target || close(joined, target)) return target.length
            }
        }
        return 0
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

/** Órdenes de fichas, al instante y sin modelo. */
object CardCommands {

    private const val KIND = "(proyecto|persona|lugar|empresa|tema|cliente|amigo|amiga|app|aplicaci[oó]n)"

    private val create = Regex(
        "^(?:crea|haz|abre|a[ñn]ade|nueva|nuevo)(?: una| un)?(?: ficha| tarjeta)?(?: (?:de|del|para)(?: la| el)?)?(?: $KIND)?(?: llamad[oa]| que se llama)?\\s+([^:,]+?)\\s*(?:[:,]\\s*(.+))?$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val note = Regex(
        "^(?:apunta|anota|guarda|a[ñn]ade|recuerda)(?: que)? (?:en|para|sobre)(?: la ficha de| la ficha| el| la| mi)?(?: $KIND)?\\s+([^:,]+?)\\s*[:,]\\s*(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ask = Regex(
        "^(?:te )?(?:recuerdas|acuerdas|acuerdas de|recuerdas a|recuerdas el|conoces|conoces a)\\s+(.+)$|" +
            "^qu[eé] (?:sabes|recuerdas|tienes) (?:de|del|sobre|acerca de)\\s+(.+)$|^h[aá]blame (?:de|del|sobre)\\s+(.+)$|" +
            "^qu[eé] (?:es|era) (?:el |la )?(.+)$|^qui[eé]n (?:es|era)\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val list = Regex(
        "^(?:qu[eé]|cu[aá]les) (?:proyectos|fichas|personas|lugares) (?:tengo|tienes|conoces|hay)|^(?:mis|tus) (?:proyectos|fichas)$|^lista de (?:proyectos|fichas)",
        RegexOption.IGNORE_CASE,
    )
    private val delete = Regex("^(?:borra|elimina|olvida) la ficha (?:de |del )?(.+)$", RegexOption.IGNORE_CASE)

    fun handle(text: String, context: Context): String? {
        val t = text.trim().trimStart('¿', '¡').trimEnd('.', '!', '?')
        val cards = Cards.get(context)

        list.find(t)?.let { m ->
            val kind = when {
                m.value.contains("proyecto", true) -> "proyecto"
                m.value.contains("persona", true) -> "persona"
                m.value.contains("lugar", true) -> "lugar"
                else -> null
            }
            val found = cards.ranked().filter { kind == null || it.kind == kind }
            if (found.isEmpty()) return "Todavía no tengo ninguna ficha${kind?.let { " de $it" } ?: ""}. Dime, por ejemplo, \"nuevo proyecto Ultra: mi asistente\"."
            return "Tengo ${found.size}: " + found.take(12).joinToString(", ") { it.name } + (if (found.size > 12) " y más." else ".")
        }
        delete.find(t)?.let { m ->
            val f = cards.named(m.groupValues[1]) ?: return "No tengo ninguna ficha de ${m.groupValues[1]}."
            cards.delete(f.id)
            return "Hecho, he quitado la ficha de ${f.name}. Lo que tenía apuntado sobre él sigue en mi memoria."
        }
        note.find(t)?.let { m ->
            val name = m.groupValues[2].trim()
            val explicit = m.groupValues[1].isNotBlank()
            val f = cards.named(name) ?: if (explicit) cards.upsert(name, m.groupValues[1]).first else return@let
            Recall.get(context).add("${f.name}: ${m.groupValues[3].trim()}", kind = "dato", tier = 1, source = "ficha")
            cards.touch(f.id)
            Assistant.brainDirty = true
            return "Apuntado en la ficha de ${f.name}."
        }
        create.find(t)?.let { m ->
            val word = t.substringBefore(' ').lowercase(Locale.ROOT)
            val saysCard = Regex("\\b(ficha|tarjeta)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)
            val kindWord = m.groupValues[1]
            // "nuevo proyecto X" o "crea una ficha de X"; "crea una alarma…" no es una ficha.
            if (!saysCard && (kindWord.isBlank() || word !in setOf("nuevo", "nueva", "crea", "abre", "añade", "anade"))) return@let
            val name = m.groupValues[2].trim()
            if (name.length < 2 || name.split(' ').size > 5) return@let
            val (f, fresh) = cards.upsert(name, kindWord, m.groupValues[3])
            return if (fresh) "Hecho, ficha de ${f.name} creada${if (f.summary.isBlank()) ". Cuéntame de qué va y lo apunto." else "."}"
            else "Ya tenía la ficha de ${f.name}; la he completado."
        }
        ask.find(t)?.let { m ->
            val rest = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@let
            val f = cards.detect(rest, max = 1).firstOrNull() ?: return@let
            // Solo si la pregunta es por la ficha en general; lo concreto lo contesta el modelo con su detalle.
            val extra = Spoken.words(rest).size - Spoken.words(f.name).size
            if (extra > 2) return@let
            cards.touch(f.id)
            return cards.spoken(f)
        }
        return null
    }
}

/** Herramientas para que el modelo consulte y complete las fichas. */
class CardTools(context: Context) : ToolSet {

    private val context = context.applicationContext
    private val cards get() = Cards.get(context)

    @Tool(description = "Lee la ficha completa de un proyecto, persona o lugar con todo su detalle guardado. Úsala si te preguntan algo concreto que no está en lo que ya te pasé.")
    fun leerFicha(
        @ToolParam(description = "Nombre del proyecto, persona o lugar") nombre: String,
        @ToolParam(description = "Lo que se quiere saber, para traer primero lo más parecido") pregunta: String,
    ): String {
        if (locked()) return LOCKED
        val f = cards.named(nombre) ?: cards.detect(nombre, 1).firstOrNull()
            ?: return "No tengo ficha de $nombre. Fichas que tengo: " + cards.ranked().take(15).joinToString { it.name }.ifEmpty { "ninguna" }
        cards.touch(f.id)
        val d = cards.details(f, pregunta, k = 12)
        return "${f.name} (${f.kind}): ${f.summary.ifBlank { "sin resumen" }}\n" +
            d.all.joinToString("\n") { "- $it" }.ifEmpty { "Sin más detalle guardado." }
    }

    @Tool(description = "Apunta un detalle en la ficha de un proyecto, persona o lugar (la crea si no existe). Úsala cuando Maurizio te cuente algo que deba quedar guardado de ese tema.")
    fun apuntarEnFicha(
        @ToolParam(description = "Nombre del proyecto, persona o lugar") nombre: String,
        @ToolParam(description = "proyecto, persona, lugar, empresa o tema") tipo: String,
        @ToolParam(description = "El detalle, en una frase completa") detalle: String,
    ): String {
        if (locked()) return LOCKED
        if (detalle.isBlank()) return "No hay nada que apuntar."
        val f = cards.upsert(nombre, tipo).first
        Recall.get(context).add("${f.name}: ${detalle.trim()}", kind = "dato", tier = 1, source = "ficha")
        return "Apuntado en la ficha de ${f.name}."
    }

    private fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private companion object {
        const val LOCKED = "Para eso hay que desbloquear el teléfono."
    }
}
