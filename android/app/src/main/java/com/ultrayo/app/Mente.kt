package com.ultrayo.app

/**
 * Lo que Maurizio le pide a Ultra sobre sí misma: analizarse, enseñar o cambiar su personalidad y sus
 * reglas. Se entiende con código (sin modelo) para que nunca conteste "soy un modelo de lenguaje, no
 * puedo cambiarme". Ningún cambio se aplica solo: todo sale en la tarjeta Aplicar/No y se puede deshacer.
 */
object Mente {

    sealed interface Pedido {
        /** "¿Puedes analizarte / cambiar tu personalidad?" */
        data object Capacidad : Pedido

        /** "¿Cuál es tu personalidad?", "dime tus reglas". */
        data object Ver : Pedido

        /** "Analízate", "¿qué mejorarías de ti?". */
        data object Analizar : Pedido

        /** "Cambia tu personalidad para que…", "sé más directa", "quiero que me hables de usted". */
        data class Cambiar(val peticion: String) : Pedido

        /** "Aplica esos cambios" justo después de analizarse. */
        data object AplicarAnalisis : Pedido

        /** "Añade la regla: …", "a partir de ahora …". */
        data class NuevaRegla(val regla: String) : Pedido

        /** "Quita la regla de …". */
        data class QuitarRegla(val regla: String) : Pedido
    }

    private const val SELF = "(?:personalidad|forma de ser|manera de ser|caracter|forma de hablar|manera de hablar|" +
        "forma de responder|manera de responder|forma de contestar|comportamiento|tono|reglas|normas|mente|instrucciones)"

    private val capacidad = Regex(
        "\\b(?:puedes|podrias|eres capaz de|sabes|tienes capacidad de|es posible) (?:tu )?" +
            "(?:auto ?)?(?:analizar|evaluar|criticar|examinar|cambiar|modificar|mejorar|reprogramar|editar|reescribir|corregir|revisar|ajustar)" +
            "(?:te(?: a ti mism[oa])?|(?: a)? ti mism[oa]| tus? (?:propia |propio |propias )?$SELF)\\b"
    )
    private val ver = Regex(
        "^(?:(?:muestrame|ensename|dime|lee(?:me)?|recitame|cuentame) (?:cual es |cuales son |como es |como son )?|" +
            "(?:cual es|cuales son|como es|como son|que dice|que dicen) )(?:tu|tus) (?:personalidad|reglas|normas|forma de ser)$"
    )
    private val analizar = Regex(
        "\\b(?:auto ?anali[sz]ate|anali[sz]ate|auto ?evaluate|evaluate|revisate|examinate|criticate|" +
            "haz(?:te)? (?:un |una )?(?:auto ?analisis|analisis de ti|autoevaluacion|autocritica|evaluacion de ti)|" +
            "(?:analiza|evalua|revisa|examina|critica) (?:tu|tus) $SELF|" +
            "que (?:mejorarias|cambiarias|harias mejor) (?:de|en) ti|en que (?:fallas|puedes mejorar|deberias mejorar)|" +
            "que (?:haces mal|puedes mejorar|deberias mejorar|deberias cambiar))\\b"
    )
    private val aplicar = Regex(
        "^(?:si,? )?(?:aplica(?:lo|los)?|hazlo|cambialo|cambiate(?: asi)?|mejorate|aplica (?:esos|los|estos|tus) cambios|" +
            "haz (?:esos|los|estos) cambios|aplica(?:te)? (?:esas|las|tus) (?:mejoras|propuestas)|adelante,? cambiate)$"
    )
    private val cambiar = Regex(
        "\\b(?:cambia|cambiate|modifica|reescribe|ajusta|mejora|edita|actualiza|corrige|reprograma)(?:te)? (?:tu|tus) $SELF\\b"
    )
    private val quiero = Regex(
        "^(?:quiero|me gustaria|prefiero|necesito) que (?:seas|hables|me hables|me trates|contestes|me contestes|respondas|me respondas|te comportes|actues|tengas|uses|no seas|no hables|no me hables)\\b"
    )
    private val se = Regex(
        "^(?:se|se un poco|intenta ser|procura ser|deja de ser) (?:mas|menos|muy) \\w+|" +
            "^(?:habla(?:me)?|contesta(?:me)?|responde(?:me)?) (?:mas|menos|de forma|de manera|en tono|de usted|de tu|con (?:mas|menos) )"
    )
    private val nuevaRegla = Regex(
        "^(?:anade|agrega|ponte|crea|apunta|pon|anadete|agregate|create|apuntate) (?:una |la |esta |otra )?(?:nueva )?(?:regla|norma)(?: nueva)?(?: de| que diga| que dice)?[:,]?\\s+(.+)$"
    )
    private val desdeAhora = Regex("^(?:a partir de ahora|desde ahora|de ahora en adelante)[,:]?\\s+(.+)$")
    private val quitarRegla = Regex(
        "^(?:quita|quitate|borra|borrate|elimina|eliminate|olvida) (?:la |esa |tu )?(?:regla|norma)(?: de| que dice| que dice que)?[:,]?\\s+(.+)$"
    )

    /** Lo dicho en minúsculas, sin acentos ni ¿¡, para que valga escrito o dictado. */
    fun clean(text: String): String = text.lowercase().trim()
        .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u").replace("ü", "u")
        .replace("ñ", "n").replace(Regex("[¿¡]"), "").trim().trimEnd('.', '!', '?', ',', ' ')

    /** Qué pide sobre sí misma, o null si no habla de eso. [justAnalyzed] activa "aplica esos cambios". */
    fun entender(text: String, justAnalyzed: Boolean = false): Pedido? {
        val original = text.trim().trimEnd('.', '!', '?', ' ')
        val t = clean(text)
        if (t.isEmpty()) return null
        if (justAnalyzed && aplicar.matches(t)) return Pedido.AplicarAnalisis
        quitarRegla.find(t)?.let { return Pedido.QuitarRegla(it.groupValues[1].trim()) }
        nuevaRegla.find(t)?.let { return Pedido.NuevaRegla(cut(original, t, it.groups[1]!!.range.first).replaceFirstChar { c -> c.uppercase() }) }
        desdeAhora.find(t)?.let { m ->
            // "a partir de ahora contesta por escrito" ya lo atiende el modo texto antes.
            return Pedido.NuevaRegla(cut(original, t, m.groups[1]!!.range.first).replaceFirstChar { it.uppercase() })
        }
        if (ver.matches(t)) return Pedido.Ver
        capacidad.find(t)?.let { m ->
            // "¿Puedes cambiar tu personalidad para que seas más directa?" ya es un cambio, no una pregunta.
            val rest = t.substring(m.range.last + 1).trim()
            return if (rest.split(' ').count { it.isNotBlank() } >= 3) Pedido.Cambiar(original) else Pedido.Capacidad
        }
        if (analizar.containsMatchIn(t)) return Pedido.Analizar
        if (cambiar.containsMatchIn(t) || quiero.containsMatchIn(t) || se.containsMatchIn(t)) return Pedido.Cambiar(original)
        return null
    }

    /** Recorta [original] desde la posición que corresponde en su versión limpia (mismo largo salvo ¿¡). */
    private fun cut(original: String, cleaned: String, from: Int): String {
        val o = original.replace(Regex("[¿¡]"), "").trim().trimEnd('.', '!', '?', ',', ' ')
        return if (o.length == cleaned.length) o.substring(from).trim() else cleaned.substring(from).trim()
    }

    /** Las reglas como lista, una por línea, sin guiones. */
    fun lines(rules: String): List<String> = rules.lines().map { it.trim().trimStart('-', '•', '*', ' ').trim() }.filter { it.isNotBlank() }

    fun join(lines: List<String>): String = lines.joinToString("\n") { "- $it" }

    /** La regla de [rules] que más se parece a [wanted], o null. */
    fun findRule(rules: String, wanted: String): String? {
        val list = lines(rules)
        val w = clean(wanted)
        list.firstOrNull { clean(it) == w }?.let { return it }
        list.firstOrNull { clean(it).contains(w) }?.let { return it }
        val words = w.split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        if (words.isEmpty()) return null
        return list.map { it to words.count { word -> clean(it).contains(word) } }
            .filter { it.second * 2 >= words.size && it.second > 0 }
            .maxByOrNull { it.second }?.first
    }

    /** Instrucción para que el modelo reescriba su personalidad y sus reglas según lo que pide Maurizio. */
    fun rewriteInstruction(): String =
        "Eres el editor de la personalidad de Ultra Yo, el asistente de Maurizio. Te paso su personalidad actual, sus " +
            "reglas actuales y lo que pide Maurizio. Reescríbelas aplicando exactamente lo que pide, sin perder lo que " +
            "no toca cambiar, sin inventar cosas que no ha pedido y en español. Contesta SOLO con este formato, sin nada más:\n" +
            "PERSONALIDAD:\n(la personalidad completa, en segunda persona: \"Eres…\")\n" +
            "REGLAS:\n(una regla por línea, empezando por \"- \"; si no hay ninguna, escribe \"- (ninguna)\")"

    fun rewriteInput(personality: String, rules: String, request: String): String =
        "PERSONALIDAD ACTUAL:\n$personality\n\nREGLAS ACTUALES:\n${rules.ifBlank { "(ninguna)" }}\n\nLO QUE PIDE MAURIZIO:\n$request"

    /** Lo que devolvió el modelo, separado en personalidad y reglas. Null si no vale. */
    fun parseRewrite(reply: String, currentRules: String): Pair<String, String>? {
        val text = Thinking.strip(reply).replace("**", "").replace(Regex("```\\w*"), "").trim()
        val personalityAt = Regex("PERSONALIDAD\\s*:", RegexOption.IGNORE_CASE).find(text)
        val rulesAt = Regex("REGLAS\\s*:", RegexOption.IGNORE_CASE).find(text)
        val personality = when {
            personalityAt != null && rulesAt != null && rulesAt.range.first > personalityAt.range.last ->
                text.substring(personalityAt.range.last + 1, rulesAt.range.first)
            personalityAt != null -> text.substring(personalityAt.range.last + 1)
            rulesAt != null -> text.substring(0, rulesAt.range.first)
            else -> text
        }.trim()
        val rules = if (rulesAt != null) {
            join(lines(text.substring(rulesAt.range.last + 1)).filterNot { clean(it).trim('(', ')') == "ninguna" })
        } else {
            currentRules
        }
        if (personality.length < 20) return null
        return personality to rules
    }

    /** Pide al modelo que se analice con su personalidad, sus reglas y lo que ha pasado en la conversación. */
    fun analysisPrompt(personality: String, rules: String, request: String, errors: String): String = buildString {
        append("Maurizio te pide que te analices: \"").append(request).append("\".\n\n")
        append("Esta es tu personalidad ahora (es texto que tú puedes cambiar):\n").append(personality).append("\n\n")
        append("Tus reglas ahora:\n").append(rules.ifBlank { "(ninguna)" }).append("\n\n")
        if (errors.isNotBlank()) append("Tus últimos fallos registrados:\n").append(errors).append("\n\n")
        append(
            "Mira también cómo has contestado en esta conversación. Analízate con honestidad, sin excusas ni halagos: " +
                "di qué haces bien, qué haces mal o te falta (por ejemplo respuestas largas, inventar, no usar herramientas, " +
                "no admitir que no sabes), y propone dos o tres cambios concretos a tu personalidad o a tus reglas. " +
                "Termina diciendo que si le parecen bien diga \"aplica esos cambios\" y te los deja en pantalla para aceptarlos. " +
                "No digas que no puedes analizarte ni cambiarte: sí puedes."
        )
    }
}
