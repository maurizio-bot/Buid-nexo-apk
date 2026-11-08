package com.ultrayo.app

import java.text.Normalizer
import java.util.Calendar

/**
 * La charla de siempre ("hola", "¿cómo estás?", "buenos días, Ultra", "¿estás ahí?"): se contesta al
 * instante, sin despertar al modelo, sin buscar en la memoria ni en las fichas y sin pensar.
 * Solo si la frase entera es charla: "hola, ¿qué tiempo hace?" sigue su camino normal.
 */
object Charla {

    private enum class Tipo { SALUDO, COMO_ESTAS, ESTOY_BIEN, ESTOY_MAL, ESTAS_AHI, QUIEN_ERES, VALE, RISA }

    /** Frases de charla (ya sin tildes, en minúsculas y sin signos), de la más larga a la más corta. */
    private val frases: List<Pair<String, Tipo>> = listOf(
        Tipo.SALUDO to listOf(
            "buenos dias", "buenas tardes", "buenas noches", "muy buenas", "buen dia", "buenas", "hola", "holi", "hey", "ey",
            "que pasa", "que hay", "que onda", "saludos",
        ),
        Tipo.COMO_ESTAS to listOf(
            "como estas", "como estas hoy", "como te encuentras", "como te va", "como vas", "como andas", "como lo llevas",
            "como va todo", "como va eso", "como va", "que tal estas", "que tal te va", "que tal vas", "que tal el dia",
            "que tal", "todo bien", "estas bien", "que cuentas", "que me cuentas", "y tu", "y tu que tal", "y tu como estas",
        ),
        Tipo.ESTOY_BIEN to listOf(
            "estoy bien", "estoy muy bien", "muy bien", "bien", "genial", "estupendo", "de lujo", "todo bien por aqui",
            "aqui estoy", "aqui ando", "tirando", "bien gracias", "muy bien gracias",
        ),
        Tipo.ESTOY_MAL to listOf("estoy mal", "estoy cansado", "estoy cansada", "fatal", "regular", "mal", "no muy bien", "un poco cansado"),
        Tipo.ESTAS_AHI to listOf("estas ahi", "estas por ahi", "me oyes", "me escuchas", "sigues ahi", "estas despierta", "estas despierto"),
        Tipo.QUIEN_ERES to listOf("quien eres", "como te llamas", "que eres", "cual es tu nombre"),
        Tipo.VALE to listOf("vale", "ok", "okay", "de acuerdo", "perfecto", "entendido", "guay", "genial gracias", "estupendo gracias"),
        Tipo.RISA to listOf("jaja", "jajaja", "jajajaja", "jeje", "jejeje", "lol", "xd"),
    ).flatMap { (tipo, lista) -> lista.map { it to tipo } }.sortedByDescending { it.first.length }

    /** Palabras de relleno que pueden acompañar a la charla sin cambiar nada. */
    private val relleno = setOf("oye", "eh", "pues", "y", "tu", "que", "ultra", "yo", "hoy", "amigo", "crack", "jefe", "por", "aqui", "gracias", "ahora", "mira", "bueno", "venga", "oiga", "entonces", "maurizio", "a", "ver", "dime")

    private fun normal(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-zñ0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Qué tipos de charla hay en [said], o null si la frase lleva algo más (una pregunta de verdad, una orden…). */
    private fun tipos(said: String): Set<Tipo>? {
        var t = " " + normal(said) + " "
        if (t.isBlank() || t.length > 70) return null
        val found = linkedSetOf<Tipo>()
        for ((frase, tipo) in frases) {
            val needle = " $frase "
            while (t.contains(needle)) {
                found += tipo
                t = t.replaceFirst(needle, " ")
            }
        }
        // Lo que queda tiene que ser solo relleno: "hola ultra", "oye, ¿y tú qué tal?".
        val resto = t.split(' ').filter { it.isNotBlank() }
        if (found.isEmpty() || resto.any { it !in relleno }) return null
        return found
    }

    /** Las preguntas que hace la propia charla ("¿Y tú?"): contestarlas sigue siendo charla. */
    private val preguntaDeCharla = Regex("¿Y tú|¿Cómo estás tú|¿En qué te ayudo|¿Qué necesitas")

    /**
     * Si [said] es solo charla, la respuesta al instante; si no, null. [anterior] es lo último que dijo
     * Ultra: si preguntó algo de verdad ("¿Quieres que lo busque?"), un "vale" o un "bien" es la respuesta
     * a eso y lo tiene que ver el modelo. [hour] es la hora del día (0-23).
     */
    fun responder(said: String, anterior: String? = null, hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String? {
        val t = tipos(said) ?: return null
        val preguntoAlgo = anterior?.trim()?.endsWith("?") == true && !preguntaDeCharla.containsMatchIn(anterior)
        if (preguntoAlgo && t.none { it == Tipo.SALUDO || it == Tipo.COMO_ESTAS || it == Tipo.QUIEN_ERES || it == Tipo.ESTAS_AHI }) return null
        val partes = mutableListOf<String>()
        if (Tipo.SALUDO in t) {
            val n = normal(said)
            partes += when {
                "buenos dias" in n || "buen dia" in n -> if (hour >= 14) "Buenos días… aunque ya es por la tarde." else "Buenos días."
                "buenas tardes" in n -> "Buenas tardes."
                "buenas noches" in n -> "Buenas noches."
                else -> when (hour) {
                    in 6..13 -> listOf("Hola, buenos días.", "Hola.", "Buenas.")
                    in 14..20 -> listOf("Hola, buenas tardes.", "Hola.", "Buenas.")
                    else -> listOf("Hola, buenas noches.", "Hola.", "Buenas.")
                }.random()
            }
        }
        when {
            Tipo.QUIEN_ERES in t -> partes += "Soy Ultra, tu asistente. Funciono dentro de tu teléfono y te ayudo con tus proyectos y con el móvil."
            Tipo.COMO_ESTAS in t -> partes += listOf(
                "Bien, con todo listo. ¿Y tú?",
                "Todo en orden por aquí. ¿Y tú qué tal?",
                "Bien, gracias. ¿Cómo estás tú?",
            ).random()
            Tipo.ESTOY_MAL in t -> partes += "Vaya. Si te puedo quitar algo de encima, dímelo."
            Tipo.ESTOY_BIEN in t -> partes += listOf("Me alegro.", "Genial, me alegro.").random() + " ¿En qué te ayudo?"
            Tipo.ESTAS_AHI in t -> partes += listOf("Aquí estoy.", "Sí, te oigo.", "Aquí estoy, dime.").random()
            Tipo.SALUDO in t -> partes += listOf("¿En qué te ayudo?", "Dime.", "¿Qué necesitas?").random()
            Tipo.RISA in t -> partes += "😄"
            Tipo.VALE in t -> partes += listOf("Perfecto.", "Hecho.", "Vale.").random()
        }
        return partes.joinToString(" ").takeIf { it.isNotBlank() }
    }
}
