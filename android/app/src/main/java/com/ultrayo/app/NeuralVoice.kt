package com.ultrayo.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * La voz neural de Ultra (Piper con sherpa-onnx), dentro del teléfono y sin internet.
 *
 * Va en cadena para que empiece a hablar cuanto antes: un hilo convierte cada frase en audio
 * mientras otro reproduce la anterior. [stop] lo corta todo al instante.
 */
object NeuralVoice {

    @Volatile
    var ready = false
        private set

    /** Se llama en el hilo principal cuando termina de sonar cada frase (con su identificador). */
    var onDone: ((String) -> Unit)? = null

    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null
    private val generator = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private sealed interface Item { val gen: Int }
    private class Chunk(override val gen: Int, val samples: FloatArray) : Item
    private class End(override val gen: Int, val id: String) : Item

    private val playQueue = LinkedBlockingQueue<Item>()
    private val lock = Object()

    /** Cambia con cada [stop]: lo que se generó antes ya no suena. */
    @Volatile
    private var generation = 0
    private var framesWritten = 0L

    /** Posición del reproductor justo después del último corte (cuenta desde ahí). */
    private var baseHead = 0L
    private var sampleRate = 22050

    /** Carga el modelo (tarda un segundo). Se llama fuera del hilo principal. */
    fun load(context: Context) {
        if (ready || !VoiceModels.isReady(context, VoiceModels.Kind.VOICE)) return
        val dir = VoiceModels.dir(context, VoiceModels.Kind.VOICE)
        val model = VoiceModels.find(dir) { it.name.endsWith(".onnx") } ?: error("Falta el modelo de voz")
        val tokens = VoiceModels.find(dir) { it.name == "tokens.txt" } ?: error("Falta tokens.txt")
        val espeak = File(dir, "espeak-ng-data").takeIf { it.isDirectory }
            ?: dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }
            ?: error("Falta espeak-ng-data")
        val engine = OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = model.path,
                        tokens = tokens.path,
                        dataDir = espeak.path,
                        // Menos "ruido" que los valores de fábrica (0,667 y 0,8): pronuncia más estable y
                        // se entiende mejor, a cambio de sonar un poco menos expresiva.
                        noiseScale = 0.5f,
                        noiseScaleW = 0.6f,
                    ),
                    numThreads = 2,
                    provider = "cpu",
                ),
                maxNumSentences = 1,
            ),
        )
        // La primera vez el motor va más lento: se calienta con una palabra que no suena.
        runCatching { engine.generate("Hola.", sid = 0, speed = 1f) }
        val rate = engine.sampleRate()
        tts = engine
        sampleRate = rate
        track = buildTrack(rate)
        ready = true
        Thread(::playLoop, "voz-neural").apply { isDaemon = true }.start()
    }

    /** La voz va por el canal de llamadas si el manos libres está enlazado ([AudioRoute.callMode]). */
    private var trackCallMode = false

    private fun buildTrack(rate: Int): AudioTrack {
        trackCallMode = AudioRoute.callMode
        val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioRoute.speechAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, rate * 4 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    /** Si se ha enlazado o soltado el manos libres desde la última frase, rehace el reproductor. Con [lock]. */
    private fun currentTrack(): AudioTrack? {
        val old = track ?: return null
        if (trackCallMode == AudioRoute.callMode || old.playState == AudioTrack.PLAYSTATE_PLAYING) return old
        runCatching { old.release() }
        return buildTrack(sampleRate).also {
            track = it
            framesWritten = 0
            baseHead = 0
        }
    }

    /** Pone [text] en la cola: suena en cuanto acabe lo anterior. [speed] 1 = normal. */
    fun speak(text: String, id: String, speed: Float) {
        val gen = generation
        generator.execute {
            val engine = tts
            if (engine == null || gen != generation) return@execute
            ErrorLog.begin("voz", "hablando con la voz rápida (sherpa-onnx Piper)")
            try {
                engine.generateWithCallback(text, sid = 0, speed = speed, callback = TtsCallback { samples ->
                    if (gen != generation) return@TtsCallback 0
                    playQueue.put(Chunk(gen, samples.copyOf()))
                    1
                })
            } catch (e: Throwable) {
                ErrorLog.add("Fallo de la voz rápida", e)
            } finally {
                ErrorLog.end("voz")
            }
            if (gen == generation) playQueue.put(End(gen, id)) else main.post { onDone?.invoke(id) }
        }
    }

    // --- Volumen de lo que está sonando (para la puerta del micrófono, [EchoGate]) ---

    private val levelTimes = LongArray(512)
    private val levelValues = FloatArray(512)
    private var levelIndex = 0

    private fun noteLevel(rms: Float) = synchronized(levelTimes) {
        levelTimes[levelIndex] = System.currentTimeMillis()
        levelValues[levelIndex] = rms
        levelIndex = (levelIndex + 1) % levelTimes.size
    }

    /**
     * El volumen más alto de lo que se ha mandado al altavoz en el último segundo y pico. Se escribe hasta
     * 0,8 s antes de que suene, así que esta ventana cubre lo que está sonando ahora y su eco en la sala.
     */
    fun recentLevel(windowMs: Long = 1500): Float = synchronized(levelTimes) {
        val since = System.currentTimeMillis() - windowMs
        var max = 0f
        for (i in levelTimes.indices) if (levelTimes[i] >= since && levelValues[i] > max) max = levelValues[i]
        max
    }

    /** Corta la voz al instante. */
    fun stop() {
        synchronized(lock) {
            generation++
            playQueue.clear()
            track?.let {
                runCatching {
                    it.pause()
                    it.flush()
                }
            }
            framesWritten = 0
            baseHead = track?.let { head(it) } ?: 0L
        }
    }

    fun setOutput() {
        track?.preferredDevice = AudioRoute.output
    }

    private fun playLoop() {
        while (true) {
            val item = playQueue.take()
            val audio = synchronized(lock) { track } ?: continue
            // Un fallo del altavoz (AudioTrack) no debe matar este hilo ni cerrar la app.
            try {
                play(item, audio)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                ErrorLog.add("Fallo al reproducir la voz", e)
                if (item is End) main.post { onDone?.invoke(item.id) }
            }
        }
    }

    private fun play(item: Item, audio: AudioTrack) {
        when (item) {
            is Chunk -> write(item)
            is End -> {
                // Espera a que suene de verdad el final de la frase (con un límite por si el audio se atasca).
                val deadline = System.currentTimeMillis() + 2000 + synchronized(lock) { framesWritten } * 1000 / sampleRate
                while (System.currentTimeMillis() < deadline) {
                    val pending = synchronized(lock) {
                        item.gen == generation && head(audio) - baseHead < framesWritten
                    }
                    if (!pending) break
                    Thread.sleep(10)
                }
                synchronized(lock) {
                    if (item.gen == generation && playQueue.isEmpty()) {
                        runCatching { audio.pause() }
                    }
                }
                main.post { onDone?.invoke(item.id) }
            }
        }
    }

    private fun head(audio: AudioTrack): Long = audio.playbackHeadPosition.toLong() and 0xffffffffL

    private fun write(chunk: Chunk) {
        var offset = 0
        val samples = chunk.samples
        while (offset < samples.size) {
            val written = synchronized(lock) {
                if (chunk.gen != generation) return
                val audio = currentTrack() ?: return
                if (audio.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    audio.preferredDevice = AudioRoute.output
                    audio.play()
                }
                audio.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING).also {
                    if (it > 0) framesWritten += it
                }
            }
            if (written < 0) return
            if (written > 0) noteLevel(EchoGate.rms(samples, offset, offset + written))
            offset += written
            if (offset < samples.size) Thread.sleep(8)
        }
    }
}

/**
 * El trozo de código que la librería de voz (sherpa-onnx) llama por cada pedazo de audio.
 *
 * Tiene que ser una clase de verdad y no una lambda normal: la librería nativa busca a mano el método
 * `invoke(float[]): Integer` en la clase del objeto. Kotlin 2 compila las lambdas de otra forma
 * (invokedynamic) y esa clase solo tiene `invoke(Object): Object`, así que la librería no lo encontraba
 * y la app se cerraba de golpe al hablar (NoSuchMethodError dentro de JNI → SIGABRT). Ver TtsCallbackTest.
 */
class TtsCallback(private val block: (FloatArray) -> Int) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = block(samples)
}

/**
 * Deja el texto listo para decirlo en voz alta. La voz (espeak + Piper) leía mal los símbolos, los
 * emojis y las abreviaturas, y eso sonaba a ruido o a palabras que no se entendían.
 */
object Habla {
    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")
    private val replacements = listOf(
        // Las viñetas de las listas, antes que los guiones sueltos.
        Regex("^\\s*[-•·]\\s+", RegexOption.MULTILINE) to "",
        Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE) to "",
        Regex("(\\d)\\s*%") to "$1 por ciento",
        Regex("(\\d)\\s*€") to "$1 euros",
        Regex("€\\s*(\\d+(?:[.,]\\d+)?)") to "$1 euros",
        Regex("\\$\\s*(\\d+(?:[.,]\\d+)?)") to "$1 dólares",
        Regex("(\\d)\\s*km/h\\b") to "$1 kilómetros por hora",
        Regex("(\\d)\\s*km\\b") to "$1 kilómetros",
        Regex("(\\d)\\s*kg\\b") to "$1 kilos",
        Regex("(\\d)\\s*cm\\b") to "$1 centímetros",
        Regex("(\\d)\\s*ml\\b") to "$1 mililitros",
        Regex("(\\d)\\s*GB\\b") to "$1 gigas",
        Regex("(\\d)\\s*MB\\b") to "$1 megas",
        Regex("(\\d)\\s*[º°]C\\b") to "$1 grados",
        Regex("(\\d)\\s*[º°]") to "$1 grados",
        Regex("\\bp\\. ?ej\\.", RegexOption.IGNORE_CASE) to "por ejemplo",
        Regex("\\betc\\.", RegexOption.IGNORE_CASE) to "etcétera",
        Regex("\\bnº\\s*", RegexOption.IGNORE_CASE) to "número ",
        Regex("\\s&\\s") to " y ",
        Regex("(\\d)\\s*\\+\\s*(\\d)") to "$1 más $2",
        Regex("(\\d)\\s*=\\s*(\\d)") to "$1 igual a $2",
        Regex("(\\d)\\s*[x×]\\s*(\\d)") to "$1 por $2",
        Regex("\\s*(→|->|=>)\\s*") to ", ",
        Regex("\\s+[-–—]\\s+") to ", ",
        Regex("[()\\[\\]{}\"«»“”]") to " ",
        Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{2,4})\\b") to "$1 del $2 de $3",
        Regex("(?<=\\p{L})\\s*/\\s*(?=\\p{L})") to " o ",
        Regex("[*#_`>|~^]") to "",
        Regex("https?://\\S+") to "el enlace",
    )

    fun limpiar(text: String): String {
        var t = text.replace(emoji, "")
        // Los enlaces primero, para que sus barras no se lean como "o".
        t = t.replace(Regex("https?://\\S+"), "el enlace")
        for ((regex, value) in replacements) t = t.replace(regex, value)
        return t.replace(Regex("\\s*\\n+\\s*"), ". ").replace(Regex("\\s{2,}"), " ")
            .replace(Regex("\\s+([,.;:!?])"), "$1").replace(Regex("([,.]){2,}"), "$1").trim()
    }
}
