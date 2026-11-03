package com.ultrayo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.Executors

private const val CHANNEL = "escucha"
private const val NOTIFICATION_ID = 1
private const val ACTION_STOP = "com.ultrayo.app.PARAR"
private const val SAMPLE_RATE = 16000f
private const val WAKE_GRAMMAR = "[\"ultra\", \"oye ultra\", \"hola ultra\", \"[unk]\"]"
private const val VOSK_LISTEN_MS = 8000

/**
 * Escucha "Ultra" todo el tiempo, también con la app cerrada o el teléfono bloqueado,
 * y entonces conversa por voz. Todo el reconocimiento de la palabra ocurre en el teléfono.
 */
class UltraService : Service() {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Está conversando: no hay que escuchar la palabra mientras tanto. */
    private var busy = false

    /** Cuenta las conversaciones, para saber si un corte es de la actual. */
    private var session = 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** El reconocedor de Android no puede usar el micrófono desde aquí: usar el de Vosk. */
    private var useVoskEar = false

    /** La palabra se escucha con el detector de voz + oído rápido (NeuralWake), no con Vosk. */
    private var neural = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Assistant.init(this)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // Android solo deja empezar a escuchar con la app abierta.
            stopSelf()
            return
        }
        instance = this
        // Si Maurizio corta la respuesta (Parar, tocar el reactor), volver a esperar "Ultra".
        Assistant.onCut = {
            val mine = session
            main.postDelayed({
                if (instance === this && busy && session == mine &&
                    !Assistant.listening && !Assistant.thinking && !Assistant.speaking
                ) {
                    busy = false
                    startWake()
                }
            }, 500)
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ultrayo:escucha")
            .apply { acquire() }
        Assistant.loadBrain()
        loader.execute {
            // Primero la forma rápida (detector de voz + oído rápido); si no está, Vosk como antes.
            val fast = NeuralWake.available(this) && runCatching { NeuralWake.load(this) }
                .onFailure { ErrorLog.add("No pude cargar la palabra \"Ultra\" rápida", it) }.isSuccess
            if (fast) {
                ContextCompat.getMainExecutor(this).execute {
                    if (instance === this) {
                        neural = true
                        startWake()
                    }
                }
                return@execute
            }
            val loaded = try {
                if (WakeModel.isReady(this)) Model(WakeModel.dir(this).path) else null
            } catch (e: Throwable) {
                // También si falta la librería nativa de Vosk (UnsatisfiedLinkError).
                ErrorLog.add("No pude cargar el oído de la palabra \"Ultra\"", e)
                null
            }
            ContextCompat.getMainExecutor(this).execute {
                if (loaded == null) {
                    Assistant.status = "No pude cargar el oído. Vuelve a activarlo en ajustes."
                    stopSelf()
                } else if (instance === this) {
                    model = loaded
                    startWake()
                } else {
                    loaded.close()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Assistant.memory.wakeWord = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            Assistant.onCut = null
        }
        stopVosk()
        NeuralWake.stop()
        model?.close()
        model = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // --- La palabra "Ultra" ---

    private var wakeFailures = 0

    private fun startWake() {
        if (busy) return
        if (neural) {
            NeuralWake.start(
                onWake = { rest ->
                    wakeFailures = 0
                    wake(rest)
                },
                onFailed = {
                    // Otra app tiene el micrófono (una llamada, la grabadora…): reintentar en un rato.
                    wakeFailures++
                    if (wakeFailures == 3) Assistant.status = "Otra app está usando el micrófono; vuelvo a escuchar \"Ultra\" cuando lo suelte."
                    main.postDelayed({ if (instance === this && !busy) startWake() }, minOf(30_000L, 2_000L * wakeFailures))
                },
            )
            return
        }
        val model = model ?: return
        stopVosk()
        val rec = Recognizer(model, SAMPLE_RATE, WAKE_GRAMMAR)
        recognizer = rec
        speech = SpeechService(rec, SAMPLE_RATE).also {
            it.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
                override fun onResult(hypothesis: String?) = check(hypothesis, "text")
                override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
                override fun onError(exception: Exception?) {
                    Assistant.status = "El oído falló: ${exception?.message}"
                }
                override fun onTimeout() {}
            })
        }
    }

    private fun check(hypothesis: String?, field: String) {
        val heard = runCatching { JSONObject(hypothesis ?: "{}").optString(field) }.getOrDefault("")
        if (heard.contains("ultra")) wake()
    }

    /**
     * Empieza a conversar: al oír "Ultra" o al tocar el reactor. [said] es lo que dijo detrás de
     * "Ultra" de un tirón ("Ultra, ¿qué hora es?"): si hay algo, se contesta directamente sin volver a escuchar.
     */
    fun wake(said: String = "", beep: Boolean = true) {
        // No hacer caso de su propia voz (por ejemplo cuando dice "Ultra Yo"). Si estaba "ocupado"
        // pero ya no habla, ni piensa, ni escucha, es que lo cortaron a medias: se puede volver a empezar.
        if (Assistant.speaking || Assistant.thinking || Assistant.listening) return
        busy = true
        val mine = ++session
        stopVosk()
        NeuralWake.stop()
        val command = said.trim().takeIf { it.count(Char::isLetterOrDigit) >= 2 }
        if (command == null && beep) beep()
        val first = Ear { onHeard, onNothing, onFailed ->
            // La primera vez entrega lo que ya dijo con "Ultra"; luego, a escuchar como siempre.
            val pending = pendingCommand
            pendingCommand = null
            if (pending != null) onHeard(pending) else ear.listen(onHeard, onNothing, onFailed)
        }
        pendingCommand = command?.replaceFirstChar { it.uppercase() }
        Assistant.startConversation(first) {
            if (session == mine) {
                busy = false
                startWake()
            }
        }
    }

    private var pendingCommand: String? = null

    /** Un pitido corto: "te escucho", para cuando la pantalla está apagada. */
    private fun beep() {
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
            main.postDelayed({ tone.release() }, 400)
        }
    }

    private fun stopVosk() {
        speech?.stop()
        speech?.shutdown()
        speech = null
        recognizer?.close()
        recognizer = null
    }

    // --- Escuchar una frase ---

    /**
     * Usa el oído rápido si está (dentro del teléfono, en streaming); si no, el reconocedor de Android y,
     * si este no puede usar el micrófono desde aquí, el de Vosk.
     */
    private val ear = Ear { onHeard, onNothing, onFailed ->
        if (Assistant.bestEar() === Assistant.neuralEar) {
            Assistant.neuralEar.listen(onHeard, onNothing, onFailed)
        } else if (useVoskEar) {
            voskEar.listen(onHeard, onNothing, onFailed)
        } else {
            Assistant.androidEar.listen(onHeard, onNothing) { error ->
                useVoskEar = true
                voskEar.listen(onHeard, onNothing, onFailed)
            }
        }
    }

    private val voskEar = Ear { onHeard, onNothing, onFailed ->
        val model = model
        if (model == null) {
            onFailed(-2)
            return@Ear
        }
        stopVosk()
        val rec = Recognizer(model, SAMPLE_RATE)
        recognizer = rec
        Assistant.listening = true
        Assistant.status = "Te escucho…"
        var done = false
        fun finish(action: () -> Unit) {
            if (done) return
            done = true
            Assistant.listening = false
            Assistant.status = ""
            stopVosk()
            action()
        }
        speech = SpeechService(rec, SAMPLE_RATE).also {
            it.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {}
                override fun onResult(hypothesis: String?) {
                    val text = runCatching { JSONObject(hypothesis ?: "{}").optString("text") }.getOrDefault("")
                    if (text.isNotBlank()) finish { onHeard(text) }
                }
                override fun onFinalResult(hypothesis: String?) {
                    val text = runCatching { JSONObject(hypothesis ?: "{}").optString("text") }.getOrDefault("")
                    finish { if (text.isNotBlank()) onHeard(text) else onNothing() }
                }
                override fun onError(exception: Exception?) = finish { onFailed(-3) }
                override fun onTimeout() = finish { onNothing() }
            }, VOSK_LISTEN_MS)
        }
    }

    // --- Aviso fijo ---

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Escuchando \"Ultra\"", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, UltraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Ultra Yo te escucha")
            .setContentText("Di \"Ultra\" para hablar con él.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Dejar de escuchar", stop)
            .build()
    }

    companion object {
        @Volatile
        var instance: UltraService? = null
            private set

        private val loader = Executors.newSingleThreadExecutor()

        fun start(context: Context) {
            // Sin micrófono Android 14 cierra la app al poner el aviso fijo de micrófono: mejor no empezar.
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                throw IllegalStateException("falta el permiso del micrófono")
            }
            ContextCompat.startForegroundService(context, Intent(context, UltraService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UltraService::class.java))
        }
    }
}
