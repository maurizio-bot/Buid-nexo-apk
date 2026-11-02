package com.ultrayo.app

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * El manos libres y el coche por Bluetooth.
 *
 * Mientras Ultra conversa por voz, si hay unos cascos o un coche conectados con micrófono, abre el canal
 * de llamadas (SCO, o LE Audio si lo tiene) para que te oiga por el micrófono del coche y le oigas por
 * sus altavoces. Cuando la conversación termina y lleva unos segundos callada, lo suelta para que vuelva
 * la música. Solo se abre cuando Maurizio habla con Ultra (reactor, "Ultra" o el botón de voz).
 */
object Bluetooth {

    private lateinit var audio: AudioManager
    private val main = Handler(Looper.getMainLooper())

    /** Nombre del manos libres o del coche conectado ahora mismo (vacío si no hay). */
    @Volatile
    var connectedName = ""
        private set

    /** El canal de llamadas está abierto con este aparato. */
    @Volatile
    var linked: AudioDeviceInfo? = null
        private set

    private var opening = false
    private var waiting = mutableListOf<() -> Unit>()
    private var previousMode = AudioManager.MODE_NORMAL
    private var idleSince = 0L

    /** Se llama cuando cambia lo conectado (para refrescar Ajustes). */
    var onChange: (() -> Unit)? = null

    fun init(context: Context) {
        if (::audio.isInitialized) return
        audio = context.getSystemService(AudioManager::class.java)
        refresh()
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                val gone = linked?.let { l -> removedDevices?.any { it.id == l.id } } == true
                if (gone) close()
                refresh()
            }
        }, main)
    }

    private fun enabled() = Assistant.memory.bluetooth

    private fun refresh() {
        connectedName = headset()?.productName?.toString()?.trim().orEmpty()
        onChange?.invoke()
    }

    private fun isHeadset(d: AudioDeviceInfo) =
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    /** El manos libres con micrófono que hay conectado, si hay. LE Audio primero: suena mejor. */
    private fun headset(): AudioDeviceInfo? {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.availableCommunicationDevices
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        return devices.filter(::isHeadset).maxByOrNull { if (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) 0 else 1 }
    }

    /** El micrófono que corresponde a ese aparato. */
    private fun micOf(out: AudioDeviceInfo): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter(::isHeadset)
            .let { mics -> mics.firstOrNull { it.address == out.address && it.type == out.type } ?: mics.firstOrNull() }

    /**
     * Antes de escuchar: si hay manos libres, abre el canal de llamadas y espera a que esté listo
     * (medio segundo o uno). Siempre acaba llamando a [then] en el hilo principal, con o sin Bluetooth.
     */
    fun open(then: () -> Unit) {
        if (!::audio.isInitialized || !enabled()) return then()
        idleSince = 0L
        if (linked != null) return then()
        val device = headset() ?: return then()
        waiting += then
        if (opening) return
        opening = true
        previousMode = audio.mode
        runCatching { audio.mode = AudioManager.MODE_IN_COMMUNICATION }
        val started = System.currentTimeMillis()
        val asked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audio.setCommunicationDevice(device) }.getOrDefault(false)
        } else {
            @Suppress("DEPRECATION")
            runCatching { audio.startBluetoothSco(); audio.isBluetoothScoOn = true; true }.getOrDefault(false)
        }
        if (!asked) return failed()

        fun ready(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.communicationDevice?.id == device.id
        } else {
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn && System.currentTimeMillis() - started > 800
        }

        fun poll() {
            when {
                ready() -> main.postDelayed({ linkedTo(device) }, 150) // el enlace tarda un poco en dar audio
                System.currentTimeMillis() - started > 3000 -> failed()
                else -> main.postDelayed(::poll, 60)
            }
        }
        poll()
    }

    private fun linkedTo(device: AudioDeviceInfo) {
        linked = device
        AudioRoute.callMode = true
        AudioRoute.output = device
        AudioRoute.input = micOf(device)
        NeuralVoice.setOutput()
        opening = false
        watchIdle()
        flush()
    }

    private fun failed() {
        undo()
        opening = false
        Assistant.status = "No pude abrir el micro del manos libres; te escucho por el del teléfono."
        flush()
    }

    private fun flush() {
        val pending = waiting
        waiting = mutableListOf()
        pending.forEach { it() }
    }

    /** Suelta el canal de llamadas: vuelve la música y el micrófono del teléfono. */
    fun close() {
        if (linked == null) return
        undo()
        linked = null
    }

    private fun undo() {
        AudioRoute.callMode = false
        AudioRoute.input = null
        AudioRoute.output = null
        NeuralVoice.setOutput()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audio.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audio.isBluetoothScoOn = false; audio.stopBluetoothSco() }
        }
        runCatching {
            audio.mode = if (previousMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else previousMode
        }
    }

    /** Suelta el canal cuando Ultra lleva unos segundos sin escuchar, pensar ni hablar. */
    private fun watchIdle() {
        main.postDelayed({
            if (linked == null) return@postDelayed
            val busy = Assistant.listening || Assistant.thinking || Assistant.speaking || NeuralEar.active
            val now = System.currentTimeMillis()
            if (busy) {
                idleSince = 0L
            } else if (idleSince == 0L) {
                idleSince = now
            } else if (now - idleSince > IDLE_MS) {
                close()
                return@postDelayed
            }
            watchIdle()
        }, 1000)
    }

    private const val IDLE_MS = 6000L
}
