package com.ultrayo.app

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool

data class Turn(val role: String, val content: String)

/**
 * El modelo de IA que corre dentro del teléfono (Gemma con LiteRT-LM).
 * Todas las llamadas son lentas y deben hacerse desde un mismo hilo en segundo plano.
 */
class Brain(
    private val modelPath: String,
    private val cacheDir: String,
    private val tools: ToolSet,
) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /**
     * Carga el modelo en el procesador. Es casi tan rápido como la GPU para respuestas cortas
     * y evita cierres de la app si el controlador gráfico falla en código nativo.
     */
    fun load() {
        engine = start(Backend.CPU())
    }

    private fun start(backend: Backend): Engine {
        val engine = Engine(EngineConfig(modelPath = modelPath, backend = backend, cacheDir = cacheDir))
        try {
            engine.initialize()
        } catch (e: Exception) {
            engine.close()
            throw e
        }
        return engine
    }

    /** Empieza una conversación nueva con las instrucciones y lo ya hablado. */
    fun reset(systemPrompt: String, history: List<Turn>) {
        conversation?.close()
        conversation = checkNotNull(engine) { "El modelo no está cargado" }.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                initialMessages = history.map {
                    if (it.role == "user") Message.user(it.content) else Message.model(it.content)
                },
                tools = listOf(tool(tools)),
            )
        )
    }

    fun ask(text: String): String =
        checkNotNull(conversation) { "No hay conversación" }.sendMessage(text).toString().trim()

    fun close() {
        conversation?.close()
        engine?.close()
    }
}
