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
    private val webRequest = Regex(
        "^(?:busca(?:me)?|mira|consulta|investiga)(?: en| por)? (?:internet|la web|google|la red)(?: sobre| qu[eé]| si| c[oó]mo| cu[aá]l| d[oó]nde)?[,:]?\\s*(.+)$|^(?:qu[eé] dicen en internet (?:de|sobre))\\s+(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val docsRequest = Regex(
        "^(?:busca|mira|consulta|revisa)(?: en)? (?:mis|los) (?:documentos|archivos|apuntes|notas|pdfs?)(?: sobre| qu[eé]| si| c[oó]mo| cu[aá]l| d[oó]nde| lo que)?[,:]?\\s*(.*)$|^seg[uú]n mis (?:documentos|archivos|apuntes|notas)[,:]?\\s*(.*)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
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
                        val gpu = GpuGuard.beforeLoad(app)
                        Ai.brain = Brain(
                            downloader.file.path,
                            app.cacheDir.path,
                            listOf(PhoneTools(app), ControlTools(app), KnowledgeTools(app), DataTools(app), SkillTools(app), SelfTools(app)),
                        ).also {
                            it.load(useGpu = gpu)
                            GpuGuard.afterLoad(app, tried = gpu, onGpu = it.onGpu)
                        }
                    }
                }
                brainModel = downloader.model
                brainReady = true
                brainDirty = true
                status = ""
                onReady()
            } catch (e: Exception) {
                status = "No pude cargar el modelo: ${e.message}"
                // Si falla un cerebro nuevo (por ejemplo uno propio mal convertido), vuelve al ligero.
                if (downloader.model != BrainModel.LIGHT && ModelDownloader(app, BrainModel.LIGHT).isReady()) {
                    BrainModel.select(app, BrainModel.LIGHT)
                    status = "No pude cargar ${downloader.model.label} (${e.message}). Vuelvo al ligero."
                    scope.launch { loadBrain(onReady) }
                }
            } finally {
                loading = false
            }
        }
    }

    /** El cerebro cargado ahora (para enseñarlo en Ajustes). */
    var brainModel by mutableStateOf<BrainModel?>(null)

    /** Cambia de cerebro: suelta el actual y carga el que esté elegido. */
    fun reloadBrain(onReady: () -> Unit = {}) {
        if (loading) return
        stopSpeaking()
        brainReady = false
        scope.launch {
            withContext(Ai.thread) {
                Ai.brain?.close()
                Ai.brain = null
            }
            brainDirty = true
            loadBrain(onReady)
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
        listenWithAndroid(onHeard, onNothing, onFailed, retried = false)
    }

    /** Si pedir el reconocimiento sin red falló una vez en este teléfono, ya no se pide. */
    private var offlineFails = false

    /**
     * Escucha una frase. Si el reconocedor falla al arrancar (error 5 de cliente, ocupado, idioma sin
     * red…), lo cierra, crea uno nuevo y lo intenta otra vez sin pedir modo sin red.
     */
    private fun listenWithAndroid(
        onHeard: (String) -> Unit,
        onNothing: () -> Unit,
        onFailed: (Int) -> Unit,
        retried: Boolean,
    ) {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(app).also { recognizer = it }
        var heardSomething = false
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
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        if (retried || heardSomething) {
                            onFailed(error)
                        } else {
                            offlineFails = true
                            recognizer?.destroy()
                            recognizer = null
                            main.postDelayed({ listenWithAndroid(onHeard, onNothing, onFailed, retried = true) }, 250)
                        }
                    }
                    else -> onFailed(error)
                }
            }

            override fun onBeginningOfSpeech() {
                heardSomething = true
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            // Reconocer dentro del teléfono si se puede (no espera a la red), salvo que ya haya fallado.
            if (!offlineFails && !retried) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
                status = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Necesito permiso para usar el micrófono."
                    SpeechRecognizer.ERROR_AUDIO -> "Otra app está usando el micrófono."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        "El reconocedor de voz necesita internet o el paquete de español sin conexión."
                    else -> "El reconocedor de voz no arrancó ($error). Toca el reactor otra vez."
                }
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
            Expert.questionIn(text)?.let { question ->
                askExpert(question, history, onDone)
                return
            }
            // Cuentas: exactas y al instante, sin pasar por el modelo.
            Calculator.fromSpeech(command)?.let { expression ->
                runCatching { Calculator.format(Calculator.eval(expression)) }.getOrNull()?.let { result ->
                    say("Son $result.", onDone)
                    return
                }
            }
            webRequest.find(command)?.let { match ->
                val query = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty().trim()
                if (query.length > 1) {
                    retrieveThenAsk(text, history, onDone, "Buscando en internet…") {
                        val results = WebSearch.search(query)
                        if (results.isEmpty()) null
                        else "Resultados de internet para \"$query\":\n${results.joinToString("\n")}\n\n" +
                            "Pregunta de Maurizio: $text\n\nContesta con lo que dicen estos resultados, sin inventar nada que no esté en " +
                            "ellos, y di de qué web sale. Si no bastan, dilo y usa leerWeb con el enlace más prometedor."
                    }
                    return
                }
            }
            docsRequest.find(command)?.let { match ->
                val query = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim() ?: text
                retrieveThenAsk(text, history, onDone, "Buscando en tus documentos…") {
                    "Fragmentos de los documentos de Maurizio:\n${DocIndex.formatted(app, query)}\n\nPregunta de Maurizio: $text\n\n" +
                        "Contesta solo con lo que digan esos fragmentos y cita el archivo entre paréntesis, por ejemplo (según factura.pdf). " +
                        "Si no está ahí, dilo claro en vez de inventarlo."
                }
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

        val prompt = if (attached?.text != null) {
            "$text\n\n[Archivo adjunto: ${attached.name}]\n${attached.text}"
        } else {
            text
        }
        askModel(text, prompt, attached?.imagePath, history, onDone)
    }

    /**
     * Pregunta al modelo del teléfono y va enseñando y diciendo la respuesta según la escribe.
     * [text] es lo que dijo Maurizio y [prompt] lo que se le pasa al modelo (con adjuntos o fragmentos).
     * En las preguntas difíciles le pide que piense antes en privado: ese razonamiento no se enseña ni se dice.
     */
    private fun askModel(text: String, prompt0: String, image: String?, history: List<Turn>, onDone: (() -> Unit)?) {
        val brain = Ai.brain
        if (brain == null) {
            say("Todavía me estoy despertando. Dame un momento.", onDone)
            return
        }
        if (image != null && !brain.visionEnabled) {
            say("Todavía no puedo ver fotos en este teléfono. Si quieres, descríbemela con palabras.", onDone)
            return
        }
        val deep = Thinking.isHard(text)
        val prompt = if (deep) Thinking.wrap(prompt0) else prompt0
        thinking = true
        status = if (deep) "Pensando a fondo…" else "Pensando…"
        val reset = brainDirty
        brainDirty = false
        val systemPrompt = memory.systemPrompt()

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

        val hidden = Thinking.Filter()
        val onChunk: (String) -> Unit = { raw ->
            main.post {
                if (gen != generation) return@post
                val delta = hidden.feed(raw)
                if (delta.isEmpty()) return@post
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
            val rawReply = try {
                withContext(Ai.thread) {
                    try {
                        if (reset) brain.reset(systemPrompt, history)
                        brain.askStreaming(prompt, image, onChunk).also { GpuGuard.answered(app) }
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
                val reply = Thinking.strip(rawReply)
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
                if (failed == null && finalText.isNotBlank()) TrainingLog.add(app, text, finalText, "local")
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


    /**
     * Primero consigue datos ([fetch]: internet o sus documentos, fuera del hilo principal) y luego
     * le pasa al modelo la pregunta junto con ellos, para que conteste con fuentes y sin inventar.
     */
    private fun retrieveThenAsk(text: String, history: List<Turn>, onDone: (() -> Unit)?, working: String, fetch: () -> String?) {
        if (Ai.brain == null) return say("Todavía me estoy despertando. Dame un momento.", onDone)
        thinking = true
        status = working
        scope.launch {
            val prompt = runCatching { withContext(Dispatchers.IO) { fetch() } }.getOrNull()
            thinking = false
            if (prompt == null) {
                say("No encontré nada (¿hay conexión a internet?). Te contesto con lo que sé, puede que no esté al día.", onDone = {
                    askModel(text, text, null, history, onDone)
                })
                return@launch
            }
            askModel(text, prompt, null, history, onDone)
        }
    }

    /** Modo experto: solo cuando Maurizio lo pide. Pregunta al modelo grande en la nube. */
    private fun askExpert(question: String, history: List<Turn>, onDone: (() -> Unit)?) {
        Expert.notReady(app)?.let { return say(it, onDone) }
        thinking = true
        status = "Consultando al experto en la nube…"
        val systemPrompt = memory.systemPrompt()
        scope.launch {
            val answer = runCatching { withContext(Dispatchers.IO) { Expert.ask(app, systemPrompt, history, question) } }
            thinking = false
            answer.fold(
                onSuccess = {
                    TrainingLog.add(app, question, it, "experto")
                    // Que Ultra en el teléfono también sepa lo que contestó el experto.
                    brainDirty = true
                    say("🎓 $it", onDone)
                },
                onFailure = { say("El experto no pudo contestar: ${it.message}", onDone) },
            )
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
