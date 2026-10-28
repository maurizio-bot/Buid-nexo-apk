package com.ultrayo.app

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/** Un cambio que la IA quiere hacerse a sí misma y que Maurizio aún no ha aceptado. */
class SelfChange(val description: String, val detail: String, val apply: (Memory) -> Unit)

/** El cambio pendiente de confirmar. Solo hay uno a la vez: el último reemplaza al anterior. */
object SelfEdits {
    val pending = mutableStateOf<SelfChange?>(null)
    private val main = Handler(Looper.getMainLooper())

    fun propose(change: SelfChange) = main.post { pending.value = change }
}

/**
 * Lo que la IA puede cambiar de sí misma: personalidad y habilidades.
 * Nada se aplica sin que Maurizio lo acepte en la pantalla, y todo se puede deshacer.
 */
class SelfTools(context: Context) : ToolSet {

    private val context = context.applicationContext

    @Tool(description = "Muestra tu personalidad actual, tus habilidades y lo que Maurizio te ha enseñado.")
    fun verMiMente(): String {
        if (locked()) return LOCKED
        val memory = Memory(context)
        return buildString {
            append("PERSONALIDAD:\n").append(memory.personality)
            append("\n\nHABILIDADES:\n")
            val skills = memory.skills()
            if (skills.isEmpty()) append("(ninguna)\n")
            skills.forEach { (name, text) -> append("- ").append(name).append(": ").append(text).append('\n') }
            append("\nRECUERDOS:\n")
            val facts = memory.facts()
            if (facts.isEmpty()) append("(ninguno)\n")
            facts.forEach { append("- ").append(it).append('\n') }
        }
    }

    @Tool(description = "Propone reescribir tu personalidad entera (cómo eres y cómo hablas). Maurizio debe aceptarlo.")
    fun proponerPersonalidad(
        @ToolParam(description = "La nueva personalidad completa, escrita en segunda persona (\"Eres…\")") nuevaPersonalidad: String,
    ): String = propose(
        SelfChange("Reescribir mi personalidad", nuevaPersonalidad.trim()) { it.personality = nuevaPersonalidad.trim() }
    )

    @Tool(description = "Propone crear una habilidad nueva o reescribir una que ya tienes. Maurizio debe aceptarlo.")
    fun proponerHabilidad(
        @ToolParam(description = "Nombre corto de la habilidad, por ejemplo Modo programador") nombre: String,
        @ToolParam(description = "Instrucciones de la habilidad: cuándo se usa y qué debes hacer") instrucciones: String,
    ): String {
        val existing = Memory(context).findSkill(nombre)
        val title = if (existing != null) "Reescribir la habilidad \"$existing\"" else "Crear la habilidad \"${nombre.trim()}\""
        return propose(SelfChange(title, instrucciones.trim()) { it.setSkill(existing ?: nombre, instrucciones) })
    }

    @Tool(description = "Propone borrar una de tus habilidades. Maurizio debe aceptarlo.")
    fun proponerBorrarHabilidad(
        @ToolParam(description = "Nombre de la habilidad que hay que borrar") nombre: String,
    ): String {
        val existing = Memory(context).findSkill(nombre) ?: return "No tengo ninguna habilidad llamada $nombre."
        return propose(SelfChange("Borrar la habilidad \"$existing\"", "") { it.removeSkill(existing) })
    }

    @Tool(description = "Propone borrar uno de los recuerdos que Maurizio te enseñó. Maurizio debe aceptarlo.")
    fun proponerBorrarRecuerdo(
        @ToolParam(description = "El recuerdo que hay que borrar, tal como aparece en verMiMente") recuerdo: String,
    ): String {
        val memory = Memory(context)
        val index = memory.facts().indexOfFirst { it.equals(recuerdo.trim(), ignoreCase = true) }
            .takeIf { it >= 0 }
            ?: memory.facts().indexOfFirst { it.lowercase().contains(recuerdo.trim().lowercase()) }
        if (index < 0) return "No encontré ese recuerdo."
        val fact = memory.facts()[index]
        return propose(SelfChange("Borrar un recuerdo", fact) { m ->
            val i = m.facts().indexOf(fact)
            if (i >= 0) m.removeAt(i)
        })
    }

    private fun propose(change: SelfChange): String {
        if (locked()) return LOCKED
        SelfEdits.propose(change)
        return "Propuesta enviada. Aparece en la pantalla y no se aplica hasta que Maurizio pulse Aplicar. Díselo."
    }

    private fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private companion object {
        const val LOCKED = "El teléfono está bloqueado. Pídele a Maurizio que lo desbloquee para cambiar mi mente."
    }
}
