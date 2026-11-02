package com.ultrayo.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * El botón de voz del manos libres o del volante (pulsación larga en muchos cascos): Android lo
 * manda como "orden de voz". Si el oído de "Ultra" está activo, conversa sin abrir la pantalla
 * (para no tapar el navegador del coche); si no, abre Ultra Yo y empieza a escuchar.
 */
class VoiceButtonActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Assistant.init(this)
        val service = UltraService.instance
        if (service != null) {
            Assistant.stopSpeaking()
            service.wake()
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_TALK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }
}
