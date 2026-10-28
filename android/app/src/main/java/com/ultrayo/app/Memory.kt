package com.ultrayo.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_UNDO = 20

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

    /** Habilidades: instrucciones con nombre que Maurizio (o la propia IA, con su permiso) le añade. */
    fun skills(): Map<String, String> {
        val obj = JSONObject(prefs.getString("skills", "{}") ?: "{}")
        return obj.keys().asSequence().associateWith { obj.getString(it) }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    fun setSkill(name: String, instructions: String) = saveSkills(skills() + (name.trim() to instructions.trim()))

    /** Devuelve false si no existía. */
    fun removeSkill(name: String): Boolean {
        val skills = skills()
        val key = findSkill(name) ?: return false
        saveSkills(skills - key)
        return true
    }

    fun findSkill(name: String): String? {
        val wanted = name.trim().lowercase()
        return skills().keys.firstOrNull { it.lowercase() == wanted }
    }

    /** Guarda cómo está todo ahora para poder deshacer el próximo cambio. */
    fun snapshot() {
        val history = JSONArray(prefs.getString("history", "[]"))
        val state = JSONObject()
            .put("personality", personality)
            .put("facts", JSONArray(facts()))
            .put("skills", JSONObject(skills()))
        history.put(state)
        while (history.length() > MAX_UNDO) history.remove(0)
        prefs.edit().putString("history", history.toString()).apply()
    }

    fun canUndo(): Boolean = JSONArray(prefs.getString("history", "[]")).length() > 0

    /** Vuelve al estado anterior al último cambio. Devuelve false si no había nada que deshacer. */
    fun undo(): Boolean {
        val history = JSONArray(prefs.getString("history", "[]"))
        if (history.length() == 0) return false
        val state = history.getJSONObject(history.length() - 1)
        history.remove(history.length() - 1)
        val facts = state.getJSONArray("facts")
        prefs.edit()
            .putString("personality", state.getString("personality"))
            .putString("facts", facts.toString())
            .putString("skills", state.getJSONObject("skills").toString())
            .putString("history", history.toString())
            .apply()
        return true
    }

    fun systemPrompt(): String = buildString {
        append(personality)
        append(
            "\n\nPuedes usar herramientas para ver qué apps hay en el teléfono, abrirlas, y buscar y leer " +
                "archivos de las carpetas que Maurizio te ha permitido. Solo puedes leer: no puedes " +
                "cambiar ni borrar nada. Úsalas solo cuando te lo pida." +
                "\n\nSi Maurizio te pide que cambies tu forma de ser, tus reglas o tus habilidades, usa las " +
                "herramientas proponer… para cambiarte o borrarte a ti mismo. Maurizio confirma cada cambio en la " +
                "pantalla, así que dile que lo revise. No las uses si él no te lo ha pedido."
        )
        val skills = skills()
        if (skills.isNotEmpty()) {
            append("\n\nHabilidades que tienes (síguelas cuando venga al caso):\n")
            skills.forEach { (name, instructions) -> append("- ").append(name).append(": ").append(instructions).append('\n') }
        }
        val facts = facts()
        if (facts.isNotEmpty()) {
            append("\n\nCosas que Maurizio te ha enseñado y que debes tener siempre en cuenta:\n")
            facts.forEach { append("- ").append(it).append('\n') }
        }
    }

    private fun saveSkills(skills: Map<String, String>) {
        prefs.edit().putString("skills", JSONObject(skills).toString()).apply()
    }

    private fun save(facts: List<String>) {
        prefs.edit().putString("facts", JSONArray(facts).toString()).apply()
    }
}
