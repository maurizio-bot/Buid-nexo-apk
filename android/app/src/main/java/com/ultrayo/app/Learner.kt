package com.ultrayo.app

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Ultra Yo aprende sola de lo que habla con Maurizio (nunca toca nada del teléfono para ello):
 * - Correcciones: si él dice "así no", "te equivocas", "prefiero que…", lo apunta al momento como regla.
 * - Al terminar una conversación (un rato sin hablar o "chat nuevo"), saca los datos duraderos
 *   (preferencias, decisiones, proyectos) y los guarda en su memoria por capas ([Recall]).
 * - Investigar un tema en segundo plano, solo cuando él se lo encarga.
 */
object Learner {

    /**
     * Silencio antes de repasar la conversación. El repaso usa el modelo y cierra la conversación en curso,
     * así que la siguiente pregunta tiene que volver a leer todas las instrucciones y herramientas (varios
     * segundos). Con 90 s pasaba casi siempre entre pregunta y pregunta: ahora espera 10 minutos.
     */
    private const val IDLE_MS = 10 * 60_000L
    private const val MAX_TURNS = 14

    private val main = Handler(Looper.getMainLooper())
    private val scope = Safe.mainScope("aprendizaje")
    private lateinit var app: Context

    /** Por dónde va el chat ya revisado. */
    private var reviewed = 0

    private val correction = Regex(
        "^(?:no[,.]? )?(?:as[ií] no|eso no|no es as[ií]|te equivocas|est[aá]s equivocad[ao]|eso est[aá] mal|est[aá] mal|no me gusta que|" +
            "no quiero que|no vuelvas a|deja de|nunca me|prefiero que|prefiero|mejor (?:hazlo|dilo|contesta)|la pr[oó]xima vez|" +
            "no hagas|no digas|no me hables|h[aá]blame m[aá]s|s[eé] m[aá]s|no seas)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val research = Regex(
        "^(?:investiga|estudia|inf[oó]rmate|aprende)(?: a fondo| bien| todo)?(?: sobre| de| acerca de| lo de)?\\s+(.+?)(?:,? y (?:apr[eé]ndelo|gu[aá]rdalo|recu[eé]rdalo|me cuentas|av[ií]same))?[.!?]*$",
        RegexOption.IGNORE_CASE,
    )

    fun init(context: Context) {
        val first = !::app.isInitialized
        app = context.applicationContext
        if (first) main.postDelayed(nightCheck, NIGHT_CHECK_MS)
    }

    // --- Repaso nocturno ("dormir") ---

    private const val NIGHT_CHECK_MS = 20 * 60_000L
    private const val MAX_NIGHT_CARDS = 12

    private val nightCheck = object : Runnable {
        override fun run() {
            runCatching { if (shouldSleep()) consolidate() }
            main.postDelayed(this, NIGHT_CHECK_MS)
        }
    }

    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date())

    /** De madrugada, cargando, sin estar hablando y una sola vez por noche. */
    private fun shouldSleep(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour !in 1..5 || !recall.learning) return false
        if (Assistant.thinking || Assistant.speaking || Assistant.listening || Ai.brain == null) return false
        val charging = app.getSystemService(BatteryManager::class.java)?.isCharging == true
        val prefs = app.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)
        return charging && prefs.getString("ultimo_repaso", "") != today()
    }

    /**
     * Como cuando un humano duerme: repasa lo que quedó de la conversación, rehace el resumen de las
     * fichas que han cambiado con todo lo que sabe de ellas y ordena la memoria (fusiona y olvida).
     * También se puede lanzar a mano desde Ajustes. [done] recibe cuántas fichas repasó.
     */
    fun consolidate(done: ((Int) -> Unit)? = null) {
        app.getSharedPreferences("ultrayo", Context.MODE_PRIVATE).edit().putString("ultimo_repaso", today()).apply()
        review()
        val brain = Ai.brain ?: return run { done?.invoke(0) }
        val cards = Cards.get(app)
        val since = app.getSharedPreferences("ultrayo", Context.MODE_PRIVATE).getLong("repaso_desde", 0L)
        val pending = cards.ranked().filter { it.used >= since || it.summary.isBlank() }.take(MAX_NIGHT_CARDS)
        scope.launch {
            var updated = 0
            for (f in pending) {
                if (Assistant.thinking || Assistant.speaking || Assistant.listening) break
                val facts = cards.details(f, f.name, k = 15).all
                if (facts.isEmpty()) continue
                val summary = runCatching {
                    withContext(Ai.thread) {
                        brain.answerOnce(
                            "Resume en una sola frase de menos de 25 palabras qué es ${f.name} y lo más importante de su estado " +
                                "actual, usando solo estas notas. Sin introducción, solo la frase.",
                            facts.joinToString("\n") { "- $it" },
                        )
                    }
                }.getOrNull()?.lines()?.firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '-', ' ') ?: continue
                if (summary.length in 10..300) {
                    cards.setSummary(f.id, summary)
                    updated++
                }
            }
            withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { recall.maintain() } }
            app.getSharedPreferences("ultrayo", Context.MODE_PRIVATE).edit().putLong("repaso_desde", System.currentTimeMillis()).apply()
            Assistant.brainDirty = true
            done?.invoke(updated)
        }
    }

    private val recall get() = Recall.get(app)

    /** Si [text] corrige a Ultra, la apunta como regla. Devuelve el aviso que se enseña, o null. */
    fun maybeCorrection(text: String, turns: List<Turn>): String? {
        if (!recall.learning || !correction.containsMatchIn(text.trim())) return null
        val previous = turns.lastOrNull { it.role == "assistant" }?.content?.take(120)
        val rule = text.trim().trimEnd('.', '!') + (previous?.let { " (lo dijo cuando respondiste: \"$it\")" } ?: "")
        recall.add("Corrección de Maurizio: $rule", kind = "regla", tier = 2, source = "corrección")
        Assistant.brainDirty = true
        return "📝 Apuntado para la próxima vez."
    }

    /** El tema, si Maurizio encarga investigar algo en segundo plano. */
    fun researchTopic(text: String): String? {
        if (Regex("^aprende\\s+que\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())) return null
        return research.find(text.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.length > 2 }
    }

    /** Tras cada respuesta: si luego hay un rato de silencio, revisa la conversación. */
    fun afterReply() {
        main.removeCallbacks(idleReview)
        if (recall.learning) main.postDelayed(idleReview, IDLE_MS)
    }

    /** Antes de contestar: no molestar al modelo mientras se habla. */
    fun beforeAsk() = main.removeCallbacks(idleReview)

    private val idleReview = Runnable { review() }

    /** Saca de la conversación lo que merece recordarse. Se llama al quedarse en silencio o al empezar chat nuevo. */
    fun review(turns: List<Turn> = Assistant.turns.toList()) {
        if (!recall.learning) return
        if (reviewed > turns.size) reviewed = 0
        val fresh = turns.drop(reviewed).filter { it.content.isNotBlank() }.takeLast(MAX_TURNS)
        reviewed = turns.size
        if (fresh.count { it.role == "user" } < 2) return
        val brain = Ai.brain ?: return
        if (Assistant.thinking || Assistant.speaking) return
        val dialog = fresh.joinToString("\n") { (if (it.role == "user") "Maurizio: " else "Ultra: ") + it.content.take(400) }
        scope.launch {
            val notes = runCatching {
                withContext(Ai.thread) {
                    brain.answerOnce(
                        "Lee esta conversación entre Maurizio y su asistente Ultra. Escribe solo datos duraderos y útiles " +
                            "sobre Maurizio para el futuro: sus preferencias, cómo quiere que hagas las cosas, decisiones que tomó, " +
                            "sus proyectos y datos personales que él mismo dijo. Una idea por línea, empezando por \"- \", en " +
                            "tercera persona y en menos de 25 palabras. Nada de lo que solo importaba en esta conversación, " +
                            "nada inventado ni deducido. Si un dato es de un proyecto, persona o lugar concreto, empieza la línea " +
                            "por su nombre y dos puntos (por ejemplo \"- Proyecto1: usa una base de datos SQLite\"). Además, por " +
                            "cada proyecto, persona o lugar del que hablen, añade una línea así: FICHA: nombre | tipo (proyecto, " +
                            "persona, lugar o empresa) | qué es, en menos de 20 palabras. Si no hay nada que valga la pena, escribe solo: NADA",
                        dialog,
                    )
                }
            }.getOrNull() ?: return@launch
            Assistant.brainDirty = true
            var learned = 0
            val cards = Cards.get(app)
            notes.lines().map { it.trim().trimStart('-', '•', ' ') }.filter { it.startsWith("FICHA:", ignoreCase = true) }.forEach { line ->
                val parts = line.substringAfter(':').split('|').map { it.trim() }
                val name = parts.getOrNull(0).orEmpty()
                if (name.length in 2..40 && name.split(' ').size <= 5 && cards.upsert(name, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" }).second) learned++
            }
            notes.lines().map { it.trim() }.filter { it.startsWith("-") || it.startsWith("•") }
                .filterNot { it.trimStart('-', '•', ' ').startsWith("FICHA:", ignoreCase = true) }.forEach { line ->
                val fact = line.trimStart('-', '•', ' ').trim()
                if (fact.length !in 8..220 || fact.equals("NADA", ignoreCase = true)) return@forEach
                val preference = Regex("prefiere|le gusta|no le gusta|quiere que|no quiere|odia|siempre|nunca", RegexOption.IGNORE_CASE).containsMatchIn(fact)
                if (recall.add(fact, kind = if (preference) "preferencia" else "dato", tier = if (preference) 2 else 1, source = "conversación")) learned++
            }
            if (learned > 0) Assistant.status = "Aprendí $learned cosa${if (learned == 1) "" else "s"} de esta conversación."
        }
    }

    /**
     * Investiga [topic] en segundo plano: lee las mejores páginas, pide al modelo unas notas con
     * su fuente y las guarda. Solo se usa cuando Maurizio lo encarga. [done] recibe lo que decir.
     */
    fun research(topic: String, done: (String) -> Unit) {
        scope.launch {
            val pages = runCatching { withContext(kotlinx.coroutines.Dispatchers.IO) { WebSearch.readTop(topic, pages = 5, perPage = 1500) } }.getOrDefault("")
            if (pages.isBlank()) return@launch done("No pude investigar \"$topic\": no encontré nada o no hay conexión.")
            val brain = Ai.brain ?: return@launch done("Todavía me estoy despertando; pídemelo en un momento.")
            val notes = runCatching {
                withContext(Ai.thread) {
                    brain.answerOnce(
                        "Con estos fragmentos de internet escribe entre 4 y 8 notas sobre el tema, una por línea, empezando " +
                            "por \"- \", cada una de menos de 35 palabras y terminando con la web de donde sale entre corchetes. " +
                            "Solo lo que digan los fragmentos; si se contradicen, dilo.",
                        "Tema: $topic\n\n$pages",
                    )
                }
            }.getOrElse { return@launch done("No pude resumir lo que encontré sobre \"$topic\": ${it.message}") }
            Assistant.brainDirty = true
            val lines = notes.lines().map { it.trim() }.filter { it.startsWith("-") }.map { it.trimStart('-', ' ') }
            lines.forEach { recall.add("Sobre $topic: $it", kind = "conocimiento", tier = 1, source = "investigación") }
            done(
                if (lines.isEmpty()) "Leí sobre \"$topic\" pero no saqué nada claro. Pregúntame algo concreto y lo busco."
                else "Terminé de investigar \"$topic\" y guardé ${lines.size} notas. Lo principal: ${lines.first()}"
            )
        }
    }
}
