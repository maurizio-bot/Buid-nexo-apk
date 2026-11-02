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
            try {
                engine.generateWithCallback(text, sid = 0, speed = speed) { samples ->
                    if (gen != generation) return@generateWithCallback 0
                    playQueue.put(Chunk(gen, samples.copyOf()))
                    1
                }
            } catch (_: Throwable) {
            }
            if (gen == generation) playQueue.put(End(gen, id)) else main.post { onDone?.invoke(id) }
        }
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
            offset += written
            if (offset < samples.size) Thread.sleep(8)
        }
    }
}
