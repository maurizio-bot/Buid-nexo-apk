package com.ultrayo.app

import android.content.Context
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
    private val tools: List<ToolSet>,
) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /**
     * Carga el modelo. Con [useGpu] lo intenta primero en la GPU, que empieza a contestar
     * varias veces antes (sobre todo con el cerebro de 4B); si la GPU no arranca, usa el procesador.
     * Un cierre nativo de la GPU no se puede capturar aquí: de eso se encarga [Assistant.loadBrain].
     */
    fun load(useGpu: Boolean = false) {
        if (useGpu) {
            try {
                engine = start(vision = true, gpu = true).also { visionEnabled = true }
                onGpu = true
                return
            } catch (e: Exception) {
                runCatching { engine = start(vision = false, gpu = true) }
                if (engine != null) {
                    onGpu = true
                    return
                }
            }
        }
        engine = try {
            start(vision = true, gpu = false).also { visionEnabled = true }
        } catch (e: Exception) {
            // Si la parte que ve imágenes no arranca, al menos que pueda hablar.
            start(vision = false, gpu = false)
        }
    }

    /** Si puede mirar fotos. */
    var visionEnabled = false
        private set

    /** Si está funcionando en la GPU (si no, en el procesador). */
    var onGpu = false
        private set

    /**
     * Arranca el motor con espacio para [MAX_TOKENS] tokens de conversación (archivos largos,
     * resultados de herramientas…). Si el modelo no lo admite, con lo que traiga por defecto.
     */
    private fun start(vision: Boolean, gpu: Boolean): Engine =
        try {
            start(vision, gpu, MAX_TOKENS)
        } catch (e: Exception) {
            start(vision, gpu, null)
        }

    private fun start(vision: Boolean, gpu: Boolean, maxTokens: Int?): Engine {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = if (gpu) Backend.GPU() else Backend.CPU(),
            visionBackend = if (vision) (if (gpu) Backend.GPU() else Backend.CPU()) else null,
            maxNumTokens = maxTokens,
            cacheDir = cacheDir,
        )
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
                tools = tools.map { tool(it) },
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

    /**
     * Como [ask], pero va entregando la respuesta a trozos según el modelo la escribe, para que
     * Ultra Yo empiece a hablar sin esperar al final. Devuelve la respuesta completa.
     */
    suspend fun askStreaming(text: String, imagePath: String? = null, onChunk: (String) -> Unit): String {
        val conversation = checkNotNull(conversation) { "No hay conversación" }
        val flow = if (imagePath != null && visionEnabled) {
            conversation.sendMessageAsync(Contents.of(Content.ImageFile(imagePath), Content.Text(text)))
        } else {
            conversation.sendMessageAsync(Contents.of(text))
        }
        val full = StringBuilder()
        flow.collect { message ->
            val piece = message.toString()
            // Por si el motor entrega el texto acumulado en vez de solo lo nuevo.
            val delta = if (full.isNotEmpty() && piece.length > full.length && piece.startsWith(full)) piece.substring(full.length) else piece
            if (delta.isNotEmpty()) {
                full.append(delta)
                onChunk(delta)
            }
        }
        return full.toString().trim()
    }

    /**
     * Una petición suelta, fuera del chat (por ejemplo, traducir lo que hay que pintar).
     * Cierra la conversación en curso: después hay que llamar a [reset].
     */
    fun answerOnce(instruction: String, text: String, imagePath: String? = null): String {
        conversation?.close()
        conversation = null
        val once = checkNotNull(engine) { "El modelo no está cargado" }
            .createConversation(ConversationConfig(systemInstruction = Contents.of(instruction)))
        return try {
            val reply = if (imagePath != null && visionEnabled) {
                once.sendMessage(Contents.of(Content.ImageFile(imagePath), Content.Text(text)))
            } else {
                once.sendMessage(text)
            }
            reply.toString().trim()
        } finally {
            once.close()
        }
    }

    companion object {
        const val MAX_TOKENS = 8192

        /** Si el error es que la conversación ya no cabe en la memoria del modelo. */
        fun isFull(e: Throwable): Boolean {
            val message = e.message.orEmpty()
            return message.contains("exceeds", ignoreCase = true) || message.contains("capacity", ignoreCase = true)
        }
    }

    fun close() {
        conversation?.close()
        engine?.close()
    }
}

/**
 * Recuerda si la GPU funciona en este teléfono. Si la app se cierra de golpe mientras el modelo
 * arranca o contesta en la GPU (un fallo nativo que no se puede capturar), la próxima vez usa el
 * procesador. Se guarda con commit() para que el aviso quede escrito antes de un posible cierre.
 */
object GpuGuard {
    private fun prefs(context: Context) = context.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)

    /** Maurizio quiere usar la GPU (por defecto sí). */
    fun wanted(context: Context) = prefs(context).getBoolean("usar_gpu", true)

    fun setWanted(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("usar_gpu", on).putString("gpu_estado", "sin_probar").commit()

    fun broken(context: Context) = prefs(context).getString("gpu_estado", null) == "rota"

    /** Antes de cargar: decide si probar la GPU. Si la última vez se quedó a medias, la da por rota. */
    fun beforeLoad(context: Context): Boolean {
        val p = prefs(context)
        if (p.getString("gpu_estado", null) == "probando") p.edit().putString("gpu_estado", "rota").commit()
        val use = wanted(context) && !broken(context)
        if (use) p.edit().putString("gpu_estado", "probando").commit()
        return use
    }

    /** Tras cargar: si cayó al procesador por un error normal, no lo vuelve a intentar. */
    fun afterLoad(context: Context, tried: Boolean, onGpu: Boolean) {
        if (tried && !onGpu) prefs(context).edit().putString("gpu_estado", "rota").commit()
    }

    /** La primera respuesta en la GPU salió bien: ya es de fiar. */
    fun answered(context: Context) {
        val p = prefs(context)
        if (p.getString("gpu_estado", null) == "probando") p.edit().putString("gpu_estado", "ok").commit()
    }
}
