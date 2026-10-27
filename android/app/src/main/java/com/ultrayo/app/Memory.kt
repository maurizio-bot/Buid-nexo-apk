package com.ultrayo.app

import android.content.Context
import org.json.JSONArray

private const val DEFAULT_PERSONALITY = """Eres Ultra Yo, el asistente personal de Maurizio, al estilo de Jarvis.
Le ayudas sobre todo a desarrollar sus proyectos.
Hablas en español, con un tono cercano y directo.
Tus respuestas se leen en voz alta: sé breve (dos o tres frases salvo que te pida más),
no uses listas, tablas, asteriscos ni bloques de código."""

/** Lo que Maurizio le enseña a su IA. Se guarda solo en el teléfono. */
class Memory(context: Context) {

    private val prefs = context.getSharedPreferences("ultrayo_memoria", Context.MODE_PRIVATE)

    var personality: String
        get() = prefs.getString("personality", null) ?: DEFAULT_PERSONALITY
        set(value) = prefs.edit().putString("personality", value.ifBlank { DEFAULT_PERSONALITY }).apply()

    fun facts(): List<String> {
        val array = JSONArray(prefs.getString("facts", "[]"))
        return List(array.length()) { array.getString(it) }
    }

    fun add(fact: String) = save(facts() + fact)

    fun removeAt(index: Int) = save(facts().filterIndexed { i, _ -> i != index })

    fun clear() = save(emptyList())

    /** Borra los recuerdos que contienen todas las palabras de [query]. Devuelve cuántos borró. */
    fun forget(query: String): Int {
        val words = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (words.isEmpty()) return 0
        val before = facts()
        val after = before.filterNot { fact -> words.all { fact.lowercase().contains(it) } }
        save(after)
        return before.size - after.size
    }

    fun systemPrompt(): String = buildString {
        append(personality)
        val facts = facts()
        if (facts.isNotEmpty()) {
            append("\n\nCosas que Maurizio te ha enseñado y que debes tener siempre en cuenta:\n")
            facts.forEach { append("- ").append(it).append('\n') }
        }
    }

    private fun save(facts: List<String>) {
        prefs.edit().putString("facts", JSONArray(facts).toString()).apply()
    }
}
