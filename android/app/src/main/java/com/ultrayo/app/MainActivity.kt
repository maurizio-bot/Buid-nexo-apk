package com.ultrayo.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Accent = Color(0xFF4FC3F7)
private const val MAX_HISTORY = 20

class MainActivity : ComponentActivity() {

    private lateinit var memory: Memory
    private lateinit var downloader: ModelDownloader
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var downloadWatcher: Job? = null

    private val turns = mutableStateListOf<Turn>()
    private var status by mutableStateOf("")
    private var listening by mutableStateOf(false)
    private var thinking by mutableStateOf(false)
    private var locked by mutableStateOf(false)
    private var download by mutableStateOf<DownloadState>(DownloadState.None)
    private var brainReady by mutableStateOf(Ai.brain != null)

    /** Hay que reiniciar la conversación del modelo (cambió la memoria o se borró el chat). */
    private var brainDirty = true

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else status = "Necesito permiso para usar el micrófono"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memory = Memory(this)
        downloader = ModelDownloader(this)
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
        refreshDownload()
    }

    override fun onResume() {
        super.onResume()
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // Al abrir la app (por ejemplo con doble pulsación del botón lateral) empieza a escuchar sola.
        if (brainReady && hasMicPermission() && !listening && !thinking) startListening()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // --- Cerebro ---

    private fun refreshDownload() {
        download = downloader.state()
        when (download) {
            DownloadState.Done -> loadBrain()
            is DownloadState.Running -> watchDownload()
            else -> {}
        }
    }

    private fun startDownload() {
        downloader.start()
        watchDownload()
    }

    private fun watchDownload() {
        if (downloadWatcher?.isActive == true) return
        downloadWatcher = lifecycleScope.launch {
            while (true) {
                download = downloader.state()
                if (download !is DownloadState.Running) break
                delay(1000)
            }
            if (download == DownloadState.Done) loadBrain()
        }
    }

    private fun loadBrain() {
        if (Ai.brain != null) {
            brainReady = true
            return
        }
        status = "Despertando a tu IA…"
        lifecycleScope.launch {
            try {
                withContext(Ai.thread) {
                    if (Ai.brain == null) {
                        Ai.brain = Brain(downloader.file.path, cacheDir.path).also { it.load() }
                    }
                }
                brainReady = true
                status = "Lista. Toca el botón y háblame."
            } catch (e: Exception) {
                status = "No pude cargar el modelo: ${e.message}"
            }
        }
    }

    // --- Voz ---

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

    private fun say(text: String) {
        turns.add(Turn("assistant", text))
        status = ""
        val spoken = text.replace(Regex("[*#_`>|]"), "")
        if (ttsReady) tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    // --- Conversación ---

    private fun send(text: String) {
        val history = turns.takeLast(MAX_HISTORY)
        turns.add(Turn("user", text))

        Teaching.handle(text, memory)?.let { answer ->
            brainDirty = true
            say(answer)
            return
        }

        val brain = Ai.brain ?: return
        thinking = true
        status = "Pensando…"
        val reset = brainDirty
        brainDirty = false
        val systemPrompt = memory.systemPrompt()
        lifecycleScope.launch {
            val reply = try {
                withContext(Ai.thread) {
                    if (reset) brain.reset(systemPrompt, history)
                    brain.ask(text)
                }
            } catch (e: Exception) {
                brainDirty = true
                status = "Algo falló: ${e.message}"
                null
            }
            thinking = false
            if (reply != null) say(reply.ifBlank { "No sé qué responder a eso." })
        }
    }

    private fun newChat() {
        turns.clear()
        brainDirty = true
        status = ""
    }

    // --- Pantallas ---

    @Composable
    private fun Screen() {
        var showMemory by remember { mutableStateOf(false) }
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
                if (brainReady) TextButton(onClick = { newChat() }) { Text("Nueva") }
                // Con el teléfono bloqueado no se puede ver ni cambiar lo que sabe.
                if (!locked) {
                    TextButton(onClick = { showMemory = !showMemory }) {
                        Text(if (showMemory) "Cerrar" else "Memoria")
                    }
                }
            }

            when {
                showMemory && !locked -> MemoryScreen(Modifier.weight(1f))
                !brainReady -> SetupScreen(Modifier.weight(1f))
                else -> ChatScreen(Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun SetupScreen(modifier: Modifier) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Tu IA vive dentro del teléfono. La primera vez hay que descargar su cerebro " +
                    "(unos 2,6 GB). Después funciona sin internet.",
                color = Color.White
            )
            when (val d = download) {
                DownloadState.None -> Button(onClick = { startDownload() }) { Text("Descargar (mejor con wifi)") }
                is DownloadState.Running -> {
                    Text("Descargando… ${(d.progress * 100).toInt()} %", color = Color.LightGray)
                    LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
                }
                is DownloadState.Failed -> {
                    Text("La descarga falló (código ${d.reason}).", color = Color.LightGray)
                    Button(onClick = { startDownload() }) { Text("Reintentar") }
                }
                DownloadState.Done -> Text(status, color = Color.LightGray)
            }
        }
    }

    @Composable
    private fun ChatScreen(modifier: Modifier) {
        val listState = rememberLazyListState()
        LaunchedEffect(turns.size) {
            if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
        }
        Column(modifier) {
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
                enabled = !thinking,
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
    private fun MemoryScreen(modifier: Modifier) {
        var personality by remember { mutableStateOf(memory.personality) }
        var newFact by remember { mutableStateOf("") }
        var facts by remember { mutableStateOf(memory.facts()) }

        LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Personalidad", color = Accent)
                OutlinedTextField(
                    value = personality,
                    onValueChange = { personality = it },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = {
                    memory.personality = personality
                    personality = memory.personality
                    brainDirty = true
                }) { Text("Guardar personalidad") }
            }
            item {
                Text("Lo que le has enseñado", color = Accent)
                Text(
                    "También puedes decirle por voz \"recuerda que…\" u \"olvida…\".",
                    color = Color.LightGray
                )
            }
            itemsIndexed(facts) { index, fact ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fact, color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        memory.removeAt(index)
                        facts = memory.facts()
                        brainDirty = true
                    }) { Text("Borrar") }
                }
            }
            item {
                OutlinedTextField(
                    value = newFact,
                    onValueChange = { newFact = it },
                    label = { Text("Enseñarle algo nuevo") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        memory.add(newFact.trim())
                        newFact = ""
                        facts = memory.facts()
                        brainDirty = true
                    },
                    enabled = newFact.isNotBlank()
                ) { Text("Añadir") }
            }
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
