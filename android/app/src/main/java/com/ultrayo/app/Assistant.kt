package com.ultrayo.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val MAX_HISTORY = 20
private const val MAX_PROMPT = 6000

/** Lo que el usuario adjunta antes de enviar: una foto ya copiada al teléfono o el texto de un archivo. */
data class Attachment(val name: String, val imagePath: String? = null, val text: String? = null)

/** Una forma de escuchar una frase: el reconocedor de Android o, si falla, el del propio teléfono (Vosk). */
fun interface Ear {
    /** [onFailed] recibe el código de error cuando el micrófono no se pudo usar. */
    fun listen(onHeard: (String) -> Unit, onNothing: () -> Unit, onFailed: (Int) -> Unit)
}

/**
 * La conversación con Ultra Yo, compartida por la pantalla y por el servicio que escucha "Ultra"
 * en segundo plano. Todo se llama desde el hilo principal.
 */
object Assistant {

    val turns = mutableStateListOf<Turn>()
    var status by mutableStateOf("")
    var listening by mutableStateOf(false)
    var thinking by mutableStateOf(false)
    var speaking by mutableStateOf(false)
    var brainReady by mutableStateOf(false)

    /** Hay que reiniciar la conversación del modelo (cambió la memoria o se borró el chat). */
    var brainDirty = true

    lateinit var memory: Memory
        private set

    /** Lo último que dijo Maurizio, para que la IA solo se programe cuando él lo pide. */
    @Volatile
    private var lastUserText = ""

    private val codeRequest = Regex(
        "program|c[oó]digo|script|javascript|funci[oó]n|habilidad",
        RegexOption.IGNORE_CASE,
    )
    private val readTextRequest = Regex(
        "(lee|l[eé]eme|extrae|saca|copia)[^.?]*\\b(texto|pone|dice|escrito|letras)|qu[eé] (pone|dice|est[aá] escrito)|transcr[ií]be|p[aá]sa(la|me)? a texto|\\bocr\\b",
        RegexOption.IGNORE_CASE,
    )
    private val runSkill = Regex(
        "^(?:ejecuta|usa|lanza|corre) (?:la )?habilidad (?:programada )?(.+?)(?: con (.+))?$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private lateinit var app: Context
    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var afterSpeech: (() -> Unit)? = null
    private var loading = false

    private val textMode = Regex(
        "^(modo (texto|escrito)|(resp[oó]ndeme|contesta(me)?|h[aá]blame|responde) (por|en|con) (texto|escrito))",
        RegexOption.IGNORE_CASE,
    )
    private val voiceMode = Regex(
        "^(modo (voz|audio)|(resp[oó]ndeme|contesta(me)?|h[aá]blame|responde) (por|en|con) (voz|audio))",
        RegexOption.IGNORE_CASE,
    )

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        memory = Memory(app)
        Control.init(app)
        brainReady = Ai.brain != null
        tts = TextToSpeech(app) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("es-ES")
                // Un poco más rápida que la voz por defecto: más ágil, menos robot.
                tts?.setSpeechRate(1.1f)
                ttsReady = true
            }
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = main.post { utteranceDone(utteranceId) }.let {}
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = main.post { utteranceDone(utteranceId) }.let {}
            })
        }
    }

    fun locked() = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    // --- Cerebro ---

    /** Carga el modelo si ya está descargado. [onReady] se llama cuando está listo. */
    fun loadBrain(onReady: () -> Unit = {}) {
        if (Ai.brain != null) {
            brainReady = true
            onReady()
            return
        }
        val downloader = ModelDownloader(app)
        if (!downloader.isReady() || loading) return
        loading = true
        status = "Despertando a tu IA…"
        scope.launch {
            try {
                withContext(Ai.thread) {
                    if (Ai.brain == null) {
                        Ai.brain = Brain(
                            downloader.file.path,
                            app.cacheDir.path,
                            listOf(PhoneTools(app), ControlTools(app), DataTools(app), SkillTools(app), SelfTools(app)),
                        ).also { it.load() }
                    }
                }
                brainReady = true
                status = ""
                onReady()
            } catch (e: Exception) {
                status = "No pude cargar el modelo: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    // --- Oír y hablar ---

    /** Escucha una frase con el reconocedor de voz de Android. */
    val androidEar = Ear { onHeard, onNothing, onFailed ->
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            onFailed(-1)
            return@Ear
        }
        stopSpeaking()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(app).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                status = "Te escucho…"
            }

            override fun onResults(results: Bundle?) {
                listening = false
                status = ""
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) onNothing() else onHeard(text)
            }

            override fun onError(error: Int) {
                listening = false
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        status = ""
                        onNothing()
                    }
                    else -> onFailed(error)
                }
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            // Reconocer dentro del teléfono si se puede: no espera a la red.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        r.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    /**
     * Conversación por voz: escucha, responde en voz alta y vuelve a escuchar,
     * hasta que Maurizio se calla. Entonces llama a [onEnd].
     */
    fun converse(ear: Ear = androidEar, onEnd: () -> Unit = {}) {
        ear.listen(
            onHeard = { text ->
                send(text) { if (memory.voiceReplies) converse(ear, onEnd) else onEnd() }
            },
            onNothing = onEnd,
            onFailed = { error ->
                status = "Error del micrófono ($error)"
                onEnd()
            },
        )
    }

    /** Añade la respuesta al chat (con una [image] opcional) y la dice en voz alta, salvo en modo texto. [onDone] se llama al terminar. */
    fun say(text: String, onDone: (() -> Unit)? = null, image: String? = null) {
        turns.add(Turn("assistant", text, image = image))
        status = ""
        if (!memory.voiceReplies || !ttsReady) {
            onDone?.invoke()
            return
        }
        stopSpeaking()
        afterSpeech = onDone
        speakPart(text)
        maybeFinishSpeech()
    }

    /** Dice algo que llega más tarde (por ejemplo "Enviado"), desde cualquier hilo. */
    fun notify(text: String) = main.post { say(text) }.let {}

    /** Se llama cuando una respuesta se corta a medias (para que el servicio vuelva a escuchar "Ultra"). */
    var onCut: (() -> Unit)? = null

    /** Cambia cada vez que se corta la voz: lo que llegue de una respuesta anterior ya no se dice. */
    private var generation = 0
    private var utteranceSeq = 0
    private var queued = 0

    /** La respuesta aún se está escribiendo: no dar la voz por terminada aunque la cola se vacíe. */
    private var streamOpen = false

    /** Pone [text] en la cola de la voz, sin cortar lo que ya está diciendo. */
    private fun speakPart(text: String) {
        val spoken = text.replace(Regex("[*#_`>|]"), "").replace(Regex("https?://\\S+"), "el enlace").trim()
        if (spoken.isEmpty() || !ttsReady) return
        queued++
        speaking = true
        tts?.speak(spoken, TextToSpeech.QUEUE_ADD, null, "g${generation}_${utteranceSeq++}")
    }

    private fun utteranceDone(id: String?) {
        if (id?.startsWith("g${generation}_") != true) return
        if (queued > 0) queued--
        maybeFinishSpeech()
    }

    private fun maybeFinishSpeech() {
        if (queued == 0 && !streamOpen) finishSpeech()
    }

    /** Corta la voz al instante (tocar el reactor, "para", el botón de parar). */
    fun stopSpeaking() {
        val cut = afterSpeech != null
        generation++
        queued = 0
        streamOpen = false
        afterSpeech = null
        if (cut) onCut?.invoke()
        if (speaking) tts?.stop()
        speaking = false
    }

    private fun finishSpeech() {
        speaking = false
        val next = afterSpeech
        afterSpeech = null
        next?.invoke()
    }

    /**
     * Saca de [buffer] las frases ya completas para decirlas mientras el modelo sigue escribiendo.
     * La primera se corta antes (en una coma) para que empiece a hablar cuanto antes.
     */
    private fun takeSentences(buffer: StringBuilder, final: Boolean, first: Boolean): List<String> {
        val parts = mutableListOf<String>()
        while (true) {
            var cut = -1
            for (i in buffer.indices) {
                val ch = buffer[i]
                val next = buffer.getOrNull(i + 1)
                if (ch in ".!?…:;\n" && (next == null && final || next == ' ' || next == '\n')) {
                    cut = i + 1
                    break
                }
                if (first && parts.isEmpty() && ch == ',' && next == ' ' && i >= 40) {
                    cut = i + 1
                    break
                }
            }
            if (cut == -1) break
            parts += buffer.substring(0, cut)
            buffer.delete(0, cut)
        }
        if (final && buffer.isNotBlank()) {
            parts += buffer.toString()
            buffer.clear()
        }
        // No leer en voz alta código ni herramientas escritas como texto.
        return parts.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") && !Regex("\\w\\(.*[=:]\\s*\"").containsMatchIn(it) }
    }

    // --- Conversación ---

    /** Responde a [typed] (más un adjunto opcional). [onDone] se llama cuando termina de contestar. */
    fun send(typed: String, attached: Attachment? = null, onDone: (() -> Unit)? = null) {
        val text = typed.ifBlank {
            when {
                attached?.imagePath != null -> "¿Qué ves en esta foto?"
                attached != null -> "Resúmeme este archivo."
                else -> {
                    onDone?.invoke()
                    return
                }
            }
        }
        val history = turns.takeLast(MAX_HISTORY)
        turns.add(Turn("user", text, image = attached?.imagePath, fileName = attached?.name))
        lastUserText = text

        if (attached == null) {
            val command = text.trim()
            // Lo primero, y sin pensar: parar, o el sí/no a una acción pendiente.
            if (Control.isStop(QuickActions.clean(command))) {
                Control.stop()
                say("Parado.", onDone)
                return
            }
            Control.answer(QuickActions.clean(command))?.let { answer ->
                say(answer, onDone)
                return
            }
            Control.resume()
            if (textMode.containsMatchIn(command)) {
                memory.voiceReplies = false
                say("Vale, a partir de ahora te contesto por escrito. Dime \"modo voz\" para volver.", onDone)
                return
            }
            if (voiceMode.containsMatchIn(command)) {
                memory.voiceReplies = true
                say("Hecho, vuelvo a contestarte en voz alta.", onDone)
                return
            }
            // Órdenes del teléfono: al instante, sin pasar por el modelo.
            QuickActions.handle(app, text)?.let { answer ->
                say(answer, onDone)
                return
            }
            ClaudeLink.questionIn(text)?.let { question ->
                say(if (locked()) "Desbloquea el teléfono y te paso a Claude." else ClaudeLink.open(app, question), onDone)
                return
            }
            // Llamadas a herramientas pegadas o escritas a mano, y "crea una habilidad llamada X: …"
            (WrittenCalls.handle(app, text)?.done?.joinToString(" ") ?: WrittenCalls.skillCommand(app, text))?.let { answer ->
                say(answer, onDone)
                return
            }
            Teaching.handle(text, memory)?.let { answer ->
                brainDirty = true
                say(answer, onDone)
                return
            }
            DataCommands.handle(text, app)?.let { answer ->
                say(answer, onDone)
                return
            }
            runSkill.find(command.trimEnd('.', '!', '?'))?.let { match ->
                val name = memory.findCodeSkill(match.groupValues[1])
                if (name != null) {
                    runCodeSkill(name, match.groupValues[2], onDone)
                    return
                }
            }
        }

        if (attached != null && Regex("^(guarda|gu[aá]rdame|almacena)", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())) {
            val saved = runCatching {
                val dir = Vault.files(app)
                if (attached.imagePath != null) {
                    java.io.File(attached.imagePath).copyTo(java.io.File(dir, "foto-${System.currentTimeMillis()}.jpg"))
                } else {
                    java.io.File(dir, attached.name).also { it.writeText(attached.text.orEmpty()) }
                }
            }
            say(saved.fold({ "Guardado en mi carpeta: ${it.path}." }, { "No pude guardarlo: ${it.message}" }), onDone)
            return
        }

        if (attached == null || attached.imagePath != null) {
            val target = attached?.imagePath ?: recentImage(history)
            if (target != null && readTextRequest.containsMatchIn(text)) {
                readText(target, onDone)
                return
            }
            ImageCommands.parse(text, target != null)?.let { command ->
                image(command, target, onDone)
                return
            }
        }

        val brain = Ai.brain
        if (brain == null) {
            say("Todavía me estoy despertando. Dame un momento.", onDone)
            return
        }
        if (attached?.imagePath != null && !brain.visionEnabled) {
            say("Todavía no puedo ver fotos en este teléfono. Si quieres, descríbemela con palabras.", onDone)
            return
        }
        val prompt = if (attached?.text != null) {
            "$text\n\n[Archivo adjunto: ${attached.name}]\n${attached.text}"
        } else {
            text
        }
        thinking = true
        status = "Pensando…"
        val reset = brainDirty
        brainDirty = false
        val systemPrompt = memory.systemPrompt()
        val image = attached?.imagePath

        // La respuesta aparece y se dice según el modelo la escribe.
        stopSpeaking()
        val gen = generation
        val voice = memory.voiceReplies && ttsReady
        if (voice) {
            streamOpen = true
            afterSpeech = onDone
        }
        val shown = StringBuilder()
        val unspoken = StringBuilder()
        var turnIndex = -1
        var spokeAny = false

        fun showLive() {
            if (turnIndex == -1 || turnIndex >= turns.size) {
                thinking = false
                status = ""
                turns.add(Turn("assistant", shown.toString()))
                turnIndex = turns.lastIndex
            } else {
                turns[turnIndex] = turns[turnIndex].copy(content = shown.toString())
            }
        }

        val onChunk: (String) -> Unit = { delta ->
            main.post {
                if (gen != generation) return@post
                shown.append(delta)
                showLive()
                if (voice) {
                    unspoken.append(delta)
                    takeSentences(unspoken, final = false, first = !spokeAny).forEach {
                        speakPart(it)
                        spokeAny = true
                    }
                }
            }
        }

        scope.launch {
            var failure: String? = null
            val reply = try {
                withContext(Ai.thread) {
                    try {
                        if (reset) brain.reset(systemPrompt, history)
                        brain.askStreaming(prompt, image, onChunk)
                    } catch (e: Exception) {
                        if (!Brain.isFull(e)) throw e
                        // La conversación ya no cabe: empieza de cero (la memoria se conserva) y lo intenta otra vez.
                        brain.reset(systemPrompt, emptyList())
                        brain.askStreaming(prompt.take(MAX_PROMPT), image, onChunk)
                    }
                }
            } catch (e: Exception) {
                brainDirty = true
                failure = if (Brain.isFull(e)) {
                    "Eso es demasiado largo para leerlo de una vez. Pídeme una parte concreta, por ejemplo un archivo de dentro."
                } else {
                    "Algo falló: ${e.message}"
                }
                ""
            }
            val failed = failure
            // Los últimos trozos llegan por el hilo principal: que se pongan antes de cerrar.
            main.post {
                thinking = false
                if (gen != generation) {
                    onCut?.invoke()
                    return@post
                }
                if (failed != null && turnIndex == -1) {
                    say(failed, onDone)
                    return@post
                }
                // Si el modelo escribió la herramienta como texto en vez de usarla, se hace por él.
                val written = WrittenCalls.handle(app, reply)
                val finalText = when {
                    written != null -> (written.remainingText + "\n\n" + written.done.joinToString(" ")).trim()
                    failed != null -> "$shown\n\n($failed)".trim()
                    else -> reply.ifBlank { shown.toString() }
                }.ifBlank { "No sé qué responder a eso." }
                shown.setLength(0)
                shown.append(finalText)
                showLive()
                status = ""
                if (voice) {
                    if (written != null) {
                        unspoken.clear()
                        speakPart(written.done.joinToString(" "))
                    } else if (!spokeAny && unspoken.isEmpty()) {
                        speakPart(finalText)
                    } else {
                        takeSentences(unspoken, final = true, first = false).forEach { speakPart(it) }
                    }
                    streamOpen = false
                    maybeFinishSpeech()
                } else {
                    onDone?.invoke()
                }
            }
        }
    }

    /** Lee el texto escrito en una foto, dentro del teléfono, y lo pone en el chat. */
    private fun readText(imagePath: String, onDone: (() -> Unit)?) {
        thinking = true
        status = "Leyendo el texto de la imagen…"
        scope.launch {
            val text = try {
                withContext(Dispatchers.Default) { Ocr.read(imagePath).trim() }
            } catch (e: Exception) {
                thinking = false
                return@launch say("No pude leer la imagen: ${e.message}", onDone)
            }
            thinking = false
            say(if (text.isEmpty()) "No veo ningún texto en esa imagen." else "Esto es lo que pone:\n\n$text", onDone)
        }
    }

    /** Si Maurizio acaba de pedir que la IA programe algo. Se llama desde el hilo del modelo. */
    fun userAskedForCode(): Boolean = codeRequest.containsMatchIn(lastUserText)

    /** Enseña en el chat una foto que la IA ha encontrado en el teléfono. Se puede llamar desde cualquier hilo. */
    fun show(name: String, imagePath: String) = main.post { turns.add(Turn("assistant", "📎 $name", image = imagePath)) }

    private fun runCodeSkill(name: String, input: String, onDone: (() -> Unit)?) {
        val code = memory.codeSkills().getValue(name).code
        thinking = true
        status = "Ejecutando \"$name\"…"
        scope.launch {
            val result = withContext(Dispatchers.Default) { SkillEngine.run(app, code, input) }
            thinking = false
            say(result, onDone)
        }
    }

    // --- Imágenes ---

    /** La imagen del último intercambio del chat: a esa se refieren "gírala" o "ponla en blanco y negro". */
    private fun recentImage(history: List<Turn>): String? =
        history.takeLast(2).lastOrNull { it.image != null }?.image?.takeIf { java.io.File(it).exists() }

    private fun image(command: ImageCommand, target: String?, onDone: (() -> Unit)?) {
        when (command) {
            is ImageCommand.Create -> {
                Studio.notReadyMessage(app)?.let { return say(it, onDone) }
                paint(onDone) {
                    val path = Studio.create(app, command.description)
                    "Aquí la tienes. También está en tu galería, en la carpeta Ultra Yo." to path
                }
            }
            is ImageCommand.AiEdit -> {
                val path = checkNotNull(target)
                Studio.notReadyMessage(app)?.let { return say(it, onDone) }
                paint(onDone) {
                    val source = withContext(Dispatchers.IO) { ImageOps.load(path) } ?: error("no pude abrir la imagen")
                    val edited = Studio.aiEdit(app, source, command.instruction, sourcePath = path)
                    "Hecho. La nueva versión también está en tu galería." to withContext(Dispatchers.IO) { Studio.keep(app, edited) }
                }
            }
            is ImageCommand.Basic -> scope.launch {
                val path = try {
                    withContext(Dispatchers.Default) {
                        val source = ImageOps.load(checkNotNull(target)) ?: error("no pude abrir la imagen")
                        Studio.keep(app, command.apply(source))
                    }
                } catch (e: Exception) {
                    return@launch say("No pude retocarla: ${e.message}", onDone)
                }
                say(command.done, onDone, image = path)
            }
            ImageCommand.OpenEditor -> {
                if (locked()) return say("Desbloquea el teléfono y te abro el editor.", onDone)
                Studio.editing = target
                say("Te abro el editor.", onDone)
            }
        }
    }

    /** Pinta con el pintor local y enseña el resultado. [work] devuelve qué decir y la ruta de la imagen. */
    private fun paint(onDone: (() -> Unit)?, work: suspend () -> Pair<String, String>) {
        thinking = true
        scope.launch {
            val result = try {
                work()
            } catch (e: Exception) {
                thinking = false
                return@launch say("No pude pintarla: ${e.message}", onDone)
            }
            thinking = false
            say(result.first, onDone, image = result.second)
        }
    }

    fun newChat() {
        stopSpeaking()
        turns.clear()
        SelfEdits.pending.value = null
        brainDirty = true
        status = ""
    }
}
