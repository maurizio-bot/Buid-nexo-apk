package com.ultrayo.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.Executors

private const val RATE = 16000

/** Trozos de 50 ms: el texto aparece casi según hablas. */
private const val CHUNK = RATE / 20

/** Lo que se guarda de antes de abrir la puerta del micrófono (0,4 s), para no perder el principio de la frase. */
private const val PREROLL_CHUNKS = 8

/** Si la puerta se abre y en 1,5 s no sale nada que no sea eco, se vuelve a cerrar. */
private const val GATE_OPEN_MS = 1500L

/** Volumen que se supone a la voz de Android (sin voz rápida no se sabe cuánto suena). */
private const val ASSUMED_REF = 0.1f

/**
 * El oído rápido de Ultra: reconocimiento de voz en español en streaming (sherpa-onnx), dentro del
 * teléfono. Va escribiendo lo que oye mientras hablas y sabe solo cuándo has terminado (0,7 s de silencio),
 * sin esperar a ningún servidor.
 */
object NeuralEar {

    @Volatile
    var ready = false
        private set

    private var recognizer: OnlineRecognizer? = null

    /**
     * La conversación y la palabra "Ultra" usan el mismo reconocedor desde hilos distintos. Cada una con
     * su propio "stream", pero por seguridad la librería nativa nunca trabaja con dos a la vez:
     * cada paso dura milisegundos, así que esperar aquí no se nota.
     */
    private val decodeLock = Any()
    private val sessions = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Cambia para cortar la escucha en curso. */
    @Volatile
    private var session = 0

    /** Terminar ya con lo que haya oído (tocar el reactor mientras escucha). */
    @Volatile
    private var finishNow = false

    @Volatile
    var active = false
        private set

    /** Dejar pausas largas al hablar (Ajustes > Voz rápida): espera más antes de dar la frase por terminada. */
    @Volatile
    var longPauses = true

    /** Carga el modelo (un segundo o dos). Se llama fuera del hilo principal. */
    @Synchronized
    fun load(context: Context) {
        if (ready || !VoiceModels.isReady(context, VoiceModels.Kind.EAR)) return
        val dir = VoiceModels.dir(context, VoiceModels.Kind.EAR)
        fun part(name: String): File =
            // Si hay versión reducida (int8), es más rápida.
            VoiceModels.find(dir) { it.name.startsWith(name) && it.name.endsWith(".int8.onnx") }
                ?: VoiceModels.find(dir) { it.name.startsWith(name) && it.name.endsWith(".onnx") }
                ?: error("Falta $name en el oído rápido")
        val tokens = VoiceModels.find(dir) { it.name == "tokens.txt" } ?: error("Falta tokens.txt")
        recognizer = OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = part("encoder").path,
                        decoder = part("decoder").path,
                        joiner = part("joiner").path,
                    ),
                    tokens = tokens.path,
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "zipformer2",
                ),
                endpointConfig = EndpointConfig(
                    // Sin decir nada: se rinde a los 6 s.
                    rule1 = EndpointRule(false, 6.0f, 0.0f),
                    // Tras hablar: 1 s de silencio es lo mínimo para pensar que has terminado (con 0,7 s
                    // cortaba a Maurizio a media frase). Encima, [run] espera algo más según [longPauses].
                    rule2 = EndpointRule(true, 1.0f, 0.0f),
                    // Una frase muy larga se corta a los 60 s.
                    rule3 = EndpointRule(false, 0.0f, 60.0f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            ),
        )
        ready = true
    }

    /**
     * Transcribe de una vez un trozo de audio ya grabado (16 kHz, de -1 a 1). Lo usa la palabra "Ultra":
     * el detector de voz corta la frase y aquí se escribe entera, sin esperar a los trozos de 1,3 s del
     * modo en streaming. Se llama fuera del hilo principal; devuelve "" si el oído no está cargado.
     */
    fun transcribe(samples: FloatArray): String {
        val rec = recognizer ?: return ""
        ErrorLog.begin("oido", "escribiendo una frase oída (oído rápido sherpa-onnx)")
        val stream = synchronized(decodeLock) { rec.createStream() }
        return try {
            stream.acceptWaveform(samples, RATE)
            // El modelo necesita algo de silencio detrás para soltar las últimas palabras.
            stream.acceptWaveform(FloatArray(RATE * 13 / 10), RATE)
            stream.inputFinished()
            synchronized(decodeLock) {
                while (rec.isReady(stream)) rec.decode(stream)
                rec.getResult(stream).text.trim()
            }
        } finally {
            synchronized(decodeLock) { stream.release() }
            ErrorLog.end("oido")
        }
    }

    /** Escucha una frase. Va enseñando lo que oye con [onPartial]. Todo se llama en el hilo principal. */
    fun listen(
        onPartial: (String) -> Unit,
        onHeard: (String) -> Unit,
        onNothing: () -> Unit,
        onFailed: (Int) -> Unit,
    ) {
        val mine = ++session
        finishNow = false
        sessions.execute {
            guarded(onFailed) {
                run(
                    mine, whileSpeaking = false, isEcho = { false }, onInterrupt = {},
                    onPartial = onPartial, onHeard = onHeard, onNothing = onNothing, onFailed = onFailed,
                )
            }
        }
    }

    /** Un fallo inesperado del oído (librería nativa, micrófono) no cierra la app: se apunta y se avisa. */
    private inline fun guarded(crossinline onFailed: (Int) -> Unit, block: () -> Unit) {
        try {
            block()
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Throwable) {
            active = false
            ErrorLog.add("Fallo del oído rápido", e)
            main.post { onFailed(-8) }
        }
    }

    /**
     * Escucha mientras Ultra habla, con cancelación de eco. Si Maurizio dice algo que no es el eco de
     * la propia voz de Ultra ([isEcho]), llama a [onInterrupt] (para callarla) y sigue escuchando
     * hasta que termine la frase, que entrega con [onHeard].
     */
    fun watch(
        isEcho: (String) -> Boolean,
        onInterrupt: () -> Unit,
        onPartial: (String) -> Unit,
        onHeard: (String) -> Unit,
        onNothing: () -> Unit,
    ) {
        val mine = ++session
        finishNow = false
        sessions.execute {
            // Si el micrófono no se puede abrir mientras habla, simplemente no se puede interrumpir con la voz.
            guarded({}) { run(
                mine, whileSpeaking = true, isEcho = isEcho, onInterrupt = onInterrupt,
                onPartial = onPartial, onHeard = onHeard, onNothing = onNothing, onFailed = {},
            ) }
        }
    }

    /** Deja de escuchar sin entregar nada. [then] se llama (en el hilo principal) cuando el micrófono ya está libre. */
    fun cancel(then: (() -> Unit)? = null) {
        session++
        if (then != null) sessions.execute { main.post { then() } }
    }

    /** Termina ya y entrega lo que haya oído. */
    fun finish() {
        finishNow = true
    }

    @SuppressLint("MissingPermission")
    private fun run(
        mine: Int,
        whileSpeaking: Boolean,
        isEcho: (String) -> Boolean,
        onInterrupt: () -> Unit,
        onPartial: (String) -> Unit,
        onHeard: (String) -> Unit,
        onNothing: () -> Unit,
        onFailed: (Int) -> Unit,
    ) {
        if (mine != session) return
        val rec = recognizer ?: return main.post { onFailed(-4) }.let {}
        val source = if (whileSpeaking) AudioRoute.micSourceWhileSpeaking() else AudioRoute.micSource()
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, CHUNK * 8))
        } catch (e: Exception) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            main.post { onFailed(-5) }
            return
        }
        record.preferredDevice = AudioRoute.input
        val effects = if (whileSpeaking) {
            listOfNotNull(
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null,
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null,
            ).onEach { runCatching { it.enabled = true } }
        } else {
            emptyList()
        }
        val stream = synchronized(decodeLock) { rec.createStream() }
        ErrorLog.begin("escucha", "escuchándote (oído rápido sherpa-onnx)")
        val pcm = ShortArray(CHUNK)
        val samples = FloatArray(CHUNK)
        var last = ""
        // Cuándo empezó el silencio que el reconocedor da por final de frase (0 = sigue hablando).
        var silenceSince = 0L
        var interrupted = !whileSpeaking
        val gate = EchoGate()
        var gateOpen = false
        var openedAt = 0L
        val preroll = ArrayDeque<FloatArray>()
        active = true
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                main.post { onFailed(-5) }
                return
            }
            while (mine == session) {
                val n = record.read(pcm, 0, CHUNK)
                if (n <= 0) {
                    main.post { onFailed(-6) }
                    return
                }
                for (i in 0 until n) samples[i] = pcm[i] / 32768f
                if (whileSpeaking && !interrupted) {
                    // Mientras Ultra habla, el oído está "sordo": lo del micrófono no le llega hasta que la
                    // puerta detecta una voz de fuera más fuerte que su eco ([EchoGate]).
                    val chunk = samples.copyOf(n)
                    val now = System.currentTimeMillis()
                    if (!gateOpen) {
                        val ref = if (NeuralVoice.ready) NeuralVoice.recentLevel() else ASSUMED_REF
                        if (!gate.feed(EchoGate.rms(chunk), ref)) {
                            preroll.addLast(chunk)
                            if (preroll.size > PREROLL_CHUNKS) preroll.removeFirst()
                            continue
                        }
                        // Se abre: el oído recibe también lo de justo antes, para no perder el principio.
                        Diag.log("puerta", String.format(java.util.Locale.ROOT, "se abre: micro %.3f, voz de Ultra %.3f, eco normal %.2f", EchoGate.rms(chunk), ref, gate.ratio))
                        gateOpen = true
                        openedAt = now
                        preroll.forEach { stream.acceptWaveform(it, RATE) }
                        preroll.clear()
                    } else if (now - openedAt > GATE_OPEN_MS) {
                        // Se abrió pero no era nadie hablando (un golpe, un ruido): se vuelve a cerrar.
                        Diag.log("puerta", "se cierra: en 1,5 s solo oyó eco o ruido (\"$last\")")
                        gateOpen = false
                        gate.close()
                        synchronized(decodeLock) { rec.reset(stream) }
                        last = ""
                        continue
                    }
                    stream.acceptWaveform(chunk, RATE)
                } else {
                    stream.acceptWaveform(if (n == CHUNK) samples else samples.copyOf(n), RATE)
                }
                val (text, endpoint) = synchronized(decodeLock) {
                    while (rec.isReady(stream)) rec.decode(stream)
                    rec.getResult(stream).text.trim() to rec.isEndpoint(stream)
                }
                if (!interrupted) {
                    // Mientras Ultra habla: solo cuenta si no es su propio eco.
                    if (text.isNotEmpty() && !isEcho(text)) {
                        Diag.log("oído", "te oye encima de Ultra y la corta: \"$text\"")
                        interrupted = true
                        main.post { if (mine == session) onInterrupt() }
                    } else if (endpoint) {
                        synchronized(decodeLock) { rec.reset(stream) }
                        last = ""
                        gateOpen = false
                        gate.close()
                        continue
                    }
                }
                if (interrupted && text != last && text.isNotEmpty()) {
                    last = text
                    silenceSince = 0L
                    if (mine == session) main.post { onPartial(tidy(text)) }
                }
                // El reconocedor cree que has terminado: se espera un poco más si la frase parece a medias
                // ("y…", "que…", "pero…") o si Maurizio quiere pausas largas. Si vuelves a hablar, sigue.
                var done = endpoint
                if (!endpoint) silenceSince = 0L
                if (interrupted && endpoint && !finishNow && text.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    if (silenceSince == 0L) silenceSince = now
                    done = now - silenceSince >= Pausas.extraWaitMs(text, longPauses)
                    if (!done && now - silenceSince < 60) Diag.log("oído", "silencio; espera ${Pausas.extraWaitMs(text, longPauses)} ms más por si sigues (\"$text\")")
                }
                if (interrupted && (done || finishNow)) {
                    if (mine == session) {
                        session++
                        val heard = tidy(text)
                        Diag.log("oído", if (heard.isBlank()) "no oyó nada" else "frase terminada${if (finishNow) " (tocaste el reactor)" else ""}: \"$heard\"")
                        main.post { if (heard.isBlank()) onNothing() else onHeard(heard) }
                    }
                    return
                }
            }
        } catch (e: Exception) {
            main.post { onFailed(-7) }
        } finally {
            active = false
            runCatching { record.stop() }
            record.release()
            effects.forEach { runCatching { it.release() } }
            synchronized(decodeLock) { stream.release() }
            ErrorLog.end("escucha")
        }
    }

    /** El modelo escribe en minúsculas y sin puntos: lo deja presentable. */
    private fun tidy(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        return t.replaceFirstChar { it.uppercase() }
    }
}

/** Cuánto esperar a que Maurizio termine de hablar (sin Android, para poder probarlo en el PC). */
object Pausas {
    /** Lo que se espera de más tras el silencio de [rule2] antes de dar la frase por terminada. */
    fun extraWaitMs(text: String, longPauses: Boolean): Long {
        var wait = if (longPauses) 700L else 0L
        if (looksUnfinished(text)) wait += 1300L
        return wait
    }

    private val unfinishedEnd = Regex(
        "\\b(y|e|o|u|ni|que|pero|porque|pues|aunque|si|cuando|como|donde|para|por|con|sin|de|del|a|al|en|el|la|los|las|un|una|unos|unas|mi|mis|tu|tus|su|sus|este|esta|ese|esa|lo|le|me|te|se|nos|mas|más|muy|también|tambien|entonces|o sea|es decir|bueno|eh|em|mm)$",
        RegexOption.IGNORE_CASE,
    )

    /** La frase acaba en una palabra que pide continuación ("quiero que…", "y luego…"). */
    fun looksUnfinished(text: String): Boolean = unfinishedEnd.containsMatchIn(text.trim().trimEnd('.', ',', ' '))
}
