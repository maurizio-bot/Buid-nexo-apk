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
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolManager
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Un mensaje del chat. [image] es la ruta de una foto adjunta y [fileName] el nombre de un archivo adjunto. */
data class Turn(val role: String, val content: String, val image: String? = null, val fileName: String? = null)

/**
 * El modelo de IA que corre dentro del teléfono (Gemma con LiteRT-LM).
 * Todas las llamadas son lentas y deben hacerse desde un mismo hilo en segundo plano ([Ai.thread]).
 *
 * Regla de oro contra los cierres nativos: nunca se cierra la conversación ni el motor mientras el
 * motor está escribiendo. Por eso todo lo que toca el motor pasa por [lock], y la respuesta en
 * streaming espera en el mismo hilo hasta que el motor termina (si no, el hilo quedaba libre a
 * mitad de respuesta y otra tarea podía cerrar la conversación debajo del motor: la app se cerraba
 * de golpe con un "fallo nativo").
 */
class Brain(
    private val modelPath: String,
    private val cacheDir: String,
    private val tools: List<ToolSet>,
) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /** Nadie toca el motor mientras otro lo está usando (ni desde otro hilo por despiste). */
    private val lock = ReentrantLock()

    private val where get() = if (onGpu) "en la GPU" else "en el procesador"

    /**
     * Las herramientas las ejecuta Ultra Yo, no el motor. Si las ejecuta el motor, lo hace dentro
     * de su propio hilo nativo y cualquier fallo (un archivo raro, una respuesta demasiado larga
     * para la memoria del modelo…) cierra la app de golpe sin dejar rastro. Así, un fallo de una
     * herramienta se convierte en un mensaje para el modelo y en una línea del registro de errores.
     */
    private val toolManager = ToolManager(tools.map { tool(it) })
    private val gson = Gson()

    /**
     * Carga el modelo. Con [useGpu] lo intenta primero en la GPU, que empieza a contestar
     * varias veces antes (sobre todo con el cerebro de 4B); si la GPU no arranca, usa el procesador.
     * Un cierre nativo de la GPU no se puede capturar aquí: de eso se encarga [Assistant.loadBrain].
     */
    fun load(useGpu: Boolean = false) = lock.withLock {
        ErrorLog.begin("modelo", "cargando el modelo " + if (useGpu) "en la GPU" else "en el procesador")
        try {
            loadEngine(useGpu)
        } finally {
            ErrorLog.end("modelo")
        }
    }

    private fun loadEngine(useGpu: Boolean) {
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
    fun reset(systemPrompt: String, history: List<Turn>) = lock.withLock {
        closeConversation()
        conversation = checkNotNull(engine) { "El modelo no está cargado" }.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                initialMessages = history.map {
                    if (it.role == "user") Message.user(it.content) else Message.model(it.content)
                },
                tools = tools.map { tool(it) },
                automaticToolCalling = false,
            )
        )
    }

    fun ask(text: String, imagePath: String? = null): String {
        lock.lock()
        try {
            return askLocked(text, imagePath)
        } finally {
            lock.unlock()
        }
    }

    private fun askLocked(text: String, imagePath: String?): String {
        val conversation = checkNotNull(conversation) { "No hay conversación" }
        var message = firstMessage(text, imagePath)
        repeat(MAX_TOOL_ROUNDS) {
            val reply = engineWork("contestando") { conversation.sendMessage(message) }
            if (reply.toolCalls.isEmpty()) return reply.toString().trim()
            message = runTools(reply.toolCalls)
        }
        return "Hice demasiadas consultas seguidas sin llegar a una respuesta."
    }

    /** Apunta que el motor está trabajando, para saber qué hacía si la app se cierra de golpe. */
    private inline fun <T> engineWork(what: String, block: () -> T): T {
        ErrorLog.begin("modelo", "el modelo $what $where")
        try {
            return block()
        } finally {
            ErrorLog.end("modelo")
        }
    }

    private fun closeConversation() {
        val old = conversation ?: return
        conversation = null
        // Si ya estaba cerrada, close() lanza: da igual.
        runCatching { old.close() }
    }

    /**
     * Como [ask], pero va entregando la respuesta a trozos según el modelo la escribe, para que
     * Ultra Yo empiece a hablar sin esperar al final. Devuelve la respuesta completa.
     * Si el modelo pide herramientas, se ejecutan aquí y se le devuelve el resultado.
     */
    suspend fun askStreaming(text: String, imagePath: String? = null, onChunk: (String) -> Unit): String {
        val context = currentCoroutineContext()
        return lock.withLock {
            val conversation = checkNotNull(conversation) { "No hay conversación" }
            var message = firstMessage(text, imagePath)
            val full = StringBuilder()
            repeat(MAX_TOOL_ROUNDS) {
                val calls = mutableListOf<ToolCall>()
                val round = StringBuilder()
                engineWork("contestando") {
                    streamRound(conversation, message, cancelled = { !context.isActive }) { reply ->
                        calls += reply.toolCalls
                        val piece = reply.toString()
                        // Por si el motor entrega el texto acumulado en vez de solo lo nuevo.
                        val delta = if (round.isNotEmpty() && piece.length > round.length && piece.startsWith(round)) piece.substring(round.length) else piece
                        if (delta.isNotEmpty()) {
                            round.append(delta)
                            full.append(delta)
                            onChunk(delta)
                        }
                    }
                }
                if (calls.isEmpty()) return@withLock full.toString().trim()
                message = runTools(calls)
            }
            full.toString().trim().ifBlank { "Hice demasiadas consultas seguidas sin llegar a una respuesta." }
        }
    }

    /**
     * Manda [message] y entrega cada trozo de la respuesta a [onReply] en este mismo hilo, sin soltarlo
     * hasta que el motor ha terminado del todo. Si se cancela (PARAR, la app se va), le pide al motor
     * que pare y espera a que pare de verdad: nunca se deja al motor escribiendo en una conversación
     * que alguien podría cerrar.
     */
    private fun streamRound(conversation: Conversation, message: Message, cancelled: () -> Boolean, onReply: (Message) -> Unit) {
        val events = LinkedBlockingQueue<Any>()
        conversation.sendMessageAsync(
            message,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    events.put(message)
                }

                override fun onDone() {
                    events.put(DONE)
                }

                override fun onError(throwable: Throwable) {
                    events.put(throwable)
                }
            },
        )
        var stopping = false
        var stoppedAt = 0L
        var failure: Throwable? = null
        fun stop() {
            if (stopping) return
            stopping = true
            stoppedAt = System.currentTimeMillis()
            runCatching { conversation.cancelProcess() }
        }
        while (true) {
            val event = events.poll(POLL_MS, TimeUnit.MILLISECONDS)
            if (event == null) {
                if (!stopping && cancelled()) stop()
                if (stopping && System.currentTimeMillis() - stoppedAt > STOP_WAIT_MS) {
                    // El motor no confirma que ha parado. Cerrar la conversación ahora podría tumbar la app:
                    // se abandona sin cerrarla (gasta algo de memoria hasta reiniciar, pero no se cierra nada).
                    ErrorLog.add("El motor no paró tras cancelar; se deja esa conversación sin cerrar")
                    if (this.conversation === conversation) this.conversation = null
                    break
                }
                continue
            }
            if (event === DONE) break
            if (event is Throwable) {
                if (!stopping) failure = event
                break
            }
            if (event is Message && !stopping) {
                try {
                    onReply(event)
                } catch (e: Throwable) {
                    // Algo falló al enseñar la respuesta: se para el motor y se espera a que termine.
                    failure = e
                    stop()
                }
            }
            if (!stopping && cancelled()) stop()
        }
        failure?.let { throw it }
        if (stopping) throw CancellationException("Respuesta cancelada")
    }

    private fun firstMessage(text: String, imagePath: String?): Message =
        if (imagePath != null && visionEnabled) {
            Message.user(Contents.of(Content.ImageFile(imagePath), Content.Text(text)))
        } else {
            Message.user(text)
        }

    /** Ejecuta lo que pidió el modelo. Pase lo que pase, devuelve una respuesta y la app sigue. */
    private fun runTools(calls: List<ToolCall>): Message {
        val responses = calls.map { call ->
            ErrorLog.step("herramienta ${call.name} ${call.arguments}".take(300))
            val result = try {
                val args = gson.toJsonTree(call.arguments).asJsonObject
                plain(toolManager.execute(call.name, args)).also {
                    if (it.startsWith("Error occured")) ErrorLog.add("Herramienta ${call.name} falló: $it".take(1500))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ErrorLog.add("Herramienta ${call.name} ${call.arguments}".take(300), e)
                "Error: ${e.javaClass.simpleName} ${e.message.orEmpty()}".take(300)
            }
            ErrorLog.stepDone()
            val short = if (result.length > MAX_TOOL_RESULT) result.take(MAX_TOOL_RESULT) + "\n[… cortado: era demasiado largo]" else result
            Content.ToolResponse(call.name, short)
        }
        return Message.tool(Contents.of(responses))
    }

    /** El resultado de una herramienta como texto simple (las listas, una línea por cosa). */
    private fun plain(element: JsonElement): String = when {
        element.isJsonNull -> ""
        element.isJsonPrimitive -> element.asString
        element.isJsonArray -> element.asJsonArray.joinToString("\n") { plain(it) }
        else -> element.toString()
    }

    /**
     * Una petición suelta, fuera del chat (por ejemplo, traducir lo que hay que pintar).
     * Cierra la conversación en curso: después hay que llamar a [reset].
     */
    fun answerOnce(instruction: String, text: String, imagePath: String? = null): String {
        lock.lock()
        try {
            return answerOnceLocked(instruction, text, imagePath)
        } finally {
            lock.unlock()
        }
    }

    private fun answerOnceLocked(instruction: String, text: String, imagePath: String?): String {
        closeConversation()
        val once = checkNotNull(engine) { "El modelo no está cargado" }
            .createConversation(ConversationConfig(systemInstruction = Contents.of(instruction)))
        return try {
            val reply = engineWork("haciendo una tarea suelta") {
                if (imagePath != null && visionEnabled) {
                    once.sendMessage(Contents.of(Content.ImageFile(imagePath), Content.Text(text)))
                } else {
                    once.sendMessage(text)
                }
            }
            reply.toString().trim()
        } finally {
            runCatching { once.close() }
        }
    }

    companion object {
        const val MAX_TOKENS = 8192

        /** Cada cuánto mira si hay que parar mientras el motor escribe. */
        private const val POLL_MS = 150L

        /** Lo que se espera a que el motor confirme que ha parado. */
        private const val STOP_WAIT_MS = 20_000L

        /** Marca de "el motor ha terminado". */
        private val DONE = Any()

        /** Cuántas veces seguidas puede usar herramientas antes de contestar. */
        const val MAX_TOOL_ROUNDS = 8

        /** Lo máximo que se le devuelve de una herramienta, para que quepa en su memoria. */
        const val MAX_TOOL_RESULT = 3600

        /** Si el error es que la conversación ya no cabe en la memoria del modelo. */
        fun isFull(e: Throwable): Boolean {
            val message = e.message.orEmpty()
            return message.contains("exceeds", ignoreCase = true) || message.contains("capacity", ignoreCase = true)
        }
    }

    fun close() = lock.withLock {
        closeConversation()
        runCatching { engine?.close() }
        engine = null
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

    /** La app se cerró de golpe con el modelo en la GPU: la próxima vez, el procesador. */
    fun markBroken(context: Context) {
        if (wanted(context)) prefs(context).edit().putString("gpu_estado", "rota").commit()
    }

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
