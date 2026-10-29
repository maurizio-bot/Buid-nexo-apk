package com.ultrayo.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val MAX_UNDO = 15

private enum class Tool(val label: String) {
    Crop("✂️ Recortar"),
    Adjust("🎚️ Ajustes"),
    Filters("🎨 Filtros"),
    Text("🔤 Texto"),
    Ai("✨ Con IA"),
}

private val TEXT_COLORS = listOf(Color.White, Color.Black, Color(0xFFFFD54F), Color(0xFFFF5252), Color(0xFF4FC3F7), Color(0xFF69F0AE))

/**
 * Editor de imágenes a pantalla completa: recortar, girar, espejo, ajustes de luz y color,
 * filtros, texto encima y cambios pintados por la IA del teléfono. [onSaved] recibe la ruta guardada.
 */
@Composable
fun ImageEditor(path: String, onClose: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val versions = remember(path) { mutableStateListOf<Bitmap>().apply { ImageOps.load(path)?.let { add(it) } } }
    var tool by remember { mutableStateOf<Tool?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val current = versions.lastOrNull()
    if (current == null) {
        Column(Modifier.fillMaxSize().background(Background).padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("No pude abrir la imagen.", color = UltraColors.Text)
            TextButton(onClick = onClose) { Text("Cerrar") }
        }
        return
    }

    fun push(bitmap: Bitmap) {
        versions.add(bitmap)
        if (versions.size > MAX_UNDO) versions.removeAt(0)
        tool = null
    }

    // Recorte, en fracciones de la imagen.
    var crop by remember(current) { mutableStateOf(floatArrayOf(0.05f, 0.05f, 0.95f, 0.95f)) }
    // Ajustes
    var brightness by remember(current) { mutableFloatStateOf(0f) }
    var contrast by remember(current) { mutableFloatStateOf(1f) }
    var saturation by remember(current) { mutableFloatStateOf(1f) }
    // Texto
    var words by remember { mutableStateOf("") }
    var textColor by remember { mutableStateOf(Color.White) }
    var textSize by remember { mutableFloatStateOf(0.09f) }
    var textAt by remember(current) { mutableStateOf(Offset(0.5f, 0.85f)) }
    // IA
    var instruction by remember { mutableStateOf("") }
    var strength by remember { mutableFloatStateOf(0.6f) }

    val shown: Bitmap = when {
        working && Studio.preview != null -> Studio.preview!!
        tool == Tool.Text && words.isNotBlank() ->
            remember(current, words, textColor, textSize, textAt) {
                ImageOps.drawText(current, words, textAt.x, textAt.y, textSize, textColor.toArgb())
            }
        else -> current
    }
    val liveFilter = if (tool == Tool.Adjust) {
        ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(ImageOps.adjustments(brightness, contrast, saturation).array))
    } else {
        null
    }

    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 12.dp)) {
        // Barra de arriba
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cerrar editor", tint = UltraColors.Accent) }
            Text("EDITOR", color = UltraColors.Text, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { versions.removeAt(versions.lastIndex) }, enabled = versions.size > 1 && !working) { Text("↶ Deshacer") }
            TextButton(
                onClick = {
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { Studio.keep(context, current) }
                        onSaved(saved)
                    }
                },
                enabled = !working,
            ) { Text("Guardar") }
        }

        // La imagen
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(shown.width.toFloat() / shown.height)) {
                Image(
                    shown.asImageBitmap(),
                    contentDescription = "Imagen que estás editando",
                    contentScale = ContentScale.FillBounds,
                    colorFilter = liveFilter,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                )
                if (tool == Tool.Crop) CropOverlay(crop) { crop = it }
                if (tool == Tool.Text) {
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectTapGestures { p -> textAt = Offset(p.x / size.width, p.y / size.height) }
                        }.pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                textAt = Offset(
                                    (change.position.x / size.width).coerceIn(0f, 1f),
                                    (change.position.y / size.height).coerceIn(0f, 1f),
                                )
                            }
                        }
                    )
                }
            }
        }

        (Studio.progress?.takeIf { working } ?: message)?.let {
            Text(it, color = UltraColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        // Herramientas
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("🔄 Girar", enabled = !working) { push(ImageOps.rotate(current, 90f)) }
            Chip("↔️ Espejo", enabled = !working) { push(ImageOps.flip(current)) }
            Tool.entries.forEach { t ->
                Chip(t.label, selected = tool == t, enabled = !working) {
                    message = null
                    tool = if (tool == t) null else t
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (tool) {
                Tool.Crop -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Libre" to 0f, "1:1" to 1f, "4:3" to 4f / 3, "3:4" to 3f / 4, "16:9" to 16f / 9, "9:16" to 9f / 16).forEach { (label, ratio) ->
                            Chip(label) { crop = centeredCrop(ratio, current.width.toFloat() / current.height) }
                        }
                    }
                    Text("Arrastra las esquinas o el centro del recuadro.", color = UltraColors.Muted, fontSize = 12.sp)
                    GlowButton("Recortar", onClick = { push(ImageOps.crop(current, crop[0], crop[1], crop[2], crop[3])) }, Modifier.fillMaxWidth())
                }
                Tool.Adjust -> {
                    LabeledSlider("Brillo", brightness, -100f..100f) { brightness = it }
                    LabeledSlider("Contraste", contrast, 0.5f..1.8f) { contrast = it }
                    LabeledSlider("Saturación", saturation, 0f..2f) { saturation = it }
                    GlowButton("Aplicar", onClick = {
                        push(ImageOps.applyMatrix(current, ImageOps.adjustments(brightness, contrast, saturation)))
                    }, Modifier.fillMaxWidth())
                }
                Tool.Filters -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Filter.entries.forEach { f -> Chip(f.label) { push(ImageOps.filter(current, f)) } }
                }
                Tool.Text -> {
                    OutlinedTextField(
                        value = words,
                        onValueChange = { words = it },
                        label = { Text("Texto") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TEXT_COLORS.forEach { c ->
                            Box(
                                Modifier.size(30.dp).clip(CircleShape).background(c)
                                    .border(2.dp, if (c == textColor) UltraColors.AccentBright else UltraColors.GlassBorder, CircleShape)
                                    .clickable { textColor = c }
                            )
                        }
                    }
                    LabeledSlider("Tamaño", textSize, 0.04f..0.2f) { textSize = it }
                    Text("Toca o arrastra sobre la imagen para colocarlo.", color = UltraColors.Muted, fontSize = 12.sp)
                    GlowButton("Poner texto", onClick = {
                        push(ImageOps.drawText(current, words, textAt.x, textAt.y, textSize, textColor.toArgb()))
                        words = ""
                    }, Modifier.fillMaxWidth(), enabled = words.isNotBlank())
                }
                Tool.Ai -> {
                    OutlinedTextField(
                        value = instruction,
                        onValueChange = { instruction = it },
                        label = { Text("¿Qué cambio? Ej.: estilo anime, que sea de noche…") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !working,
                    )
                    LabeledSlider("Cuánto cambia", strength, 0.2f..0.9f) { strength = it }
                    Text("La IA la vuelve a pintar en 512×512 (recortada al centro).", color = UltraColors.Muted, fontSize = 12.sp)
                    GlowButton(
                        if (working) "Pintando…" else "Cambiar con IA",
                        onClick = {
                            val notReady = Studio.notReadyMessage(context)
                            if (notReady != null) {
                                message = notReady
                                return@GlowButton
                            }
                            working = true
                            message = null
                            scope.launch {
                                try {
                                    // Se guarda primero para que la IA que ve fotos pueda mirarla.
                                    val sourcePath = withContext(Dispatchers.IO) { ImageOps.saveInApp(context, current, "editando") }
                                    push(Studio.aiEdit(context, current, instruction, strength, sourcePath))
                                } catch (e: Exception) {
                                    message = "No pude cambiarla: ${e.message}"
                                } finally {
                                    working = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = instruction.isNotBlank() && !working,
                    )
                }
                null -> Text(
                    "Elige una herramienta. Al guardar, la imagen va al chat y a tu galería.",
                    color = UltraColors.Muted,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** Un recuadro centrado con la proporción [ratio] (ancho/alto) dentro de una imagen de proporción [image]. 0 = libre. */
private fun centeredCrop(ratio: Float, image: Float): FloatArray {
    if (ratio == 0f) return floatArrayOf(0.05f, 0.05f, 0.95f, 0.95f)
    // Ancho y alto en fracciones de la imagen.
    var w = 1f
    var h = image / ratio
    if (h > 1f) {
        w = ratio / image
        h = 1f
    }
    return floatArrayOf((1 - w) / 2, (1 - h) / 2, (1 + w) / 2, (1 + h) / 2)
}

/** El recuadro de recorte: se mueve arrastrando por dentro y cambia de tamaño por las esquinas. */
@Composable
private fun CropOverlay(rect: FloatArray, onChange: (FloatArray) -> Unit) {
    var dragging by remember { mutableStateOf(-1) } // 0..3 esquinas, 4 = mover, -1 = nada
    val current by androidx.compose.runtime.rememberUpdatedState(rect)
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { p ->
                    val r = current
                    val corners = listOf(
                        Offset(r[0] * size.width, r[1] * size.height),
                        Offset(r[2] * size.width, r[1] * size.height),
                        Offset(r[0] * size.width, r[3] * size.height),
                        Offset(r[2] * size.width, r[3] * size.height),
                    )
                    val near = corners.indexOfFirst { abs(it.x - p.x) < 60 && abs(it.y - p.y) < 60 }
                    dragging = if (near >= 0) near else 4
                },
                onDragEnd = { dragging = -1 },
                onDrag = { change, amount ->
                    change.consume()
                    val dx = amount.x / size.width
                    val dy = amount.y / size.height
                    val r = current.copyOf()
                    val min = 0.08f
                    when (dragging) {
                        0 -> { r[0] = (r[0] + dx).coerceIn(0f, r[2] - min); r[1] = (r[1] + dy).coerceIn(0f, r[3] - min) }
                        1 -> { r[2] = (r[2] + dx).coerceIn(r[0] + min, 1f); r[1] = (r[1] + dy).coerceIn(0f, r[3] - min) }
                        2 -> { r[0] = (r[0] + dx).coerceIn(0f, r[2] - min); r[3] = (r[3] + dy).coerceIn(r[1] + min, 1f) }
                        3 -> { r[2] = (r[2] + dx).coerceIn(r[0] + min, 1f); r[3] = (r[3] + dy).coerceIn(r[1] + min, 1f) }
                        4 -> {
                            val mx = dx.coerceIn(-r[0], 1f - r[2])
                            val my = dy.coerceIn(-r[1], 1f - r[3])
                            r[0] += mx; r[2] += mx; r[1] += my; r[3] += my
                        }
                    }
                    onChange(r)
                },
            )
        }
    ) {
        val l = rect[0] * size.width
        val t = rect[1] * size.height
        val r = rect[2] * size.width
        val b = rect[3] * size.height
        val shade = Color.Black.copy(alpha = 0.55f)
        drawRect(shade, Offset.Zero, Size(size.width, t))
        drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(shade, Offset(0f, t), Size(l, b - t))
        drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
        drawRect(UltraColors.AccentBright, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
        listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach {
            drawCircle(UltraColors.AccentBright, radius = 9.dp.toPx(), center = it)
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.White else UltraColors.Text,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) UltraColors.UserA else UltraColors.Glass)
            .border(1.dp, if (selected) UltraColors.AccentBright else UltraColors.GlassBorder, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = UltraColors.Text, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp).size(width = 92.dp, height = 20.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = UltraColors.AccentBright, activeTrackColor = UltraColors.Accent),
            modifier = Modifier.weight(1f),
        )
    }
}
