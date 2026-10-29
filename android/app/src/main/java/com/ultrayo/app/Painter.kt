package com.ultrayo.app

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

private const val PORT = 18081
private const val EXECUTABLE = "libstable_diffusion_core.so"
private const val SIZE = 512

/** Cerrar el pintor tras este rato sin usarlo, para devolverle la memoria a la IA que habla. */
private const val IDLE_MS = 3 * 60_000L

/**
 * Un modelo de Stable Diffusion 1.5 ya preparado para el teléfono por Local Dream.
 * [npu] dice si corre en la NPU del Snapdragon (rápido) o en la GPU con MNN (más lento, vale para todo).
 */
data class PaintModel(val name: String, val url: String, val npu: Boolean, val sizeMb: Int)

/** Qué hay que pintar. Con [source] se cambia esa imagen en lugar de empezar de cero. */
data class PaintRequest(
    val prompt: String,
    val negative: String = DEFAULT_NEGATIVE,
    val steps: Int = 20,
    val cfg: Float = 7f,
    val seed: Int = (0..Int.MAX_VALUE).random(),
    val source: Bitmap? = null,
    /** Cuánto se aleja de [source]: 0 la deja igual y 1 la pinta de nuevo. */
    val strength: Float = 0.6f,
) {
    companion object {
        const val DEFAULT_NEGATIVE =
            "lowres, bad anatomy, bad hands, extra fingers, blurry, deformed, ugly, watermark, text, signature, jpeg artifacts"
    }
}

sealed interface PainterState {
    data object None : PainterState
    data class Downloading(val progress: Float) : PainterState
    data object Unpacking : PainterState
    data object Ready : PainterState
    data class Failed(val message: String) : PainterState
}

/**
 * La parte de Ultra Yo que pinta: Stable Diffusion dentro del teléfono, sin internet ni coste.
 * Usa el motor de Local Dream, que corre como un programa aparte con un pequeño servidor en el
 * propio teléfono (127.0.0.1); aquí se arranca, se le piden imágenes y se cierra cuando no se usa.
 */
object Painter {

    /** Para el Snapdragon 8 Gen 3 del S24 Ultra (y el 8s Gen 3), que tienen la NPU Hexagon V75. */
    private val NPU_CHIPS = setOf("SM8650", "SM8635")

    private val NPU_MODEL = PaintModel(
        "DreamShaperV8",
        "https://huggingface.co/xororz/sd-qnn/resolve/main/DreamShaperV8_qnn2.28_8gen2.zip",
        npu = true,
        sizeMb = 1030,
    )
    private val GPU_MODEL = PaintModel(
        "AbsoluteReality",
        "https://huggingface.co/xororz/sd-mnn/resolve/main/AbsoluteReality.zip",
        npu = false,
        sizeMb = 1190,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var process: Process? = null
    private var running: PaintModel? = null
    private var idleStop: Job? = null
    private var unpacking: Job? = null

    @Volatile
    var busy = false
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("ultrayo_pintor", Context.MODE_PRIVATE)

    fun hasNpu(): Boolean = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL in NPU_CHIPS

    /** El modelo que toca: el de la NPU si el chip la tiene y no ha fallado antes. */
    fun model(context: Context): PaintModel =
        if (hasNpu() && !prefs(context).getBoolean("npu_failed", false)) NPU_MODEL else GPU_MODEL

    private fun modelDir(context: Context, model: PaintModel) = File(context.filesDir, "pintor/${model.name}")
    private fun zipFile(context: Context, model: PaintModel) =
        File(context.getExternalFilesDir("pintor"), "${model.name}.zip")

    fun isReady(context: Context) = File(modelDir(context, model(context)), ".listo").exists()

    // --- Descarga ---

    fun startDownload(context: Context) {
        val model = model(context)
        if (isReady(context)) return
        val zip = zipFile(context, model)
        zip.delete()
        val manager = context.getSystemService(DownloadManager::class.java)
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Pintor de Ultra Yo")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(zip))
        val id = manager.enqueue(request)
        prefs(context).edit().putLong("download_id", id).putString("download_model", model.name).apply()
    }

    /** Cómo va todo. Si la descarga ya terminó, empieza a descomprimirla. */
    fun state(context: Context): PainterState {
        val model = model(context)
        if (isReady(context)) return PainterState.Ready
        if (unpacking?.isActive == true) return PainterState.Unpacking
        prefs(context).getString("unpack_error", null)?.let { return PainterState.Failed(it) }
        val prefs = prefs(context)
        val id = prefs.getLong("download_id", -1)
        if (id == -1L || prefs.getString("download_model", null) != model.name) return PainterState.None
        val manager = context.getSystemService(DownloadManager::class.java)
        manager.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return PainterState.None
            return when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    unpack(context.applicationContext, model)
                    PainterState.Unpacking
                }
                DownloadManager.STATUS_FAILED ->
                    PainterState.Failed("la descarga falló (código ${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})")
                else -> {
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    PainterState.Downloading(if (total > 0) done.toFloat() / total else 0f)
                }
            }
        }
    }

    private fun unpack(context: Context, model: PaintModel) {
        if (unpacking?.isActive == true) return
        unpacking = scope.launch {
            val zip = zipFile(context, model)
            val target = modelDir(context, model)
            try {
                target.deleteRecursively()
                target.mkdirs()
                val root = target.canonicalPath + File.separator
                ZipInputStream(zip.inputStream().buffered()).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val out = File(target, entry.name)
                        if (!out.canonicalPath.startsWith(root)) continue
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
                checkNotNull(findModelFiles(target, model)) { "el zip no trae el modelo" }
                File(target, ".listo").createNewFile()
                zip.delete()
                prefs(context).edit().remove("unpack_error").apply()
            } catch (e: Exception) {
                target.deleteRecursively()
                prefs(context).edit().putString("unpack_error", "no pude descomprimir el modelo: ${e.message}")
                    .remove("download_id").apply()
            }
        }
    }

    /** Vuelve a empezar después de un fallo. */
    fun retry(context: Context) {
        prefs(context).edit().remove("unpack_error").remove("download_id").apply()
        startDownload(context)
    }

    /** La carpeta con los archivos del modelo: a veces el zip los trae dentro de otra carpeta. */
    private fun findModelFiles(dir: File, model: PaintModel): File? {
        val marker = if (model.npu) "unet.bin" else "unet.mnn"
        return dir.walkTopDown().maxDepth(3).firstOrNull { File(it, marker).exists() }
    }

    // --- Motor ---

    /** Copia las librerías de la NPU fuera del APK, donde el motor las puede abrir. */
    private fun runtimeDir(context: Context): File {
        val dir = File(context.filesDir, "pintor/qnn").apply { mkdirs() }
        // Se vuelven a copiar solo cuando se instala una versión nueva de la app.
        val stamp = File(dir, ".version")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.takeIf { it.exists() }?.readText() != installed) {
            context.assets.list("qnn").orEmpty().forEach { name ->
                val out = File(dir, name)
                context.assets.open("qnn/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
                out.setReadable(true, true)
                out.setExecutable(true, true)
            }
            stamp.writeText(installed)
        }
        return dir
    }

    private fun command(context: Context, model: PaintModel, files: File, runtime: File): List<String> {
        val executable = File(context.applicationInfo.nativeLibraryDir, EXECUTABLE)
        check(executable.exists()) { "falta el motor de imágenes en la app" }
        fun f(name: String) = File(files, name).path
        // Siempre "clip.mnn": el motor busca solo clip_v2.mnn y sus pesos en la misma carpeta.
        val hasMnnClip = File(files, "clip.mnn").exists() || File(files, "clip_v2.mnn").exists()
        val cmd = mutableListOf(executable.path)
        if (model.npu) {
            cmd += listOf(
                "--clip", f(if (hasMnnClip) "clip.mnn" else "clip.bin"),
                "--unet", f("unet.bin"),
                "--vae_decoder", f("vae_decoder.bin"),
                "--tokenizer", f("tokenizer.json"),
                "--backend", File(runtime, "libQnnHtp.so").path,
                "--system_library", File(runtime, "libQnnSystem.so").path,
                "--port", PORT.toString(),
                "--text_embedding_size", "768",
            )
            if (hasMnnClip) cmd += "--use_cpu_clip"
            if (File(files, "vae_encoder.bin").exists()) cmd += listOf("--vae_encoder", f("vae_encoder.bin"))
        } else {
            cmd += listOf(
                "--clip", f("clip.mnn"),
                "--unet", f("unet.mnn"),
                "--vae_decoder", f("vae_decoder.mnn"),
                "--tokenizer", f("tokenizer.json"),
                "--port", PORT.toString(),
                "--text_embedding_size", "768",
                "--cpu",
            )
            if (File(files, "vae_encoder.mnn").exists()) cmd += listOf("--vae_encoder", f("vae_encoder.mnn"))
        }
        return cmd
    }

    /** Si el modelo descargado sabe cambiar una imagen existente (necesita el codificador VAE). */
    fun canEdit(context: Context): Boolean {
        val model = model(context)
        val files = findModelFiles(modelDir(context, model), model) ?: return false
        return File(files, if (model.npu) "vae_encoder.bin" else "vae_encoder.mnn").exists()
    }

    private suspend fun ensureRunning(context: Context): PaintModel {
        val model = model(context)
        if (process?.isAlive == true && running == model && healthy()) return model
        stop()
        val files = checkNotNull(findModelFiles(modelDir(context, model), model)) { "el modelo no está descargado" }
        val runtime = runtimeDir(context)
        val builder = ProcessBuilder(command(context, model, files, runtime))
            .directory(files)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("LD_LIBRARY_PATH", listOf(runtime.path, "/system/lib64", "/vendor/lib64", "/vendor/lib64/egl").joinToString(":"))
            put("DSP_LIBRARY_PATH", runtime.path)
            put("ADSP_LIBRARY_PATH", runtime.path)
            put("MNN_OPENCL_TUNING", "WIDE")
        }
        val log = ArrayDeque<String>()
        val started = builder.start()
        process = started
        running = model
        // Hay que leer lo que escribe para que no se bloquee; guardamos el final por si falla.
        scope.launch {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) {
                        log.addLast(line)
                        if (log.size > 15) log.removeFirst()
                    }
                }
            }
        }
        // La primera vez la NPU prepara su caché y tarda más.
        val deadline = System.currentTimeMillis() + if (model.npu) 120_000 else 180_000
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive) break
            if (healthy()) return model
            delay(500)
        }
        stop()
        val tail = synchronized(log) { log.joinToString("\n") }
        if (model.npu) {
            // Si la NPU no arranca, la próxima vez se usa la versión para GPU (otra descarga).
            prefs(context).edit().putBoolean("npu_failed", true).apply()
            throw IOException("la NPU no arrancó, así que cambio a la versión para GPU y hay que descargarla. $tail")
        }
        throw IOException("el motor no arrancó. $tail")
    }

    private fun healthy(): Boolean = try {
        val c = URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1000
        c.readTimeout = 1000
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    fun stop() {
        process?.let { p ->
            p.destroy()
            if (!runCatching { p.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)) p.destroyForcibly()
        }
        process = null
        running = null
    }

    private fun scheduleStop() {
        idleStop?.cancel()
        idleStop = scope.launch {
            delay(IDLE_MS)
            lock.withLock { stop() }
        }
    }

    // --- Pintar ---

    /**
     * Pinta una imagen. [onProgress] recibe el paso, el total y, a veces, un avance borroso de la imagen.
     * Tarda unos segundos en la NPU y bastante más en la GPU.
     */
    suspend fun paint(
        context: Context,
        request: PaintRequest,
        onProgress: (step: Int, total: Int, preview: Bitmap?) -> Unit,
    ): Bitmap = withContext(Dispatchers.IO) {
        lock.withLock {
            busy = true
            idleStop?.cancel()
            try {
                val model = ensureRunning(context.applicationContext)
                generate(request, model, onProgress)
            } finally {
                busy = false
                scheduleStop()
            }
        }
    }

    private fun generate(
        request: PaintRequest,
        model: PaintModel,
        onProgress: (Int, Int, Bitmap?) -> Unit,
    ): Bitmap {
        val body = JSONObject()
            .put("prompt", request.prompt)
            .put("negative_prompt", request.negative)
            .put("steps", request.steps)
            .put("cfg", request.cfg.toDouble())
            .put("seed", request.seed)
            .put("width", SIZE)
            .put("height", SIZE)
            .put("scheduler", "dpm")
            .put("use_opencl", !model.npu)
            .put("show_diffusion_process", true)
            .put("show_diffusion_stride", 4)
        request.source?.let { source ->
            body.put("image", base64Png(ImageOps.squareOf(source, SIZE)))
            body.put("denoise_strength", request.strength.coerceIn(0.05f, 1f).toDouble())
        }
        val c = URL("http://127.0.0.1:$PORT/generate").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 0
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) {
                val error = c.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IOException("el motor contestó ${c.responseCode}: ${messageOf(error)}")
            }
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data: ")) continue
                    val data = JSONObject(line.substring(6))
                    when (data.optString("type")) {
                        "progress" -> {
                            val preview = data.optString("image").takeIf { it.isNotEmpty() }
                                ?.let { runCatching { decode(it, data.optString("format"), SIZE, SIZE) }.getOrNull() }
                            onProgress(data.getInt("step"), data.getInt("total_steps"), preview)
                        }
                        "complete" -> return decode(
                            data.getString("image"),
                            data.optString("format"),
                            data.getInt("width"),
                            data.getInt("height"),
                        )
                        "error" -> throw IOException(data.optString("message", "error desconocido"))
                    }
                }
            }
            throw IOException("el motor se cortó sin terminar la imagen")
        } finally {
            c.disconnect()
        }
    }

    private fun messageOf(error: String): String =
        runCatching { JSONObject(error).getJSONObject("error").getString("message") }.getOrDefault(error.take(200))

    private fun base64Png(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    /** El motor devuelve PNG/JPEG o, por defecto, los píxeles RGB en crudo. */
    private fun decode(base64: String, format: String, width: Int, height: Int): Bitmap {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        if (format == "png" || format == "jpeg") {
            return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "imagen ilegible" }
        }
        check(bytes.size == width * height * 3) { "tamaño de imagen inesperado" }
        val pixels = IntArray(width * height) { i ->
            val p = i * 3
            (0xFF shl 24) or ((bytes[p].toInt() and 0xFF) shl 16) or
                ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p + 2].toInt() and 0xFF)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
