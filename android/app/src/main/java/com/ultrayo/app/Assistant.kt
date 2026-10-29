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
        brainReady = Ai.brain != null
        tts = TextToSpeech(app) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("es-ES")
                ttsReady = true
            }
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = main.post { finishSpeech() }.let {}
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = main.post { finishSpeech() }.let {}
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
                            listOf(PhoneTools(app), SelfTools(app)),
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
        afterSpeech = onDone
        speaking = true
        val spoken = text.replace(Regex("[*#_`>|]"), "")
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    fun stopSpeaking() {
        if (speaking) {
            tts?.stop()
            afterSpeech = null
            speaking = false
        }
    }

    private fun finishSpeech() {
        speaking = false
        val next = afterSpeech
        afterSpeech = null
        next?.invoke()
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

        if (attached == null) {
            val command = text.trim()
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
            ClaudeLink.questionIn(text)?.let { question ->
                say(if (locked()) "Desbloquea el teléfono y te paso a Claude." else ClaudeLink.open(app, question), onDone)
                return
            }
            Teaching.handle(text, memory)?.let { answer ->
                brainDirty = true
                say(answer, onDone)
                return
            }
        }

        if (attached == null || attached.imagePath != null) {
            val target = attached?.imagePath ?: recentImage(history)
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
        scope.launch {
            val reply = try {
                withContext(Ai.thread) {
                    if (reset) brain.reset(systemPrompt, history)
                    brain.ask(prompt, attached?.imagePath)
                }
            } catch (e: Exception) {
                brainDirty = true
                "Algo falló: ${e.message}"
            }
            thinking = false
            say(reply.ifBlank { "No sé qué responder a eso." }, onDone)
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
