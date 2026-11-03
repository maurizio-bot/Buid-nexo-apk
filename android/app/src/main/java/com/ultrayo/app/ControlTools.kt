package com.ultrayo.app

import android.content.Context
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Herramientas del modelo para manejar el teléfono cuando la orden es más complicada que una
 * frase directa (esas las hace [QuickActions] sin pasar por aquí). Lo arriesgado pide el "sí".
 */
class ControlTools(context: Context) : ToolSet {

    private val context = context.applicationContext

    @Tool(description = "Hace una acción del teléfono descrita en español: abrir una app, llamar a alguien, mandar un WhatsApp o SMS (\"manda un whatsapp a Juan que llego tarde\"), poner una alarma o temporizador, navegar a un sitio, linterna, volumen, poner música. Úsala en cuanto Maurizio pida algo así.")
    fun accionTelefono(
        @ToolParam(description = "La orden en español, completa y directa, por ejemplo: pon una alarma a las 7:30") orden: String,
    ): String {
        if (Control.stopped) return STOPPED
        return QuickActions.handle(context, orden)
            ?: "No sé hacer esa acción directamente. Si es dentro de una app, usa leerPantalla y pulsar."
    }

    @Tool(description = "Cuando Maurizio pide una tarea de varios pasos entre apps (buscar algo y mandárselo a alguien, abrir una app y hacer algo dentro…), NO la hagas directamente: propón aquí el plan, un paso por línea, en español y en imperativo (\"busca el vuelo más barato a Madrid\", \"mándaselo a Juan por WhatsApp\"). Se le enseña y se hace cuando diga que sí.")
    fun proponerPlan(
        @ToolParam(description = "Los pasos, uno por línea o separados por |") pasos: String,
    ): String {
        if (Control.stopped) return STOPPED
        val plan = Tareas.desdeModelo(pasos) ?: return "No entendí los pasos. Escríbelos uno por línea."
        return Tareas.proponer(context, plan) + " (Ya se lo he enseñado: dile el plan en una frase y espera su sí.)"
    }

    @Tool(description = "Lee el texto y los botones que hay ahora en la pantalla del teléfono, en cualquier app.")
    fun leerPantalla(): String = robot()?.readScreen() ?: NO_CONTROL

    @Tool(description = "Pulsa en la pantalla el botón o elemento que tenga ese texto. Si es enviar, pagar, borrar o similar, Maurizio tendrá que confirmarlo.")
    fun pulsar(
        @ToolParam(description = "El texto del botón o elemento, tal como sale en leerPantalla") texto: String,
    ): String {
        if (Control.stopped) return STOPPED
        return robot()?.tapSafely(texto) ?: NO_CONTROL
    }

    @Tool(description = "Escribe un texto en el campo de la pantalla que tiene el cursor (o en el primero que haya).")
    fun escribirEnPantalla(
        @ToolParam(description = "El texto que hay que escribir") texto: String,
    ): String {
        if (Control.stopped) return STOPPED
        return robot()?.type(texto) ?: NO_CONTROL
    }

    @Tool(description = "Desplaza la pantalla hacia abajo o hacia arriba.")
    fun desplazar(
        @ToolParam(description = "abajo o arriba") direccion: String,
    ): String {
        if (Control.stopped) return STOPPED
        return robot()?.scroll(forward = !direccion.lowercase().contains("arriba")) ?: NO_CONTROL
    }

    @Tool(description = "Usa un botón del sistema: atras, inicio, recientes, notificaciones, captura o bloquear.")
    fun botonSistema(
        @ToolParam(description = "atras, inicio, recientes, notificaciones, captura o bloquear") boton: String,
    ): String {
        if (Control.stopped) return STOPPED
        return robot()?.systemButton(QuickActions.norm(boton).replace(" ", "")) ?: NO_CONTROL
    }

    private fun robot() = UltraAccessibility.instance

    private companion object {
        const val NO_CONTROL = "El control del teléfono no está activado. Dile a Maurizio que lo active en Ajustes de Ultra Yo, en Control del teléfono."
        const val STOPPED = "Maurizio te ha parado: no hagas nada más y díselo."
    }
}
