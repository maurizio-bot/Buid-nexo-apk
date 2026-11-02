package com.ultrayo.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ultra Yo aprende sola de lo que habla con Maurizio (nunca toca nada del teléfono para ello):
 * - Correcciones: si él dice "así no", "te equivocas", "prefiero que…", lo apunta al momento como regla.
 * - Al terminar una conversación (un rato sin hablar o "chat nuevo"), saca los datos duraderos
 *   (preferencias, decisiones, proyectos) y los guarda en su memoria por capas ([Recall]).
 * - Investigar un tema en segundo plano, solo cuando él se lo encarga.
 */
object Learner {

    private const val IDLE_MS = 90_000L
    private const val MAX_TURNS = 14

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
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
        app = context.applicationContext
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
                            "nada inventado ni deducido. Si no hay nada que valga la pena, escribe solo: NADA",
                        dialog,
                    )
                }
            }.getOrNull() ?: return@launch
            Assistant.brainDirty = true
            var learned = 0
            notes.lines().map { it.trim() }.filter { it.startsWith("-") || it.startsWith("•") }.forEach { line ->
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
