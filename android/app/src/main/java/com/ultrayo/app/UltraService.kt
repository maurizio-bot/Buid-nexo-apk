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

    /** El reconocedor de Android no puede usar el micrófono desde aquí: usar el de Vosk. */
    private var useVoskEar = false

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
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ultrayo:escucha")
            .apply { acquire() }
        Assistant.loadBrain()
        loader.execute {
            val loaded = try {
                Model(WakeModel.dir(this).path)
            } catch (e: Exception) {
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
        if (instance === this) instance = null
        stopVosk()
        model?.close()
        model = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // --- La palabra "Ultra" ---

    private fun startWake() {
        val model = model ?: return
        if (busy) return
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

    /** Empieza a conversar: al oír "Ultra" o al tocar el reactor. */
    fun wake() {
        if (busy) return
        // No hacer caso de su propia voz (por ejemplo cuando dice "Ultra Yo").
        if (Assistant.speaking || Assistant.thinking || Assistant.listening) return
        busy = true
        stopVosk()
        Assistant.converse(ear) {
            busy = false
            startWake()
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

    /** Intenta el reconocedor de Android (mejor) y, si no puede usar el micrófono, el de Vosk. */
    private val ear = Ear { onHeard, onNothing, onFailed ->
        if (useVoskEar) {
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
            ContextCompat.startForegroundService(context, Intent(context, UltraService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UltraService::class.java))
        }
    }
}
