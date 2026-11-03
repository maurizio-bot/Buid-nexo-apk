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
                    // Tras hablar: 0,7 s de silencio basta para saber que has terminado.
                    rule2 = EndpointRule(true, 0.7f, 0.0f),
                    // Una frase muy larga se corta a los 30 s.
                    rule3 = EndpointRule(false, 0.0f, 30.0f),
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
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, RATE)
            // El modelo necesita algo de silencio detrás para soltar las últimas palabras.
            stream.acceptWaveform(FloatArray(RATE * 13 / 10), RATE)
            stream.inputFinished()
            while (rec.isReady(stream)) rec.decode(stream)
            rec.getResult(stream).text.trim()
        } finally {
            stream.release()
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
        val stream = rec.createStream()
        val pcm = ShortArray(CHUNK)
        val samples = FloatArray(CHUNK)
        var last = ""
        var interrupted = !whileSpeaking
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
                stream.acceptWaveform(if (n == CHUNK) samples else samples.copyOf(n), RATE)
                while (rec.isReady(stream)) rec.decode(stream)
                val text = rec.getResult(stream).text.trim()
                val endpoint = rec.isEndpoint(stream)
                if (!interrupted) {
                    // Mientras Ultra habla: solo cuenta si no es su propio eco.
                    if (text.isNotEmpty() && !isEcho(text)) {
                        interrupted = true
                        main.post { if (mine == session) onInterrupt() }
                    } else if (endpoint) {
                        rec.reset(stream)
                        last = ""
                        continue
                    }
                }
                if (interrupted && text != last && text.isNotEmpty()) {
                    last = text
                    if (mine == session) main.post { onPartial(tidy(text)) }
                }
                if (interrupted && (endpoint || finishNow)) {
                    if (mine == session) {
                        session++
                        val heard = tidy(text)
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
            stream.release()
        }
    }

    /** El modelo escribe en minúsculas y sin puntos: lo deja presentable. */
    private fun tidy(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        return t.replaceFirstChar { it.uppercase() }
    }
}
