package com.ultrayo.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.ultrayo.app.apk.ApkInspector
import java.io.File

/**
 * Ultra abre una APK y la entiende, todo dentro del teléfono y en solo lectura: la APK no se
 * instala, no se ejecuta y no se cambia. Sirve para un archivo .apk (también .xapk/.apks) o
 * para una app ya instalada, buscándola por su nombre.
 *
 * El informe completo (con el manifiesto entero) y el código decompilado se guardan en
 * Ultra Yo/archivos/apk para poder leerlos después con calma.
 */
object ApkReader {

    private const val MAX_CODE = 3000
    private const val MAX_LISTED = 30

    /** Una APK lista para mirar: el archivo en disco, cómo se llama y las APK que la acompañan. */
    private class Source(val apk: File, val name: String, val splits: List<String>, val label: String?)

    fun isApk(name: String) = ApkInspector.isApk(name)

    /** Resumen de una APK (archivo o app instalada). */
    fun analyze(context: Context, ruta: String): String {
        val source = resolve(context, ruta) ?: return notFound(ruta)
        return analyze(context, source)
    }

    /** Resumen de un .apk que Maurizio adjunta en el chat. */
    fun analyzeAttachment(context: Context, uri: Uri, name: String): String {
        val source = fromFile(context, uri, name, uri.toString())
        synchronized(attached) { attached[name.lowercase()] = source }
        return analyze(context, source)
    }

    /** Lo adjuntado en el chat, para que verCodigoApk lo encuentre por su nombre. */
    private val attached = HashMap<String, Source>()

    private fun analyze(context: Context, source: Source): String {
        val report = ApkInspector.analyze(source.apk, source.name, source.splits, source.label)
        val saved = runCatching {
            val dir = File(Vault.files(context), "apk").apply { mkdirs() }
            val file = File(dir, safe("${report.packageName.ifEmpty { source.name }}-${report.versionName.ifEmpty { "v" }}") + ".txt")
            file.writeText(ApkInspector.fullText(report))
            "Ultra Yo/archivos/apk/${file.name}"
        }.getOrNull()
        return buildString {
            append(ApkInspector.summary(report))
            saved?.let { append("Informe completo con el manifiesto entero: ").append(it).append(" (léelo con leerArchivo).\n") }
            append("Para ver el código de una clase usa verCodigoApk.")
        }
    }

    /**
     * El código de una APK: sin [clase], las clases por dónde empezar; con [clase], su código
     * Java decompilado (o la lista si hay varias que encajan).
     */
    fun code(context: Context, ruta: String, clase: String): String {
        val source = resolve(context, ruta) ?: return notFound(ruta)
        val query = clase.trim()
        if (query.isEmpty()) {
            val report = ApkInspector.analyze(source.apk, source.name, source.splits, source.label)
            val entries = ApkInspector.entryClasses(source.apk, report)
            if (entries.isEmpty()) return "No encontré clases propias de ${report.packageName} (puede que esté ofuscada o hecha con Flutter/Unity, cuyo código no va en el dex)."
            return buildString {
                append("Clases de ").append(report.packageName).append(" por dónde empezar")
                if (report.obfuscated) append(" (está ofuscada: los nombres cortos como a, b, c no dicen nada)")
                append(":\n")
                entries.take(MAX_LISTED).forEach { append("- ").append(it).append('\n') }
                if (entries.size > MAX_LISTED) append("… y ").append(entries.size - MAX_LISTED).append(" más.\n")
                append("Pide una con verCodigoApk poniendo su nombre en clase.")
            }
        }
        val found = ApkInspector.findClasses(source.apk, query)
        if (found.isEmpty()) return "No hay ninguna clase que se llame \"$query\" en ${source.name}."
        if (found.size > 1 && !found.first().equals(query, ignoreCase = true) &&
            !found.first().substringAfterLast('.').equals(query, ignoreCase = true)
        ) {
            return "Hay ${found.size} clases que encajan con \"$query\":\n" +
                found.take(MAX_LISTED).joinToString("\n") { "- $it" } + "\nDime cuál con su nombre completo."
        }
        val name = found.first()
        val javaCode = try {
            ApkInspector.decompile(source.apk, name, File(context.cacheDir, "jadx"))
        } catch (e: OutOfMemoryError) {
            return "La parte del código donde está $name es demasiado grande para decompilarla en la memoria del teléfono."
        }
        val saved = runCatching {
            val dir = File(Vault.files(context), "apk/codigo").apply { mkdirs() }
            val file = File(dir, safe(name) + ".java")
            file.writeText(javaCode)
            "Ultra Yo/archivos/apk/codigo/${file.name}"
        }.getOrNull()
        return buildString {
            append("Código de ").append(name).append(" (decompilado con jadx; los nombres de variables pueden ser inventados):\n")
            append(javaCode.take(MAX_CODE))
            if (javaCode.length > MAX_CODE) {
                append("\n… (recortado: tiene ").append(javaCode.length).append(" letras")
                saved?.let { append("; entero en ").append(it) }
                append(")")
            }
        }
    }

    private fun notFound(ruta: String) =
        "No encontré \"$ruta\": ni un archivo .apk (búscalo con buscarArchivos) ni una app instalada con ese nombre."

    /** Una ruta de buscarArchivos, o el nombre (o paquete) de una app instalada. */
    private fun resolve(context: Context, ruta: String): Source? {
        val wanted = ruta.trim()
        if (wanted.isEmpty()) return null
        synchronized(attached) { attached[wanted.lowercase()] ?: attached[wanted.substringAfterLast('/').lowercase()] }
            ?.takeIf { it.apk.exists() }?.let { return it }
        // "WhatsApp" es una app instalada; "Download/juego.apk" es un archivo.
        val looksLikeFile = wanted.contains('/') || isApk(wanted)
        if (!looksLikeFile) installed(context, wanted)?.let { return it }
        File(wanted).takeIf { it.isFile && isApk(it.name) && it.canRead() }?.let { return fromFile(context, Uri.fromFile(it), it.name, it.path) }
        DeviceFiles.find(context, wanted)?.let { file ->
            if (isApk(file.name)) return fromFile(context, file.uri, file.name, file.path + file.size)
        }
        return null
    }

    private fun fromFile(context: Context, uri: Uri, name: String, key: String): Source {
        val local = if (uri.scheme == "file") File(uri.path!!) else cachedCopy(context, uri, name, key)
        val (apk, splits) = ApkInspector.baseApk(local, File(context.cacheDir, "apk-base"))
        return Source(apk, name, splits, label(context, apk))
    }

    /** Las APK de content:// se copian una vez a la caché (solo se guarda la última, pueden pesar mucho). */
    private fun cachedCopy(context: Context, uri: Uri, name: String, key: String): File {
        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        val target = File(dir, "${key.hashCode().toUInt()}-${safe(name).takeLast(40)}")
        if (target.exists() && target.length() > 0) return target
        dir.listFiles()?.forEach { it.delete() }
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: error("no pude abrir $name")
        return target
    }

    /** Una app instalada por su nombre visible o su paquete. Solo se lee su APK. */
    private fun installed(context: Context, wanted: String): Source? {
        val pm = context.packageManager
        val key = wanted.lowercase().removeSuffix(".apk")
        val apps: List<ApplicationInfo> = runCatching {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0).map { it.activityInfo.applicationInfo } +
                pm.getInstalledApplications(0)
        }.getOrDefault(emptyList()).distinctBy { it.packageName }
        fun labelOf(info: ApplicationInfo) = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName)
        val match = apps.firstOrNull { it.packageName.equals(key, true) }
            ?: apps.firstOrNull { labelOf(it).equals(wanted, true) }
            ?: apps.firstOrNull { labelOf(it).lowercase().contains(key) }
            ?: return null
        val apk = File(match.sourceDir ?: return null)
        if (!apk.canRead()) return null
        val splits = match.splitSourceDirs.orEmpty().map { File(it).name }
        return Source(apk, "${labelOf(match)} (instalada)", splits, labelOf(match))
    }

    /** El nombre visible de la app, que Android saca de sus recursos. */
    private fun label(context: Context, apk: File): String? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_META_DATA)?.applicationInfo ?: return null
        info.sourceDir = apk.path
        info.publicSourceDir = apk.path
        info.loadLabel(pm).toString().takeIf { it.isNotBlank() && it != info.packageName }
    }.getOrNull()

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
}
