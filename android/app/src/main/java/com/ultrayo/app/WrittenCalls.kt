package com.ultrayo.app

import android.content.Context

/**
 * A veces el modelo, en vez de usar de verdad una herramienta, la escribe como texto:
 * `Proponer_habilidad(nombre="…", instrucciones="…")`. Y Maurizio puede pegar ese texto en el chat.
 * Aquí se reconocen esas llamadas escritas y se convierten en lo que querían hacer: las que
 * cambian la mente salen en la tarjeta Aplicar/No, nunca se aplican solas.
 */
object WrittenCalls {

    private val call = Regex("([A-Za-zÁÉÍÓÚáéíóúñÑ_]+)\\s*\\(((?:[^()\"]|\"(?:[^\"\\\\]|\\\\.)*\")*)\\)", RegexOption.DOT_MATCHES_ALL)
    private val argument = Regex("(\\w+)\\s*[=:]\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)

    /** Lo que se hizo con las llamadas encontradas, y el texto sin ellas. */
    data class Result(val remainingText: String, val done: List<String>)

    private val createSkill = Regex(
        "^(?:crea|creame|créame|añade|agrega|apréndete|aprende|propón|propon)(?:te|me)? (?:una |la )?habilidad (?:nueva )?(?:llamada )?[\"“']?([^\"”':]+?)[\"”']?\\s*(?::|,|con (?:estas |las )?instrucciones:?)\\s+(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** "Crea una habilidad llamada X: instrucciones" escrito o dicho por Maurizio. */
    fun skillCommand(context: Context, text: String): String? {
        val m = createSkill.find(text.trim()) ?: return null
        return perform(context, "proponerhabilidad", mapOf("nombre" to m.groupValues[1].trim(), "instrucciones" to m.groupValues[2].trim()))
    }

    /** Busca llamadas escritas en [text]. Devuelve null si no había ninguna que se pudiera usar. */
    fun handle(context: Context, text: String): Result? {
        val done = mutableListOf<String>()
        var remaining = text
        for (match in call.findAll(text)) {
            val name = normalize(match.groupValues[1])
            val args = argument.findAll(match.groupValues[2]).associate { m ->
                normalize(m.groupValues[1]) to m.groupValues[2].replace("\\\"", "\"").replace("\\n", "\n").trim()
            }
            val outcome = perform(context, name, args) ?: continue
            done += outcome
            remaining = remaining.replace(match.value, "")
        }
        if (done.isEmpty()) return null
        // Quita restos como comillas invertidas o "tool_code" que rodeaban la llamada.
        remaining = remaining.replace(Regex("```\\w*"), "").replace("tool_code", "").trim()
        return Result(remaining, done)
    }

    private fun normalize(s: String) = s.lowercase()
        .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u")
        .replace("_", "")

    private fun perform(context: Context, name: String, args: Map<String, String>): String? {
        val tools = SelfTools(context)
        return when (name) {
            "proponerhabilidad", "crearhabilidad" -> {
                val skill = args["nombre"] ?: return null
                val instructions = args["instrucciones"] ?: args["descripcion"] ?: return null
                tools.proponerHabilidad(skill, instructions)
                "Te he dejado la habilidad \"$skill\" en la pantalla: pulsa Aplicar para que la aprenda."
            }
            "proponerpersonalidad" -> {
                val personality = args["nuevapersonalidad"] ?: args["personalidad"] ?: return null
                tools.proponerPersonalidad(personality)
                "Te he dejado la nueva personalidad en la pantalla: pulsa Aplicar si te gusta."
            }
            "guardardato" -> {
                val value = args["texto"] ?: return null
                DataTools(context).guardarDato(args["coleccion"] ?: "", value)
            }
            else -> null
        }
    }
}
