package com.ultrayo.app

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontFamily
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

private const val MAX_IMAGE_SIDE = 1024

class MainActivity : ComponentActivity() {

    private lateinit var downloader: ModelDownloader
    private var downloadWatcher: Job? = null

    // El estado de la conversación vive en Assistant, compartido con el servicio que escucha "Ultra".
    private val memory get() = Assistant.memory
    private val turns get() = Assistant.turns
    private var status: String
        get() = Assistant.status
        set(value) {
            Assistant.status = value
        }
    private val listening get() = Assistant.listening
    private val thinking get() = Assistant.thinking
    private val brainReady get() = Assistant.brainReady
    private var brainDirty: Boolean
        get() = Assistant.brainDirty
        set(value) {
            Assistant.brainDirty = value
        }

    private var locked by mutableStateOf(false)
    private var download by mutableStateOf<DownloadState>(DownloadState.None)
    private var lastCrash by mutableStateOf<String?>(null)
    private var attachment by mutableStateOf<Attachment?>(null)
    private var wakeOn by mutableStateOf(false)

    private var folders by mutableStateOf<List<Uri>>(emptyList())
    private var mediaAccess by mutableStateOf(false)

    private val askMedia = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mediaAccess = DeviceFiles.hasMedia(this)
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            Folders.add(this, uri)
            folders = Folders.list(this)
        }
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attachPhoto(uri)
    }

    private val pickPhotoToEdit = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attachPhoto(uri, edit = true)
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachFile(uri)
    }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            maybeStartWake()
            listenNow()
        } else {
            status = "Necesito permiso para usar el micrófono"
        }
    }

    // Sin este permiso el aviso de "te escucho" no se ve, pero la escucha funciona igual.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        maybeStartWake()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Assistant.init(this)
        lastCrash = App.crashFile(this).takeIf { it.exists() }?.readText()
        folders = Folders.list(this)
        mediaAccess = DeviceFiles.hasMedia(this)
        downloader = ModelDownloader(this)
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
        wakeOn = UltraService.instance != null
        mediaAccess = DeviceFiles.hasMedia(this)
        if (brainReady) maybeStartWake()
        // Con el teléfono bloqueado (abierta con el botón lateral) empieza a escuchar sola.
        if (locked && brainReady && hasMicPermission() && !listening && !thinking) listenNow()
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

    private fun loadBrain() = Assistant.loadBrain { maybeStartWake() }

    // --- Palabra "Ultra" ---

    private var preparingWake = false

    /** Enciende la escucha de "Ultra" en segundo plano si está activada. Solo se puede con la app abierta. */
    private fun maybeStartWake() {
        if (!memory.wakeWord || !brainReady || !hasMicPermission() || preparingWake) return
        if (UltraService.instance != null) {
            wakeOn = true
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("ultrayo", MODE_PRIVATE).getBoolean("asked_notifications", false)
        ) {
            getSharedPreferences("ultrayo", MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!WakeModel.isReady(this)) {
            preparingWake = true
            status = "Preparando el oído para la palabra \"Ultra\" (40 MB, solo una vez)…"
            lifecycleScope.launch {
                try {
                    WakeModel.download(this@MainActivity)
                    status = ""
                } catch (e: Exception) {
                    status = "No pude descargar el oído: ${e.message}"
                    return@launch
                } finally {
                    preparingWake = false
                }
                maybeStartWake()
            }
            return
        }
        try {
            UltraService.start(this)
            wakeOn = true
        } catch (e: Exception) {
            status = "No pude empezar a escuchar \"Ultra\": ${e.message}"
        }
    }

    private fun setWake(on: Boolean) {
        memory.wakeWord = on
        if (on) {
            maybeStartWake()
        } else {
            UltraService.stop(this)
            wakeOn = false
        }
    }

    // --- Adjuntos ---

    private fun displayName(uri: Uri): String =
        DocumentFile.fromSingleUri(this, uri)?.name ?: uri.lastPathSegment ?: "archivo"

    /** Copia la foto al teléfono, reducida, para que el modelo la pueda mirar sin gastar mucha memoria. */
    private fun attachPhoto(uri: Uri, edit: Boolean = false) {
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
            if (edit) Studio.editing = path else attachment = Attachment(name, imagePath = path)
        }
    }

    /** Lee el texto de un documento (solo lectura). Las imágenes elegidas como archivo se tratan como fotos. */
    private fun attachFile(uri: Uri) {
        val type = contentResolver.getType(uri).orEmpty()
        if (type.startsWith("image/")) {
            attachPhoto(uri)
            return
        }
        val name = displayName(uri)
        if (Archives.isArchive(name)) {
            attachArchive(uri, name)
            return
        }
        if (!DocReader.canRead(name, type) && !type.contains("json") && !type.contains("xml")) {
            status = "No sé leer \"$name\". Sé leer fotos y ${DocReader.FORMATS}."
            return
        }
        status = "Leyendo \"$name\"…"
        lifecycleScope.launch {
            val text = try {
                withContext(Dispatchers.IO) { DocReader.read(this@MainActivity, uri, name, type) }
            } catch (e: Exception) {
                status = "No pude leer \"$name\": ${e.message}"
                return@launch
            }
            status = ""
            attachment = Attachment(name, text = text)
        }
    }

    /** Un comprimido adjunto se descomprime en la carpeta de Ultra Yo y la IA recibe la lista de lo que había. */
    private fun attachArchive(uri: Uri, name: String) {
        status = "Descomprimiendo \"$name\"…"
        lifecycleScope.launch {
            val summary = try {
                withContext(Dispatchers.IO) { Archives.extract(this@MainActivity, uri, name).second }
            } catch (e: Exception) {
                status = "No pude descomprimir \"$name\": ${e.message}"
                return@launch
            }
            status = ""
            attachment = Attachment(name, text = summary)
        }
    }

    // --- Voz ---

    /** Toca el reactor: si estaba escuchando, termina; si no, empieza a conversar por voz. */
    private fun listenNow() {
        if (listening) {
            Assistant.stopListening()
            return
        }
        if (!hasMicPermission()) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        Assistant.stopSpeaking()
        val service = UltraService.instance
        if (service != null) service.wake() else Assistant.converse()
    }

    private fun sendTyped(text: String) {
        val attached = attachment
        attachment = null
        Assistant.send(text, attached)
    }

    private fun newChat() {
        Assistant.newChat()
        attachment = null
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
            val editing = Studio.editing
            if (editing != null && !locked) {
                ImageEditor(
                    editing,
                    onClose = { Studio.editing = null },
                    onSaved = { path ->
                        Studio.editing = null
                        turns.add(Turn("assistant", "Imagen editada. También está en tu galería.", image = path))
                    },
                )
                return@Column
            }
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
                        Assistant.speaking -> "Hablando"
                        brainReady && wakeOn -> "Di \"Ultra\" para hablar"
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
                        modifier = Modifier.clickable { listenNow() },
                    )
                    Spacer(Modifier.height(24.dp))
                    Text("¿En qué te ayudo, Maurizio?", color = UltraColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (wakeOn) "Di \"Ultra\" o toca el reactor para hablar. También puedes escribirme abajo." else "Toca el reactor para hablar, o escríbeme abajo.",
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
                    val preview = Studio.preview
                    when {
                        preview != null -> item { PaintingPreview(preview) }
                        thinking -> item { ThinkingDots() }
                    }
                }
            }

            val shownStatus = Studio.progress ?: status
            if (shownStatus.isNotBlank()) {
                Text(shownStatus, color = UltraColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            SelfEdits.pending.value?.let { if (!locked) SelfChangeCard(it) }
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
                        text = { Text("🖌️  Editar una foto") },
                        onClick = {
                            menuOpen = false
                            pickPhotoToEdit.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
                        sendTyped(typed.trim())
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
                            listenNow()
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
        // Cambia cada vez que se deshace algo, para volver a leer la memoria.
        var version by remember { mutableStateOf(0) }
        var personality by remember(version) { mutableStateOf(memory.personality) }
        var newFact by remember { mutableStateOf("") }
        var facts by remember(version) { mutableStateOf(memory.facts()) }
        var skills by remember(version) { mutableStateOf(memory.skills()) }
        var skillName by remember { mutableStateOf("") }
        var skillText by remember { mutableStateOf("") }
        var codeSkills by remember(version) { mutableStateOf(memory.codeSkills()) }
        var canUndo by remember(version) { mutableStateOf(memory.canUndo()) }

        fun changed() {
            facts = memory.facts()
            skills = memory.skills()
            codeSkills = memory.codeSkills()
            canUndo = memory.canUndo()
            brainDirty = true
        }

        var voiceReplies by remember { mutableStateOf(memory.voiceReplies) }

        LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card {
                    SectionTitle("Voz")
                    SettingSwitch(
                        "Escuchar \"Ultra\" siempre",
                        "Aunque la app esté cerrada o el teléfono bloqueado. Deja un aviso fijo mientras escucha.",
                        wakeOn,
                    ) { setWake(it) }
                    SettingSwitch(
                        "Contestar en voz alta",
                        "Si lo apagas, contesta por escrito. También puedes decirle \"modo texto\" o \"modo voz\".",
                        voiceReplies,
                    ) {
                        memory.voiceReplies = it
                        voiceReplies = it
                    }
                }
            }
            item { PainterCard() }
            if (canUndo) {
                item {
                    Card {
                        SectionTitle("Deshacer", "Vuelve a como estaba antes del último cambio en su mente.")
                        GlowButton("Deshacer último cambio", onClick = {
                            memory.undo()
                            brainDirty = true
                            version++
                        }, Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                Card {
                    SectionTitle(
                        "Archivos que puede leer",
                        "Solo lectura: puede buscar fotos, vídeos, audios y documentos y leerlos (${DocReader.FORMATS}), nunca cambiarlos ni borrarlos.",
                    )
                    if (mediaAccess) {
                        Text("🖼  Fotos, vídeos y audio de la galería", color = UltraColors.Text)
                    } else {
                        GlowButton(
                            "Dejarle ver fotos, vídeos y audio",
                            onClick = { askMedia.launch(DeviceFiles.mediaPermissions()) },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        "Para documentos y descargas, dale acceso a una carpeta (por ejemplo Download o Documents).",
                        color = UltraColors.Muted,
                        fontSize = 13.sp,
                    )
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
            item { DataCard() }
            item {
                CodeSkillsCard(codeSkills) { changed() }
            }
            item {
                Card {
                    SectionTitle("Personalidad", "Cómo es y cómo te habla. También puedes pedirle que se la cambie él mismo.")
                    OutlinedTextField(
                        value = personality,
                        onValueChange = { personality = it },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        memory.snapshot()
                        memory.personality = personality
                        personality = memory.personality
                        changed()
                    }) { Text("Guardar personalidad") }
                }
            }
            item {
                Card {
                    SectionTitle("Habilidades", "Instrucciones con nombre, como \"Modo programador\". Puede crearlas, reescribirlas o borrarlas si se lo pides.")
                    skills.forEach { (name, text) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("⚡  $name", color = UltraColors.Accent, fontWeight = FontWeight.SemiBold)
                                Text(text, color = UltraColors.Text, fontSize = 13.sp)
                            }
                            TextButton(onClick = {
                                memory.snapshot()
                                memory.removeSkill(name)
                                changed()
                            }) { Text("Borrar") }
                        }
                    }
                    OutlinedTextField(
                        value = skillName,
                        onValueChange = { skillName = it },
                        label = { Text("Nombre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = skillText,
                        onValueChange = { skillText = it },
                        label = { Text("Qué debe hacer") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    GlowButton(
                        "Guardar habilidad",
                        onClick = {
                            memory.snapshot()
                            memory.setSkill(skillName, skillText)
                            skillName = ""
                            skillText = ""
                            changed()
                        },
                        enabled = skillName.isNotBlank() && skillText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Card {
                    SectionTitle("Lo que le has enseñado", "También puedes decirle \"recuerda que…\", \"olvida…\" o \"deshaz el último cambio\".")
                    facts.forEachIndexed { index, fact ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("•  $fact", color = UltraColors.Text, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                memory.snapshot()
                                memory.removeAt(index)
                                changed()
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
                            memory.snapshot()
                            memory.add(newFact.trim())
                            newFact = ""
                            changed()
                        },
                        enabled = newFact.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    /** Lo guardado en la base de datos del teléfono, por colecciones. */
    @Composable
    private fun DataCard() {
        val store = remember { DataStore.get(this) }
        var version by remember { mutableStateOf(0) }
        var collections by remember(version) { mutableStateOf(store.collections()) }
        var open by remember { mutableStateOf<String?>(null) }
        val items = remember(version, open) { open?.let { store.search("", it, 100) }.orEmpty() }
        Card {
            SectionTitle(
                "Su carpeta y lo guardado",
                "Dile \"guarda en gastos: 12 € comida\", \"guarda que…\" o \"revisa tu memoria\". Todo va a su carpeta:",
            )
            Text("📂  ${Vault.path(this@MainActivity)}", color = UltraColors.Text, fontSize = 13.sp)
            if (collections.isEmpty()) Text("Todavía no hay nada guardado.", color = UltraColors.Muted, fontSize = 13.sp)
            collections.forEach { (name, count) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "🗂  $name ($count)",
                        color = UltraColors.Accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { open = if (open == name) null else name },
                    )
                    TextButton(onClick = { open = if (open == name) null else name }) { Text(if (open == name) "Cerrar" else "Ver") }
                }
                if (open == name) {
                    items.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("#${d.id}  ${d.text}", color = UltraColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                store.delete(d.id)
                                version++
                                collections = store.collections()
                            }) { Text("Borrar") }
                        }
                    }
                }
            }
        }
    }

    /** Habilidades con código: la parte de Ultra Yo que se puede programar sin tocar la app. */
    @Composable
    private fun CodeSkillsCard(skills: Map<String, CodeSkill>, onChanged: () -> Unit) {
        var editing by remember { mutableStateOf<String?>(null) }
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var problem by remember { mutableStateOf<String?>(null) }
        var output by remember { mutableStateOf<String?>(null) }

        fun edit(skillName: String?) {
            editing = skillName ?: ""
            val skill = skillName?.let { skills[it] }
            name = skillName ?: ""
            description = skill?.description ?: ""
            code = skill?.code ?: CODE_SKILL_TEMPLATE
            problem = null
            output = null
        }

        Card {
            SectionTitle(
                "Habilidades programadas",
                "Código JavaScript que Ultra Yo ejecuta en su motor de habilidades. Solo lo escribe si tú se lo pides " +
                    "(\"prográmate una habilidad que…\") y no se instala hasta que pulsas Aplicar. Al instalarla se reinicia el motor.",
            )
            skills.forEach { (skillName, skill) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🧩  $skillName", color = UltraColors.Accent, fontWeight = FontWeight.SemiBold)
                        Text(skill.description, color = UltraColors.Text, fontSize = 13.sp)
                    }
                    TextButton(onClick = { edit(skillName) }) { Text("Editar") }
                    TextButton(onClick = {
                        memory.snapshot()
                        memory.removeCodeSkill(skillName)
                        if (editing == skillName) editing = null
                        onChanged()
                    }) { Text("Borrar") }
                }
            }
            val current = editing
            if (current == null) {
                GlowButton("Escribir una habilidad", onClick = { edit(null) }, Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Para qué sirve") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Código") },
                    minLines = 6,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = UltraColors.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
                output?.let { Text("Resultado: $it", color = UltraColors.Text, fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlowButton("Guardar", onClick = {
                        problem = SkillEngine.check(code)
                        if (problem == null) {
                            memory.snapshot()
                            if (current.isNotEmpty() && !current.equals(name.trim(), ignoreCase = true)) memory.removeCodeSkill(current)
                            memory.setCodeSkill(name, CodeSkill(description.trim(), code.trim()))
                            editing = null
                            onChanged()
                        }
                    }, enabled = name.isNotBlank() && description.isNotBlank() && code.isNotBlank())
                    TextButton(onClick = {
                        problem = SkillEngine.check(code)
                        if (problem == null) {
                            output = "…"
                            lifecycleScope.launch {
                                output = withContext(Dispatchers.Default) { SkillEngine.run(this@MainActivity, code, "") }
                            }
                        }
                    }) { Text("Probar") }
                    TextButton(onClick = { editing = null }) { Text("Cancelar") }
                }
            }
        }
    }

    /** Estado del pintor (crear y editar imágenes con IA) y botón para descargarlo. */
    @Composable
    private fun PainterCard() {
        var state by remember { mutableStateOf(Painter.state(this)) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                state = Painter.state(this@MainActivity)
            }
        }
        val model = Painter.model(this)
        Card {
            SectionTitle(
                "Imágenes",
                "Dile \"crea una imagen de…\" o toca una imagen del chat para editarla. Pinta dentro del teléfono, sin internet.",
            )
            Text(
                "Pintor: ${model.name} en la ${if (model.npu) "NPU (rápido)" else "GPU"}",
                color = UltraColors.Text,
                fontSize = 13.sp,
            )
            when (val s = state) {
                PainterState.Ready -> Text("Listo para pintar.", color = UltraColors.Accent)
                PainterState.None -> GlowButton(
                    "Descargar el pintor (${model.sizeMb} MB)",
                    onClick = {
                        Painter.startDownload(this@MainActivity)
                        state = Painter.state(this@MainActivity)
                    },
                    Modifier.fillMaxWidth(),
                )
                is PainterState.Downloading -> {
                    Text("Descargando… ${(s.progress * 100).toInt()} %", color = UltraColors.Accent)
                    LinearProgressIndicator(
                        progress = { s.progress },
                        color = UltraColors.AccentBright,
                        trackColor = UltraColors.Glass,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )
                }
                PainterState.Unpacking -> Text("Preparando el modelo…", color = UltraColors.Accent)
                is PainterState.Failed -> {
                    Text("Falló: ${s.message}", color = UltraColors.Muted)
                    GlowButton("Reintentar", onClick = { Painter.retry(this@MainActivity) }, Modifier.fillMaxWidth())
                }
            }
        }
    }

    @Composable
    private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = UltraColors.Text, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = UltraColors.Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = UltraColors.Accent,
                ),
            )
        }
    }

    /** Tarjeta para aceptar o rechazar un cambio que la IA quiere hacerse a sí misma. */
    @Composable
    private fun SelfChangeCard(change: SelfChange) {
        Column(
            Modifier
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                .glass()
                .border(1.dp, UltraColors.AccentBright, RoundedCornerShape(20.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle(if (change.undoable) "Ultra Yo quiere cambiarse" else "Ultra Yo necesita tu permiso", change.description)
            if (change.detail.isNotBlank()) {
                Text(
                    change.detail.take(4000),
                    color = UltraColors.Text,
                    fontSize = 13.sp,
                    fontFamily = if (change.restartsSkills) FontFamily.Monospace else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .glass(12.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlowButton("Aplicar", onClick = {
                    if (change.undoable) memory.snapshot()
                    change.apply(memory)
                    SelfEdits.pending.value = null
                    brainDirty = true
                    val what = change.description.replaceFirstChar { it.lowercase() }
                    Assistant.say(
                        when {
                            change.restartsSkills -> "Hecho: $what. He reiniciado mi motor de habilidades y ya está cargado. Si no te gusta, dime \"deshaz el último cambio\"."
                            change.undoable -> "Hecho: $what. Si no te gusta, dime \"deshaz el último cambio\"."
                            else -> "Hecho: $what."
                        }
                    )
                })
                TextButton(onClick = {
                    SelfEdits.pending.value = null
                    Assistant.say("Vale, lo dejo como estaba.")
                }) { Text("No") }
            }
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

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Bubble(turn: Turn) {
        val mine = turn.role == "user"
        val clipboard = LocalClipboardManager.current
        fun copy() {
            clipboard.setText(AnnotatedString(turn.content))
            Toast.makeText(this, "Mensaje copiado", Toast.LENGTH_SHORT).show()
        }
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
                    // Mantener pulsado un mensaje lo copia.
                    .combinedClickable(onClick = {}, onLongClick = { copy() })
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!mine) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "ULTRA YO",
                            color = UltraColors.Accent,
                            fontSize = 10.sp,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "COPIAR",
                            color = UltraColors.Muted,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { copy() }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                turn.image?.let { path ->
                    Box(Modifier.clickable(enabled = !locked && !thinking) { Studio.editing = path }) {
                        Thumbnail(path, if (mine) 180.dp else 260.dp)
                    }
                }
                if (turn.image == null && turn.fileName != null) {
                    Text("📄  ${turn.fileName}", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
                Text(turn.content, color = UltraColors.Text, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
    }

    /** Lo que va saliendo mientras pinta. */
    @Composable
    private fun PaintingPreview(preview: Bitmap) {
        Image(
            preview.asImageBitmap(),
            contentDescription = "Imagen pintándose",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(260.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, UltraColors.GlassBorder, RoundedCornerShape(18.dp)),
        )
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
