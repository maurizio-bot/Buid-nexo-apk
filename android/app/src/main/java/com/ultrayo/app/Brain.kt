package com.ultrayo.app

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool

/** Un mensaje del chat. [image] es la ruta de una foto adjunta y [fileName] el nombre de un archivo adjunto. */
data class Turn(val role: String, val content: String, val image: String? = null, val fileName: String? = null)

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
        engine = try {
            start(vision = true).also { visionEnabled = true }
        } catch (e: Exception) {
            // Si la parte que ve imágenes no arranca, al menos que pueda hablar.
            start(vision = false)
        }
    }

    /** Si puede mirar fotos. */
    var visionEnabled = false
        private set

    private fun start(vision: Boolean): Engine {
        val config = if (vision) {
            EngineConfig(modelPath = modelPath, backend = Backend.CPU(), visionBackend = Backend.CPU(), cacheDir = cacheDir)
        } else {
            EngineConfig(modelPath = modelPath, backend = Backend.CPU(), cacheDir = cacheDir)
        }
        val engine = Engine(config)
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

    fun ask(text: String, imagePath: String? = null): String {
        val conversation = checkNotNull(conversation) { "No hay conversación" }
        val reply = if (imagePath != null && visionEnabled) {
            conversation.sendMessage(Contents.of(Content.ImageFile(imagePath), Content.Text(text)))
        } else {
            conversation.sendMessage(text)
        }
        return reply.toString().trim()
    }

    fun close() {
        conversation?.close()
        engine?.close()
    }
}
