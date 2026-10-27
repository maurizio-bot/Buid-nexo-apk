package com.ultrayo.app

/** Órdenes para enseñarle cosas a la IA: "recuerda que…", "olvida…", "¿qué sabes de mí?". */
object Teaching {

    private val remember = Regex("^(recuerda|apunta|aprende)( que)?[,:]?\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val forgetAll = Regex("^olvida todo\\b", RegexOption.IGNORE_CASE)
    private val forget = Regex("^olvida( que| lo de)?[,:]?\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val whatYouKnow = Regex("qu[eé] (sabes|recuerdas) (de|sobre) m[ií]|qu[eé] te he enseñado", RegexOption.IGNORE_CASE)

    /** Devuelve la respuesta si [text] era una orden de memoria, o null si es una pregunta normal. */
    fun handle(text: String, memory: Memory): String? {
        val t = text.trim().trimEnd('.', '!', '?')

        remember.find(t)?.let { match ->
            memory.add(match.groupValues[3].trim().replaceFirstChar { it.uppercase() })
            return "Entendido, lo recordaré."
        }
        if (forgetAll.containsMatchIn(t)) {
            memory.clear()
            return "Hecho. He olvidado todo lo que me enseñaste."
        }
        forget.find(t)?.let { match ->
            return if (memory.forget(match.groupValues[2]) > 0) "Hecho, lo he olvidado."
            else "No encontré nada sobre eso en mi memoria."
        }
        if (whatYouKnow.containsMatchIn(t)) {
            val facts = memory.facts()
            return if (facts.isEmpty()) "Todavía no me has enseñado nada. Dime \"recuerda que\" y lo que quieras."
            else "Esto es lo que me has enseñado: " + facts.joinToString(". ")
        }
        return null
    }
}
