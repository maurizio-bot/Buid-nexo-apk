package com.ultrayo.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val MAX_HISTORY = 20
private const val MAX_FILE_TEXT = 6000
private const val MAX_IMAGE_SIDE = 1024
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "csv", "json", "xml", "html", "htm", "kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h",
    "css", "yml", "yaml", "ini", "log", "gradle", "sh", "bat", "sql", "properties",
)

/** Lo que el usuario adjunta antes de enviar: una foto ya copiada al teléfono o el texto de un archivo. */
private data class Attachment(val name: String, val imagePath: String? = null, val text: String? = null)

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
    private var lastCrash by mutableStateOf<String?>(null)
    private var attachment by mutableStateOf<Attachment?>(null)

    /** Hay que reiniciar la conversación del modelo (cambió la memoria o se borró el chat). */
    private var brainDirty = true

    private var folders by mutableStateOf<List<Uri>>(emptyList())

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            Folders.add(this, uri)
            folders = Folders.list(this)
        }
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attachPhoto(uri)
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachFile(uri)
    }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else status = "Necesito permiso para usar el micrófono"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memory = Memory(this)
        lastCrash = App.crashFile(this).takeIf { it.exists() }?.readText()
        folders = Folders.list(this)
        downloader = ModelDownloader(this)
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("es-ES")
                ttsReady = true
            }
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = UltraColors.Accent,
                    background = UltraColors.Deep,
                    surface = UltraColors.Navy,
                )
            ) {
                Screen()
            }
        }
        refreshDownload()
    }

    override fun onResume() {
        super.onResume()
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // Con el teléfono bloqueado (abierta con el botón lateral) empieza a escuchar sola.
        if (locked && brainReady && hasMicPermission() && !listening && !thinking) startListening()
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
        try {
            downloader.start()
        } catch (e: Exception) {
            download = DownloadState.Failed(-1)
            status = "No pude empezar la descarga: ${e.message}"
            return
        }
        watchDownload()
    }

    private fun watchDownload() {
        if (downloadWatcher?.isActive == true) return
        downloadWatcher = lifecycleScope.launch {
            while (true) {
                download = try {
                    downloader.state()
                } catch (e: Exception) {
                    status = "No pude ver cómo va la descarga: ${e.message}"
                    DownloadState.Failed(-2)
                }
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
                        Ai.brain = Brain(downloader.file.path, cacheDir.path, PhoneTools(applicationContext)).also { it.load() }
                    }
                }
                brainReady = true
                status = ""
            } catch (e: Exception) {
                status = "No pude cargar el modelo: ${e.message}"
            }
        }
    }

    // --- Adjuntos ---

    private fun displayName(uri: Uri): String =
        DocumentFile.fromSingleUri(this, uri)?.name ?: uri.lastPathSegment ?: "archivo"

    /** Copia la foto al teléfono, reducida, para que el modelo la pueda mirar sin gastar mucha memoria. */
    private fun attachPhoto(uri: Uri) {
        val name = displayName(uri)
        status = "Preparando la foto…"
        lifecycleScope.launch {
            val path = try {
                withContext(Dispatchers.IO) {
                    val source = ImageDecoder.createSource(contentResolver, uri)
                    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val w = info.size.width
                        val h = info.size.height
                        val scale = minOf(1f, MAX_IMAGE_SIDE.toFloat() / maxOf(w, h))
                        decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    val dir = File(cacheDir, "fotos").apply { mkdirs() }
                    val file = File(dir, "foto-${System.currentTimeMillis()}.jpg")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    file.path
                }
            } catch (e: Exception) {
                status = "No pude abrir la foto: ${e.message}"
                return@launch
            }
            status = ""
            attachment = Attachment(name, imagePath = path)
        }
    }

    /** Lee un archivo de texto (solo lectura). Las imágenes elegidas como archivo se tratan como fotos. */
    private fun attachFile(uri: Uri) {
        val type = contentResolver.getType(uri).orEmpty()
        if (type.startsWith("image/")) {
            attachPhoto(uri)
            return
        }
        val name = displayName(uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        val isText = type.startsWith("text/") || type.contains("json") || type.contains("xml") ||
            extension in TEXT_EXTENSIONS
        if (!isText) {
            status = "Por ahora solo puedo leer fotos y archivos de texto, no \"$name\"."
            return
        }
        lifecycleScope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val buffer = CharArray(MAX_FILE_TEXT)
                        val read = reader.read(buffer)
                        if (read <= 0) "" else String(buffer, 0, read)
                    }.orEmpty()
                }
            } catch (e: Exception) {
                status = "No pude leer \"$name\": ${e.message}"
                return@launch
            }
            status = ""
            attachment = Attachment(name, text = text)
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
                status = ""
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) status = "No te entendí" else send(text)
            }

            override fun onError(error: Int) {
                listening = false
                status = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No te oí. Toca el reactor para hablar."
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

    private fun say(text: String, aloud: Boolean) {
        turns.add(Turn("assistant", text))
        status = ""
        val spoken = text.replace(Regex("[*#_`>|]"), "")
        if (aloud && ttsReady) tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    // --- Conversación ---

    /** [aloud]: responder en voz alta (cuando se le habló) o solo por escrito (cuando se le escribió). */
    private fun send(typed: String, aloud: Boolean = true) {
        val attached = attachment
        attachment = null
        val text = typed.ifBlank {
            when {
                attached?.imagePath != null -> "¿Qué ves en esta foto?"
                attached != null -> "Resúmeme este archivo."
                else -> return
            }
        }
        val history = turns.takeLast(MAX_HISTORY)
        turns.add(Turn("user", text, image = attached?.imagePath, fileName = attached?.name))

        if (attached == null) {
            Teaching.handle(text, memory)?.let { answer ->
                brainDirty = true
                say(answer, aloud)
                return
            }
        }

        val brain = Ai.brain ?: return
        if (attached?.imagePath != null && !brain.visionEnabled) {
            say("Todavía no puedo ver fotos en este teléfono. Si quieres, descríbemela con palabras.", aloud)
            return
        }
        val prompt = if (attached?.text != null) {
            "$text\n\n[Archivo adjunto: ${attached.name}]\n${attached.text}"
        } else {
            text
        }
        thinking = true
        status = "Pensando…"
        val reset = brainDirty
        brainDirty = false
        val systemPrompt = memory.systemPrompt()
        lifecycleScope.launch {
            val reply = try {
                withContext(Ai.thread) {
                    if (reset) brain.reset(systemPrompt, history)
                    brain.ask(prompt, attached?.imagePath)
                }
            } catch (e: Exception) {
                brainDirty = true
                status = "Algo falló: ${e.message}"
                null
            }
            thinking = false
            if (reply != null) say(reply.ifBlank { "No sé qué responder a eso." }, aloud)
        }
    }

    private fun newChat() {
        turns.clear()
        attachment = null
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
                .background(Background)
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Header(showMemory = showMemory, onToggleMemory = { showMemory = !showMemory })
            when {
                lastCrash != null -> CrashScreen(lastCrash.orEmpty(), Modifier.weight(1f))
                showMemory && !locked -> MemoryScreen(Modifier.weight(1f))
                !brainReady -> SetupScreen(Modifier.weight(1f))
                else -> ChatScreen(Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun Header(showMemory: Boolean, onToggleMemory: () -> Unit) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Reactor(40.dp, listening = listening, thinking = thinking)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "ULTRA YO",
                    color = UltraColors.Text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                )
                Text(
                    when {
                        listening -> "Escuchando"
                        thinking -> "Pensando"
                        brainReady -> "En línea · sin internet"
                        else -> "Preparándose"
                    },
                    color = if (listening || thinking) UltraColors.AccentBright else UltraColors.Muted,
                    fontSize = 12.sp,
                )
            }
            if (brainReady && !showMemory) {
                IconButton(onClick = { newChat() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Nueva conversación", tint = UltraColors.Accent)
                }
            }
            // Con el teléfono bloqueado no se puede ver ni cambiar lo que sabe.
            if (!locked) {
                IconButton(onClick = onToggleMemory) {
                    Icon(
                        if (showMemory) Icons.Filled.Close else Icons.Filled.Settings,
                        contentDescription = if (showMemory) "Cerrar" else "Memoria y ajustes",
                        tint = UltraColors.Accent,
                    )
                }
            }
        }
    }

    @Composable
    private fun CrashScreen(report: String, modifier: Modifier) {
        val clipboard = LocalClipboardManager.current
        Column(
            modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .glass()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionTitle("La app se cerró", "Haz una captura de esta pantalla y mándasela a Claude.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlowButton("Copiar", onClick = { clipboard.setText(AnnotatedString(report)) })
                TextButton(onClick = {
                    App.crashFile(this@MainActivity).delete()
                    lastCrash = null
                }) { Text("Cerrar") }
            }
            LazyColumn(Modifier.weight(1f)) {
                item { Text(report.take(4000), color = UltraColors.Muted, fontSize = 12.sp) }
            }
        }
    }

    @Composable
    private fun SetupScreen(modifier: Modifier) {
        Column(
            modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Reactor(180.dp, thinking = download is DownloadState.Running || download == DownloadState.Done)
            Spacer(Modifier.height(28.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionTitle("Tu IA vive en el teléfono")
                Text(
                    "La primera vez hay que descargar su cerebro (unos 2,6 GB). Después funciona sin internet.",
                    color = UltraColors.Text,
                )
                when (val d = download) {
                    DownloadState.None ->
                        GlowButton("Descargar (mejor con wifi)", onClick = { startDownload() }, Modifier.fillMaxWidth())
                    is DownloadState.Running -> {
                        Text("Descargando… ${(d.progress * 100).toInt()} %", color = UltraColors.Accent)
                        LinearProgressIndicator(
                            progress = { d.progress },
                            color = UltraColors.AccentBright,
                            trackColor = UltraColors.Glass,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                        )
                    }
                    is DownloadState.Failed -> {
                        Text("La descarga falló (código ${d.reason}). $status", color = UltraColors.Muted)
                        GlowButton("Reintentar", onClick = { startDownload() }, Modifier.fillMaxWidth())
                    }
                    DownloadState.Done -> Text(status, color = UltraColors.Accent)
                }
            }
        }
    }

    @Composable
    private fun ChatScreen(modifier: Modifier) {
        val listState = rememberLazyListState()
        LaunchedEffect(turns.size, thinking) {
            if (turns.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
        }
        Column(modifier) {
            if (turns.isEmpty()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Reactor(
                        160.dp,
                        listening = listening,
                        thinking = thinking,
                        modifier = Modifier.clickable { if (listening) recognizer?.stopListening() else startListening() },
                    )
                    Spacer(Modifier.height(24.dp))
                    Text("¿En qué te ayudo, Maurizio?", color = UltraColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Toca el reactor para hablar, o escríbeme abajo.",
                        color = UltraColors.Muted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(turns) { Bubble(it) }
                    if (thinking) item { ThinkingDots() }
                }
            }

            if (status.isNotBlank()) {
                Text(status, color = UltraColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            attachment?.let { AttachmentChip(it) }
            InputBar()
        }
    }

    @Composable
    private fun AttachmentChip(a: Attachment) {
        Row(
            Modifier
                .padding(bottom = 6.dp)
                .glass(14.dp)
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (a.imagePath != null) {
                Thumbnail(a.imagePath, 36.dp)
            } else {
                Text("📄", fontSize = 18.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(a.name, color = UltraColors.Text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.widthIn(max = 220.dp))
            IconButton(onClick = { attachment = null }) {
                Icon(Icons.Filled.Close, contentDescription = "Quitar adjunto", tint = UltraColors.Muted)
            }
        }
    }

    @Composable
    private fun InputBar() {
        var typed by remember { mutableStateOf("") }
        var menuOpen by remember { mutableStateOf(false) }
        Row(
            Modifier
                .padding(bottom = 12.dp)
                .fillMaxWidth()
                .glass(28.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !thinking) {
                    Icon(Icons.Filled.Add, contentDescription = "Adjuntar foto o archivo", tint = UltraColors.Accent)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("📷  Foto") },
                        onClick = {
                            menuOpen = false
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("📄  Archivo") },
                        onClick = {
                            menuOpen = false
                            pickFile.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
            TextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Escríbele a Ultra Yo…", color = UltraColors.Muted) },
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = UltraColors.Text,
                    unfocusedTextColor = UltraColors.Text,
                    cursorColor = UltraColors.AccentBright,
                ),
                modifier = Modifier.weight(1f),
            )
            if (typed.isNotBlank() || attachment != null) {
                IconButton(
                    onClick = {
                        send(typed.trim(), aloud = false)
                        typed = ""
                    },
                    enabled = !thinking,
                    modifier = Modifier
                        .padding(4.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(UltraColors.UserA, UltraColors.AccentBright))),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar", tint = Color.White)
                }
            } else {
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(44.dp)
                        .alpha(if (thinking) 0.4f else 1f)
                        .clip(CircleShape)
                        .clickable(enabled = !thinking) {
                            if (listening) recognizer?.stopListening() else startListening()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Reactor(44.dp, listening = listening, thinking = thinking)
                }
            }
        }
    }

    @Composable
    private fun MemoryScreen(modifier: Modifier) {
        var personality by remember { mutableStateOf(memory.personality) }
        var newFact by remember { mutableStateOf("") }
        var facts by remember { mutableStateOf(memory.facts()) }

        LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card {
                    SectionTitle("Carpetas que puede leer", "Solo lectura: puede buscar y leer archivos, nunca cambiarlos ni borrarlos.")
                    folders.forEach { uri ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📁  " + Folders.name(this@MainActivity, uri), color = UltraColors.Text, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                Folders.remove(this@MainActivity, uri)
                                folders = Folders.list(this@MainActivity)
                            }) { Text("Quitar") }
                        }
                    }
                    GlowButton("Dar acceso a una carpeta", onClick = { pickFolder.launch(null) }, Modifier.fillMaxWidth())
                }
            }
            item {
                Card {
                    SectionTitle("Personalidad", "Cómo es y cómo te habla.")
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
            }
            item {
                Card {
                    SectionTitle("Lo que le has enseñado", "También puedes decirle \"recuerda que…\" u \"olvida…\".")
                    facts.forEachIndexed { index, fact ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("•  $fact", color = UltraColors.Text, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                memory.removeAt(index)
                                facts = memory.facts()
                                brainDirty = true
                            }) { Text("Borrar") }
                        }
                    }
                    OutlinedTextField(
                        value = newFact,
                        onValueChange = { newFact = it },
                        label = { Text("Enseñarle algo nuevo") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    GlowButton(
                        "Añadir",
                        onClick = {
                            memory.add(newFact.trim())
                            newFact = ""
                            facts = memory.facts()
                            brainDirty = true
                        },
                        enabled = newFact.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .glass()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }

    @Composable
    private fun Thumbnail(path: String, size: androidx.compose.ui.unit.Dp) {
        val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = "Foto adjunta",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(10.dp)),
            )
        }
    }

    @Composable
    private fun Bubble(turn: Turn) {
        val mine = turn.role == "user"
        Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
            val shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
            Column(
                Modifier
                    .widthIn(max = 310.dp)
                    .clip(shape)
                    .then(
                        if (mine) {
                            Modifier.background(Brush.linearGradient(listOf(UltraColors.UserA, UltraColors.UserB)))
                        } else {
                            Modifier
                                .background(UltraColors.Glass)
                                .border(1.dp, UltraColors.GlassBorder, shape)
                        }
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!mine) {
                    Text("ULTRA YO", color = UltraColors.Accent, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                }
                turn.image?.let { Thumbnail(it, 180.dp) }
                if (turn.image == null && turn.fileName != null) {
                    Text("📄  ${turn.fileName}", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
                Text(turn.content, color = UltraColors.Text, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
    }

    @Composable
    private fun ThinkingDots() {
        val transition = rememberInfiniteTransition(label = "dots")
        Row(
            Modifier
                .glass(18.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(3) { i ->
                val a by transition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 200), RepeatMode.Reverse),
                    label = "dot$i",
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .alpha(a)
                        .clip(CircleShape)
                        .background(UltraColors.AccentBright)
                )
            }
        }
    }
}
