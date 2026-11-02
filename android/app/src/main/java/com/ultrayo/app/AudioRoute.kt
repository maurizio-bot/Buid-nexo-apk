package com.ultrayo.app

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaRecorder

/**
 * Por dónde entra y sale la voz de Ultra. Todo el audio de la voz rápida pasa por aquí, para que
 * el manos libres o el coche por Bluetooth (SCO) solo tengan que cambiar estos valores.
 */
object AudioRoute {

    /** Micrófono preferido (null = el que elija Android, normalmente el del teléfono). */
    @Volatile
    var input: AudioDeviceInfo? = null

    /** Altavoz o auricular preferido (null = el que elija Android). */
    @Volatile
    var output: AudioDeviceInfo? = null

    /**
     * true cuando se habla como en una llamada (manos libres Bluetooth): el micrófono y la voz van por
     * el canal de llamadas, que es el único que usa el micrófono de los cascos y del coche.
     */
    @Volatile
    var callMode = false

    /** Fuente del micrófono para escuchar una frase. */
    fun micSource(): Int =
        if (callMode) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION

    /** Fuente del micrófono mientras Ultra habla (para poder interrumpirla): con cancelación de eco. */
    fun micSourceWhileSpeaking(): Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION

    /** Cómo se reproduce la voz de Ultra. */
    fun speechAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (callMode) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}
