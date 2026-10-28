package com.ultrayo.app

/** Órdenes para enseñarle cosas a la IA: "recuerda que…", "olvida…", "deshaz el último cambio", "¿qué sabes de mí?". */
object Teaching {

    private val remember = Regex("^(recuerda|apunta|aprende)( que)?[,:]?\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val forgetAll = Regex("^olvida todo\\b", RegexOption.IGNORE_CASE)
    private val forget = Regex("^olvida( que| lo de)?[,:]?\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val undo = Regex("^(deshaz|deshacer|anula)( el)?( último| ultimo)? cambio", RegexOption.IGNORE_CASE)
    private val whatYouKnow = Regex("qu[eé] (sabes|recuerdas) (de|sobre) m[ií]|qu[eé] te he enseñado", RegexOption.IGNORE_CASE)

    /** Devuelve la respuesta si [text] era una orden de memoria, o null si es una pregunta normal. */
    fun handle(text: String, memory: Memory): String? {
        val t = text.trim().trimEnd('.', '!', '?')

        if (undo.containsMatchIn(t)) {
            return if (memory.undo()) "Hecho, he vuelto a como estaba antes del último cambio."
            else "No hay ningún cambio que deshacer."
        }
        remember.find(t)?.let { match ->
            memory.snapshot()
            memory.add(match.groupValues[3].trim().replaceFirstChar { it.uppercase() })
            return "Entendido, lo recordaré."
        }
        if (forgetAll.containsMatchIn(t)) {
            memory.snapshot()
            memory.clear()
            return "Hecho. He olvidado todo lo que me enseñaste."
        }
        forget.find(t)?.let { match ->
            memory.snapshot()
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
