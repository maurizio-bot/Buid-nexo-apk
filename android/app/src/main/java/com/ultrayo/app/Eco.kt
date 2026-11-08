package com.ultrayo.app

import java.text.Normalizer

/**
 * Distingue a Maurizio del eco de la propia voz de Ultra mientras ella habla (para poder interrumpirla).
 *
 * Antes bastaban 2 palabras "nuevas" en todo lo oído para creer que era Maurizio. Pero el oído transcribe
 * mal la voz de Ultra por el altavoz ("contestando" → "con testa"), y en una respuesta larga siempre salían
 * dos palabras raras: Ultra se cortaba sola y se contestaba a sí misma. Ahora:
 * - una palabra cuenta como eco aunque esté mal oída (una o dos letras de diferencia, o un trozo de una dicha);
 * - solo se mira lo último oído (las 5 últimas palabras), no todo lo acumulado;
 * - hacen falta 3 palabras nuevas seguidas por ahí, o una orden corta de parar ("para", "espera"…);
 * - lo que se entrega como pregunta empieza donde empieza Maurizio, sin el eco de delante.
 */
object Eco {

    private const val WINDOW = 5
    private const val NEEDED = 3

    private val stopWords = setOf("para", "calla", "callate", "basta", "silencio", "stop", "espera", "ultra")

    fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .split(Regex("[^a-z0-9ñ]+"))
            .filter { it.isNotEmpty() }

    /** [word] (ya normalizada) es algo que acaba de decir Ultra, aunque el oído lo haya escrito un poco mal. */
    fun isEchoWord(word: String, said: Set<String>): Boolean {
        if (word.length < 3 || word in said) return true
        return said.any { s ->
            when {
                s.length >= 4 && word.length >= 3 && (s.startsWith(word) || s.endsWith(word)) -> true
                word.length >= 4 && s.length >= 3 && (word.startsWith(s) && word.length - s.length <= 3) -> true
                kotlin.math.abs(s.length - word.length) > 2 -> false
                else -> distance(word, s) <= if (word.length >= 7) 2 else 1
            }
        }
    }

    /** Lo oído es eco de Ultra (no hay que callarla). */
    fun isEcho(heard: String, said: Set<String>): Boolean {
        val all = words(heard)
        if (all.isEmpty()) return true
        // "Para", "espera", "Ultra" dichos solos o casi: es Maurizio, salvo que Ultra acabe de decir esa palabra.
        if (all.size <= 3 && all.first() in stopWords && all.first() !in said) return false
        val window = all.takeLast(WINDOW)
        return window.count { !isEchoWord(it, said) } < NEEDED
    }

    /**
     * Quita del principio de [heard] lo que era eco de Ultra, para quedarse con lo que dijo Maurizio.
     * Si todo era eco, devuelve "".
     */
    fun stripEcho(heard: String, said: Set<String>): String {
        val tokens = heard.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val norm = tokens.map { words(it).joinToString("") }
        val first = norm.indices.firstOrNull { i -> norm[i].length >= 3 && !isEchoWord(norm[i], said) } ?: return ""
        // Una palabra corta justo delante ("no", "y", "a") suele ser ya de Maurizio.
        val start = if (first > 0 && norm[first - 1].length in 1..2) first - 1 else first
        return tokens.drop(start).joinToString(" ").replaceFirstChar { it.uppercase() }
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
