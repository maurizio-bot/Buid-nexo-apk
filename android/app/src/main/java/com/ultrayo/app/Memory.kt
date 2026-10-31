package com.ultrayo.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_UNDO = 20

private const val DEFAULT_PERSONALITY = """Eres Ultra Yo, el asistente personal de Maurizio, al estilo de Jarvis.
Le ayudas sobre todo a desarrollar sus proyectos.
Hablas en español, con un tono cercano y directo.
Tus respuestas se suelen leer en voz alta: sé breve (dos o tres frases salvo que te pida más),
no uses listas, tablas, asteriscos ni bloques de código."""

/** Lo que Maurizio le enseña a su IA. Se guarda en su carpeta del teléfono (memoria.json, ver [Vault]). */
class Memory(context: Context) {

    private val prefs = VaultPrefs.memory(context)
    private val folder = Vault.path(context)

    var personality: String
        get() = prefs.getString("personality", null) ?: DEFAULT_PERSONALITY
        set(value) = prefs.edit().putString("personality", value.ifBlank { DEFAULT_PERSONALITY }).apply()

    /** Contestar en voz alta (por defecto) o solo por escrito ("modo texto"). */
    var voiceReplies: Boolean
        get() = prefs.getBoolean("voice_replies", true)
        set(value) = prefs.edit().putBoolean("voice_replies", value).apply()

    /** Escuchar la palabra "Ultra" en segundo plano. */
    var wakeWord: Boolean
        get() = prefs.getBoolean("wake_word", true)
        set(value) = prefs.edit().putBoolean("wake_word", value).apply()

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

    /** Habilidades programadas: código que la IA ejecuta en su motor de habilidades (ver [SkillEngine]). */
    fun codeSkills(): Map<String, CodeSkill> {
        val obj = JSONObject(prefs.getString("code_skills", "{}") ?: "{}")
        return obj.keys().asSequence().associateWith {
            val skill = obj.getJSONObject(it)
            CodeSkill(skill.optString("description"), skill.optString("code"))
        }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    fun setCodeSkill(name: String, skill: CodeSkill) = saveCodeSkills(codeSkills() + (name.trim() to skill))

    fun removeCodeSkill(name: String): Boolean {
        val key = findCodeSkill(name) ?: return false
        saveCodeSkills(codeSkills() - key)
        return true
    }

    fun findCodeSkill(name: String): String? {
        val wanted = name.trim().lowercase()
        val names = codeSkills().keys
        return names.firstOrNull { it.lowercase() == wanted } ?: names.firstOrNull { it.lowercase().contains(wanted) && wanted.length > 2 }
    }

    /** Guarda cómo está todo ahora para poder deshacer el próximo cambio. */
    fun snapshot() {
        val history = JSONArray(prefs.getString("history", "[]"))
        val state = JSONObject()
            .put("personality", personality)
            .put("facts", JSONArray(facts()))
            .put("skills", JSONObject(skills()))
            .put("code_skills", JSONObject(prefs.getString("code_skills", "{}") ?: "{}"))
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
            .putString("code_skills", state.optJSONObject("code_skills")?.toString() ?: prefs.getString("code_skills", "{}"))
            .putString("history", history.toString())
            .apply()
        return true
    }

    fun systemPrompt(): String = buildString {
        append(personality)
        append(
            "\n\nPuedes usar herramientas para ver qué apps hay en el teléfono y abrirlas; para buscar fotos, vídeos, " +
                "audios y documentos del teléfono, enseñarlos en pantalla y leer documentos (txt, pdf, doc, docx, xlsx…); " +
                "y para pasarle una pregunta a Claude abriendo su app. Con los archivos solo puedes leer: no puedes " +
                "cambiar ni borrar nada. Úsalas solo cuando te lo pida." +
                "\n\nTu carpeta de almacenamiento es $folder: ahí está tu memoria, tu base de datos y los archivos que " +
                "Maurizio te pide guardar. Cuando te pida guardar, apuntar o registrar algo, usa guardarDato; si es un " +
                "archivo, guardarArchivo. Si te pide descomprimir algo, usa descomprimir (lo extrae en tu carpeta) y luego " +
                "leerArchivo para leer y analizar lo que había dentro. Cuando te pida revisar tu memoria o buscar algo guardado, usa revisarMemoria." +
                "\n\nSi Maurizio te pide que cambies tu forma de ser, tus reglas o tus habilidades, usa las " +
                "herramientas proponer… para cambiarte o borrarte a ti mismo. Maurizio confirma cada cambio en la " +
                "pantalla, así que dile que lo revise. No las uses si él no te lo ha pedido."
        )
        val skills = skills()
        if (skills.isNotEmpty()) {
            append("\n\nHabilidades que tienes (síguelas cuando venga al caso):\n")
            skills.forEach { (name, instructions) -> append("- ").append(name).append(": ").append(instructions).append('\n') }
        }
        val code = codeSkills()
        if (code.isNotEmpty()) {
            append("\n\nHabilidades programadas (ejecútalas con usarHabilidad cuando venga al caso):\n")
            code.forEach { (name, skill) -> append("- ").append(name).append(": ").append(skill.description).append('\n') }
        }
        append(
            "\nSolo si Maurizio te pide expresamente programar una habilidad, escribe su código JavaScript con " +
                "proponerCodigoHabilidad; él lo revisa y lo acepta en la pantalla."
        )
        val facts = facts()
        if (facts.isNotEmpty()) {
            append("\n\nCosas que Maurizio te ha enseñado y que debes tener siempre en cuenta:\n")
            facts.forEach { append("- ").append(it).append('\n') }
        }
    }

    private fun saveSkills(skills: Map<String, String>) {
        prefs.edit().putString("skills", JSONObject(skills).toString()).apply()
    }

    private fun saveCodeSkills(skills: Map<String, CodeSkill>) {
        val obj = JSONObject()
        skills.forEach { (name, skill) -> obj.put(name, JSONObject().put("description", skill.description).put("code", skill.code)) }
        prefs.edit().putString("code_skills", obj.toString()).apply()
    }

    private fun save(facts: List<String>) {
        prefs.edit().putString("facts", JSONArray(facts).toString()).apply()
    }
}
