package com.ultrayo.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

private val Accent = Color(0xFF4FC3F7)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    private val turns = mutableStateListOf<Turn>()
    private var status by mutableStateOf("")
    private var listening by mutableStateOf(false)
    private var thinking by mutableStateOf(false)
    private var serverUrl by mutableStateOf("")
    private var token by mutableStateOf("")
    private var locked by mutableStateOf(false)

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else status = "Necesito permiso para usar el micrófono"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ultrayo", MODE_PRIVATE)
        serverUrl = prefs.getString("server_url", "").orEmpty()
        token = prefs.getString("token", "").orEmpty()
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("es-ES")
                ttsReady = true
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
                Screen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // Al abrir la app (por ejemplo con doble pulsación del botón lateral) empieza a escuchar sola.
        if (configured() && hasMicPermission() && !listening && !thinking) startListening()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun configured() = serverUrl.isNotBlank() && token.isNotBlank()

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startListening() {
        if (!hasMicPermission()) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status = "Este teléfono no tiene reconocimiento de voz disponible"
            return
        }
        tts?.stop()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                status = "Te escucho…"
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) status = "No te entendí" else send(text)
            }

            override fun onError(error: Int) {
                listening = false
                status = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No te oí. Toca el botón para hablar."
                    else -> "Error del micrófono ($error)"
                }
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
        }
        r.startListening(intent)
    }

    private fun send(text: String) {
        turns.add(Turn("user", text))
        thinking = true
        status = "Pensando…"
        val history = turns.toList()
        val api = JarvisApi(serverUrl, token)
        lifecycleScope.launch {
            val reply = try {
                withContext(Dispatchers.IO) { api.chat(history) }
            } catch (e: JarvisException) {
                status = e.message.orEmpty()
                null
            } catch (e: IOException) {
                status = "No pude conectar con el servidor"
                null
            } catch (e: Exception) {
                status = "Respuesta inesperada del servidor"
                null
            }
            thinking = false
            if (reply == null) {
                turns.removeAt(turns.lastIndex)
                return@launch
            }
            turns.add(Turn("assistant", reply))
            status = ""
            if (ttsReady) tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "reply")
        }
    }

    @Composable
    private fun Screen() {
        var showSettings by remember { mutableStateOf(!configured()) }
        val listState = rememberLazyListState()
        LaunchedEffect(turns.size) {
            if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ultra Yo",
                    color = Accent,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { turns.clear(); status = "" }) { Text("Nueva") }
                // Con el teléfono bloqueado no se pueden ver ni cambiar los ajustes.
                if (!locked) {
                    TextButton(onClick = { showSettings = !showSettings }) {
                        Text(if (showSettings) "Cerrar" else "Ajustes")
                    }
                }
            }

            if (showSettings && !locked) SettingsPanel(onSaved = { showSettings = false })

            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(turns) { Bubble(it) }
            }

            Text(status, color = Color.LightGray, modifier = Modifier.padding(vertical = 8.dp))

            Button(
                onClick = { if (listening) recognizer?.stopListening() else startListening() },
                enabled = configured() && !thinking,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                Text(
                    when {
                        listening -> "Escuchando… (toca para terminar)"
                        thinking -> "Pensando…"
                        else -> "Hablar"
                    }
                )
            }
        }
    }

    @Composable
    private fun SettingsPanel(onSaved: () -> Unit) {
        var url by remember { mutableStateOf(serverUrl) }
        var tok by remember { mutableStateOf(token) }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Dirección del servidor (https://…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = tok,
                onValueChange = { tok = it },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                serverUrl = url.trim()
                token = tok.trim()
                prefs.edit().putString("server_url", serverUrl).putString("token", token).apply()
                onSaved()
            }) { Text("Guardar") }
        }
    }

    @Composable
    private fun Bubble(turn: Turn) {
        val mine = turn.role == "user"
        Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
            Text(
                turn.content,
                color = Color.White,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(if (mine) Color(0xFF1E3A5F) else Color(0xFF263238), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }
    }
}
