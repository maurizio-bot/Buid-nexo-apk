package com.ultrayo.app

/**
 * "Pensar antes de contestar", solo en lo difícil. Las órdenes y la charla corta van directas;
 * en una pregunta compleja se le pide al modelo que razone primero dentro de <pensar>…</pensar>.
 * Ese razonamiento no se enseña ni se lee en voz alta: solo la respuesta final.
 */
object Thinking {

    private const val OPEN = "<pensar>"
    private const val CLOSE = "</pensar>"

    private val hardHint = Regex(
        "\\bpor ?qu[eé]\\b|expl[ií]c|compar|analiz|planific|\\bplan\\b|diseñ|estrategia|ventajas|desventajas|paso a paso|" +
            "c[oó]mo (?:puedo|podr[ií]a|hago|har[ií]as?|funciona|se hace)|qu[eé] (?:me )?recomiendas|cu[aá]l es mejor|resuelve|" +
            "demuestra|optimiz|depura|arregl|\\berror\\b|\\bbug\\b|arquitectura|algoritmo|programa|c[oó]digo|ecuaci[oó]n|" +
            "problema|decid|piensa|razona|reflexiona|eval[uú]a|pros y contras",
        RegexOption.IGNORE_CASE,
    )

    /** Si merece pensar antes: pide pensar, o es una pregunta de las que llevan razonamiento, o es larga. */
    fun isHard(text: String): Boolean {
        val t = text.trim()
        val words = t.split(Regex("\\s+")).size
        if (Regex("^(piensa|razona|reflexiona)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        return (words >= 7 && hardHint.containsMatchIn(t)) || words >= 35
    }

    fun wrap(prompt: String) =
        "[Esta pregunta es difícil. Antes de contestar, razona en privado dentro de $OPEN … $CLOSE: entiende qué pide, " +
            "haz un plan, comprueba datos y cuentas con tus herramientas (calcular, buscarWeb, buscarEnDocumentos) si hace " +
            "falta y busca fallos en tu razonamiento. Si no estás seguro de algo, reconócelo. Después de $CLOSE escribe solo " +
            "la respuesta final, clara y directa, sin repetir el razonamiento.]\n\n$prompt"

    /** Quita el razonamiento de la respuesta. Si no quedó respuesta fuera de él, devuelve el razonamiento limpio. */
    fun strip(reply: String): String {
        if (!reply.contains(OPEN)) return reply.replace(CLOSE, "").trim()
        val visible = reply.replace(Regex("$OPEN[\\s\\S]*?(?:$CLOSE|$)"), "").replace(CLOSE, "").trim()
        if (visible.isNotEmpty()) return visible
        return reply.replace(OPEN, "").replace(CLOSE, "").trim()
    }

    /** Va filtrando los trozos que llegan del modelo: deja pasar solo lo que está fuera de <pensar>. */
    class Filter {
        private val pending = StringBuilder()
        private var inside = false

        fun feed(delta: String): String {
            pending.append(delta)
            val out = StringBuilder()
            while (true) {
                if (!inside) {
                    val i = pending.indexOf(OPEN)
                    if (i >= 0) {
                        out.append(pending, 0, i)
                        pending.delete(0, i + OPEN.length)
                        inside = true
                        continue
                    }
                    // Guarda un posible trozo de etiqueta a medias ("<pen") hasta el siguiente trozo.
                    val keep = (OPEN.length - 1 downTo 1).firstOrNull { pending.endsWith(OPEN.substring(0, it)) } ?: 0
                    out.append(pending, 0, pending.length - keep)
                    pending.delete(0, pending.length - keep)
                    break
                } else {
                    val i = pending.indexOf(CLOSE)
                    if (i >= 0) {
                        pending.delete(0, i + CLOSE.length)
                        inside = false
                        // Quita los saltos de línea que siguen al razonamiento.
                        while (pending.isNotEmpty() && pending[0].isWhitespace()) pending.deleteCharAt(0)
                        continue
                    }
                    if (pending.length > CLOSE.length) pending.delete(0, pending.length - CLOSE.length)
                    break
                }
            }
            return out.toString()
        }
    }
}
