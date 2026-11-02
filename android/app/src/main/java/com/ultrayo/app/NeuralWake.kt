package com.ultrayo.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.text.Normalizer

private const val RATE = 16000

/** Ventanas de 32 ms: lo que necesita el detector de voz. */
private const val WINDOW = 512

/** Se guardan los últimos 12 s de audio para poder recortar cada frase con un poco de margen. */
private const val RING = RATE * 12

/** Margen antes del inicio de la frase: el detector de voz arranca con un poco de retraso. */
private const val PRE_ROLL = RATE * 3 / 10

/** Frases más cortas que esto (un golpe, una tos) ni se miran. */
private const val MIN_SPEECH = RATE / 4

/** De una frase larga solo se mira el principio; el resto, solo si ahí estaba "Ultra". */
private const val FIRST_LOOK = RATE * 5 / 2

/**
 * La palabra "Ultra" con la pantalla apagada, rápida y con pocas falsas alarmas.
 *
 * Dos pasos: un detector de voz muy pequeño (Silero, 640 KB) escucha todo el rato y apenas gasta; solo
 * cuando alguien habla y termina la frase, el oído rápido en español (el mismo de la conversación) la
 * escribe y se mira si empieza por "Ultra". Así:
 * - en silencio solo trabaja el detector de voz (unos milisegundos por segundo de audio);
 * - "Ultra, ¿qué hora es?" dicho de un tirón llega entero y se contesta sin volver a escuchar;
 * - "ultravioleta", "cultura", "otra" o "última" no la despiertan.
 * Escucha por el micrófono del teléfono, también con un coche o unos cascos conectados (la música sigue).
 */
object NeuralWake {

    private val main = Handler(Looper.getMainLooper())
    private var vad: Vad? = null
    private var thread: Thread? = null

    @Volatile
    private var session = 0

    /** Si se puede usar: el oído rápido está descargado y el detector de voz va en el APK. */
    fun available(context: Context): Boolean =
        VoiceModels.isReady(context, VoiceModels.Kind.EAR) &&
            runCatching { context.assets.open("silero_vad.onnx").close(); true }.getOrDefault(false)

    /** Carga lo que haga falta (uno o dos segundos). Se llama fuera del hilo principal. */
    @Synchronized
    fun load(context: Context) {
        NeuralEar.load(context)
        if (vad == null) {
            vad = Vad(
                assetManager = context.assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 0.25f,
                        minSpeechDuration = 0.15f,
                        windowSize = WINDOW,
                        maxSpeechDuration = 8f,
                    ),
                    sampleRate = RATE,
                    numThreads = 1,
                ),
            )
        }
    }

    val ready get() = vad != null && NeuralEar.ready

    /**
     * Empieza a esperar "Ultra". Al oírla llama a [onWake] (en el hilo principal) con lo que dijo
     * después, que puede estar vacío, y deja el micrófono libre. Si el micrófono no se puede usar, [onFailed].
     */
    fun start(onWake: (String) -> Unit, onFailed: () -> Unit) {
        stop()
        val mine = ++session
        thread = Thread({ run(mine, onWake, onFailed) }, "ultra-palabra").apply {
            // Que no le quite tiempo a nada importante.
            priority = Thread.MIN_PRIORITY + 1
            start()
        }
    }

    /** Deja de esperar "Ultra" y suelta el micrófono (espera a que esté libre). */
    fun stop() {
        session++
        val t = thread ?: return
        thread = null
        if (t !== Thread.currentThread()) runCatching { t.join(1500) }
    }

    @SuppressLint("MissingPermission")
    private fun run(mine: Int, onWake: (String) -> Unit, onFailed: () -> Unit) {
        val detector = vad ?: return main.post { if (mine == session) onFailed() }.let {}
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            // Siempre el micrófono del teléfono: abrir el del coche cortaría la música (ver Bluetooth.kt).
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, WINDOW * 16),
            )
        } catch (e: Exception) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            main.post { if (mine == session) onFailed() }
            return
        }
        val ring = FloatArray(RING)
        var total = 0L
        val pcm = ShortArray(WINDOW)
        val window = FloatArray(WINDOW)
        detector.reset()
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                main.post { if (mine == session) onFailed() }
                return
            }
            var filled = 0
            while (mine == session) {
                val n = record.read(pcm, filled, WINDOW - filled)
                if (n < 0) {
                    main.post { if (mine == session) onFailed() }
                    return
                }
                filled += n
                if (filled < WINDOW) continue
                filled = 0
                for (i in 0 until WINDOW) {
                    val v = pcm[i] / 32768f
                    window[i] = v
                    ring[((total + i) % RING).toInt()] = v
                }
                total += WINDOW
                detector.acceptWaveform(window)
                while (!detector.empty() && mine == session) {
                    val segment = detector.front()
                    detector.pop()
                    val heard = check(segment.start.toLong(), segment.samples, ring, total) ?: continue
                    if (mine != session) return
                    session++
                    ErrorLog.add("(no es error) Despertó con la palabra \"Ultra\" al oír: \"${heard.second}\"")
                    main.post { onWake(heard.first) }
                    return
                }
            }
        } catch (e: Exception) {
            ErrorLog.add("Escucha de la palabra \"Ultra\"", e)
            main.post { if (mine == session) onFailed() }
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { detector.reset() }
        }
    }

    /** Escribe la frase y mira si es para Ultra. Devuelve (lo que dijo después de "Ultra", la frase entera). */
    private fun check(start: Long, samples: FloatArray, ring: FloatArray, total: Long): Pair<String, String>? {
        if (samples.size < MIN_SPEECH) return null
        // Recorta del audio guardado con un poco de margen delante (si todavía está guardado).
        val from = maxOf(0L, start - PRE_ROLL)
        val end = start + samples.size
        val audio = if (end <= total && from >= total - RING) {
            FloatArray((end - from).toInt()) { ring[((from + it) % RING).toInt()] }
        } else {
            samples
        }
        // Primero solo el principio: así una tele o una conversación larga gastan poco.
        if (audio.size > FIRST_LOOK + PRE_ROLL) {
            val head = NeuralEar.transcribe(audio.copyOf(FIRST_LOOK + PRE_ROLL))
            if (WakeWord.find(head) == null) return null
        }
        val text = NeuralEar.transcribe(audio)
        val rest = WakeWord.find(text) ?: return null
        return rest to text
    }
}

/** Decide si una frase escrita es para Ultra y qué viene detrás. */
object WakeWord {

    /** Palabras que van detrás de "ultra" cuando no la llaman a ella. */
    private val notForHer = setOf(
        "violeta", "violetas", "sonido", "sonidos", "marino", "marinos", "ligero", "ligera", "rapido", "rapida",
        "derecha", "derechista", "fino", "fina", "procesado", "procesados", "maraton", "secreto", "pasteurizada",
        "congelado", "resistente", "moderno", "conservador",
    )

    /** Palabras normales que se parecen a "ultra" y no lo son. */
    private val lookAlikes = setOf("otra", "otras", "contra", "oculta", "culta", "cultura", "ultima", "ultimo", "lustra", "multa")

    /**
     * Si "Ultra" (o como el oído suele escribirla mal: "ulta", "ultras", "cultra"…) está entre las cuatro
     * primeras palabras, devuelve lo que viene detrás tal y como lo escribió el oído (puede ser ""). Si no, null.
     */
    fun find(text: String): String? {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(text).toList()
        for ((i, w) in words.take(4).withIndex()) {
            val word = plain(w.value)
            if (word in lookAlikes || word.length < 4 || distance(word, "ultra") > 1) continue
            val next = words.getOrNull(i + 1)?.let { plain(it.value) }
            if (next != null && next in notForHer) continue
            var rest = text.substring(w.range.last + 1).trimStart(' ', ',', '.', ';', ':', '!', '¡', '?', '¿', '-')
            // "Ultra Yo" es su nombre completo.
            if (next == "yo") rest = rest.substringAfter(' ', "").takeIf { it.isNotBlank() } ?: ""
            return rest.trim()
        }
        return null
    }

    private fun plain(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
