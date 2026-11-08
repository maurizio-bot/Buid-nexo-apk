package com.ultrayo.app.apk

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Entender una APK sin instalarla ni ejecutarla: manifiesto (paquete, versión, permisos,
 * pantallas, servicios…), firma, librerías nativas, recursos, idiomas y un repaso del código
 * (librerías que usa, qué hace con el teléfono, direcciones web). Todo dentro del teléfono y
 * en solo lectura: la APK no se toca. Sin Android, para poder probarlo también en el ordenador.
 */
object ApkInspector {

    class Component(val kind: String, val name: String, val exported: Boolean, val actions: List<String>, val permission: String?)

    class Report(
        val fileName: String,
        val sizeBytes: Long,
        val packageName: String,
        val versionName: String,
        val versionCode: String,
        val minSdk: Int?,
        val targetSdk: Int?,
        val label: String?,
        val appClass: String?,
        val launcher: String?,
        val permissions: List<String>,
        val components: List<Component>,
        val flags: List<String>,
        val manifestXml: String,
        val signer: String?,
        val signSchemes: List<String>,
        val abis: Map<String, List<String>>,
        val frameworks: List<String>,
        val dexFiles: Int,
        val classCount: Int,
        val methodRefCount: Int,
        val ownClasses: Int,
        val topPackages: List<Pair<String, Int>>,
        val sdks: List<String>,
        val behaviours: List<String>,
        val libraryBehaviours: List<String>,
        val hosts: List<String>,
        val obfuscated: Boolean,
        val resources: Map<String, Int>,
        val resourceFiles: Int,
        val assets: Int,
        val languages: List<String>,
        val splits: List<String>,
        val notes: List<String>,
    )

    private const val MAX_DEX_BYTES = 120L shl 20

    fun isApk(name: String): Boolean = name.lowercase().let { n -> BUNDLES.any { n.endsWith(it) } || n.endsWith(".apk") }

    private val BUNDLES = listOf(".xapk", ".apks", ".apkm")

    /**
     * Si es un paquete con varias APK (.xapk, .apks), saca la principal a [work] y devuelve
     * esa y los nombres de las demás; si es una APK normal, la devuelve tal cual.
     */
    fun baseApk(file: File, work: File): Pair<File, List<String>> {
        ZipFile(file).use { zip ->
            if (zip.getEntry("AndroidManifest.xml") != null) return file to emptyList()
            val apks = zip.entries().asSequence().filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") }.toList()
            if (apks.isEmpty()) error("no lleva AndroidManifest.xml ni APK dentro: no parece una app de Android")
            val base = apks.firstOrNull { it.name.substringAfterLast('/').equals("base.apk", true) }
                ?: apks.filterNot { it.name.contains("split", true) || it.name.contains("config.", true) }.maxByOrNull { it.size }
                ?: apks.maxBy { it.size }
            work.mkdirs()
            val out = File(work, "base-${file.nameWithoutExtension.take(40)}.apk")
            zip.getInputStream(base).use { input -> out.outputStream().use { input.copyTo(it) } }
            return out to apks.filter { it != base }.map { it.name.substringAfterLast('/') }
        }
    }

    fun analyze(file: File, displayName: String = file.name, splits: List<String> = emptyList(), label: String? = null): Report {
        val notes = mutableListOf<String>()
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: error("no lleva AndroidManifest.xml: no es una APK")
            val manifest = Axml.parse(zip.getInputStream(manifestEntry).use { it.readBytes() })
            val app = manifest.first("application")
            val pkg = manifest.attr("package").orEmpty()

            val permissions = (manifest.all("uses-permission") + manifest.all("uses-permission-sdk-23"))
                .mapNotNull { it.attr("name") }.distinct()
            val components = mutableListOf<Component>()
            var launcher: String? = null
            for (kind in listOf("activity", "activity-alias", "service", "receiver", "provider")) {
                for (c in app?.all(kind).orEmpty()) {
                    val name = fullName(pkg, c.attr("name") ?: c.attr("targetActivity") ?: continue)
                    val filters = c.all("intent-filter")
                    val actions = filters.flatMap { f -> f.all("action").mapNotNull { it.attr("name") } }
                    val categories = filters.flatMap { f -> f.all("category").mapNotNull { it.attr("name") } }
                    if (launcher == null && "android.intent.action.MAIN" in actions && "android.intent.category.LAUNCHER" in categories) {
                        launcher = name
                    }
                    val exported = c.attr("exported")?.let { it == "true" } ?: filters.isNotEmpty()
                    components += Component(kind.removeSuffix("-alias"), name, exported, actions.distinct(), c.attr("permission"))
                }
            }
            val flags = buildList {
                if (app?.attr("debuggable") == "true") add("es una versión de pruebas (debuggable): cualquiera con un cable puede inspeccionarla")
                if (app?.attr("allowBackup") != "false") add("permite copia de seguridad de sus datos (allowBackup)")
                if (app?.attr("usesCleartextTraffic") == "true") add("permite conexiones sin cifrar (http)")
                if (app?.attr("networkSecurityConfig") != null) add("tiene configuración de red propia (networkSecurityConfig)")
                if (app?.attr("requestLegacyExternalStorage") == "true") add("pide el acceso antiguo a todos los archivos")
                if (app?.attr("largeHeap") == "true") add("pide más memoria de lo normal (largeHeap)")
            }

            // Librerías nativas por procesador
            val abis = entries.filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .groupBy({ it.name.split('/')[1] }, { it.name.substringAfterLast('/') })
            val allLibs = abis.values.flatten().toSet()
            val names = entries.map { it.name }
            val frameworks = buildList {
                if ("libflutter.so" in allLibs || names.any { it.startsWith("assets/flutter_assets/") }) add("Flutter (Dart)")
                if ("libreactnativejni.so" in allLibs || "assets/index.android.bundle" in names) add("React Native (JavaScript)")
                if ("libunity.so" in allLibs || "libil2cpp.so" in allLibs) add("Unity (juego, C#)")
                if (allLibs.any { it.startsWith("libmonosgen") || it.startsWith("libmonodroid") } || names.any { it.startsWith("assemblies/") }) add("Xamarin / .NET MAUI (C#)")
                if (names.any { it.startsWith("assets/www/") } && names.any { it.contains("cordova") }) add("Cordova (web)")
                if (names.any { it.startsWith("assets/public/") } && names.any { it.contains("capacitor") }) add("Capacitor (web)")
                if (allLibs.any { it.startsWith("libgodot") }) add("Godot (juego)")
            }.toMutableList()

            // Código (.dex)
            val dexEntries = entries.filter { it.name.matches(Regex("classes\\d*\\.dex")) }.sortedBy { dexOrder(it.name) }
            val classes = ArrayList<String>()
            val supers = HashMap<String, String>()
            val behaviourHits = LinkedHashSet<String>() // de su propio código
            val libraryHits = LinkedHashSet<String>() // de cualquier parte, librerías incluidas
            val hostCounts = HashMap<String, Int>()
            val libPrefixes = SDKS.map { it.first } + LIB_PREFIXES
            val isOwn = { c: String -> libPrefixes.none { c.startsWith(it) } }
            var methodRefs = 0
            for (entry in dexEntries) {
                if (entry.size > MAX_DEX_BYTES) { notes += "${entry.name} es demasiado grande; no lo he repasado"; continue }
                val dex = runCatching { Dex(zip.getInputStream(entry).use { it.readBytes() }) }.getOrElse {
                    notes += "no pude leer ${entry.name}: ${it.message}"; null
                } ?: continue
                classes += dex.classNames()
                supers += dex.superClasses()
                methodRefs += dex.methodRefs
                for (ref in dex.methodReferences()) {
                    for ((prefix, what) in BEHAVIOURS) if (ref.startsWith(prefix)) libraryHits += what
                }
                // Lo que hace el código de la propia app (sin contar sus librerías)
                val own = dex.codeUse(isOwn)
                for (ref in own.methods) {
                    for ((prefix, what) in BEHAVIOURS) if (ref.startsWith(prefix)) behaviourHits += what
                }
                for (s in own.strings) {
                    if (s.length > 2000) continue
                    if (s.contains("http")) URL.findAll(s).forEach { m ->
                        val host = m.groupValues[1].lowercase().removePrefix("www.")
                        if (host.contains('.') && IGNORED_HOSTS.none { host == it || host.endsWith(".$it") }) hostCounts[host] = (hostCounts[host] ?: 0) + 1
                    }
                    if (s == "/system/xbin/su" || s == "/system/bin/su" || s == "eu.chainfire.supersu") behaviourHits += "comprueba si el teléfono tiene root"
                }
            }
            for ((cls, sup) in supers) SUPERCLASSES[sup]?.let { if (isOwn(cls)) behaviourHits += it else libraryHits += it }
            if (classes.any { it.startsWith("kotlin.") }) frameworks += "Kotlin"
            if (classes.any { it.startsWith("androidx.compose.") }) frameworks += "Jetpack Compose"
            if (frameworks.isEmpty() && classes.isNotEmpty()) frameworks += "Java/Kotlin normal"

            val sdks = SDKS.filter { (prefix, _) -> classes.any { it.startsWith(prefix) } }.map { it.second }.distinct()
            val appCode = classes.filter(isOwn)
            val ownClasses = if (pkg.isNotEmpty()) classes.count { it.startsWith("$pkg.") } else 0
            val topPackages = appCode.groupingBy { packageGroup(it) }.eachCount().entries
                .sortedByDescending { it.value }.take(8).map { it.key to it.value }
            val shortNames = appCode.count { c -> c.split('.').let { parts -> parts.size >= 2 && parts.takeLast(2).all { it.length <= 2 } } }
            val obfuscated = appCode.size > 50 && shortNames * 3 > appCode.size

            // Recursos
            val resEntries = names.filter { it.startsWith("res/") }
            val resources = resEntries.mapNotNull { n -> n.split('/').getOrNull(1)?.substringBefore('-')?.takeIf { n.count { c -> c == '/' } >= 2 } }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.associate { it.key to it.value }
            val languages = runCatching {
                zip.getEntry("resources.arsc")?.let { e -> if (e.size in 1..(64L shl 20)) locales(zip.getInputStream(e).use { it.readBytes() }) else null }
            }.getOrNull().orEmpty()

            val (signer, schemes) = runCatching { signature(file, zip) }.getOrElse { null to emptyList() }
            val sdk = manifest.first("uses-sdk")

            return Report(
                fileName = displayName,
                sizeBytes = file.length(),
                packageName = pkg,
                versionName = manifest.attr("versionName").orEmpty(),
                versionCode = manifest.attr("versionCode").orEmpty(),
                minSdk = sdk?.attr("minSdkVersion")?.toIntOrNull(),
                targetSdk = sdk?.attr("targetSdkVersion")?.toIntOrNull(),
                label = label ?: app?.attr("label")?.takeIf { !it.startsWith("@") },
                appClass = app?.attr("name")?.let { fullName(pkg, it) },
                launcher = launcher,
                permissions = permissions,
                components = components,
                flags = flags,
                manifestXml = Axml.toXml(manifest),
                signer = signer,
                signSchemes = schemes,
                abis = abis,
                frameworks = frameworks.distinct(),
                dexFiles = dexEntries.size,
                classCount = classes.size,
                methodRefCount = methodRefs,
                ownClasses = ownClasses,
                topPackages = topPackages,
                sdks = sdks,
                behaviours = behaviourHits.toList(),
                libraryBehaviours = (libraryHits - behaviourHits).toList(),
                hosts = hostCounts.entries.sortedByDescending { it.value }.map { it.key }.take(15),
                obfuscated = obfuscated,
                resources = resources,
                resourceFiles = resEntries.size,
                assets = names.count { it.startsWith("assets/") },
                languages = languages,
                splits = splits,
                notes = notes,
            )
        }
    }

    /** Lo importante en pocas líneas (cabe en la conversación con el modelo). */
    fun summary(r: Report): String = buildString {
        append("APK ").append(r.fileName).append(" (").append(human(r.sizeBytes)).append(")\n")
        r.label?.let { append("App: ").append(it).append('\n') }
        append("Paquete: ").append(r.packageName.ifEmpty { "?" })
        append(" · versión ").append(r.versionName.ifEmpty { "?" })
        if (r.versionCode.isNotEmpty()) append(" (").append(r.versionCode).append(')')
        append('\n')
        append("Android: mínimo ").append(androidName(r.minSdk)).append(", pensada para ").append(androidName(r.targetSdk)).append('\n')
        if (r.splits.isNotEmpty()) append("Paquete con ").append(r.splits.size + 1).append(" APK (principal + ").append(r.splits.joinToString(", ").take(150)).append(")\n")
        r.signer?.let { append("Firma: ").append(it) }
        if (r.signSchemes.isNotEmpty()) append(if (r.signer != null) " · " else "Firma: ").append(r.signSchemes.joinToString("+"))
        if (r.signer != null || r.signSchemes.isNotEmpty()) append('\n')
        append("Hecha con: ").append(r.frameworks.joinToString(", ").ifEmpty { "?" }).append('\n')

        val dangerous = r.permissions.filter { DANGEROUS[it] != null }
        append("Permisos (").append(r.permissions.size).append(")")
        if (dangerous.isNotEmpty()) {
            append(", sensibles: ").append(dangerous.joinToString("; ") { "${short(it)} (${DANGEROUS[it]})" })
        }
        val others = r.permissions.filter { DANGEROUS[it] == null }.map { short(it) }
        if (others.isNotEmpty()) append(". Otros: ").append(others.take(14).joinToString(", ")).append(if (others.size > 14) "…" else "")
        append('\n')

        val counts = r.components.groupingBy { it.kind }.eachCount()
        append("Partes: ").append(listOf("activity" to "pantallas", "service" to "servicios", "receiver" to "receptores", "provider" to "proveedores de datos")
            .joinToString(", ") { (k, n) -> "${counts[k] ?: 0} $n" }).append('\n')
        r.launcher?.let { append("Pantalla de inicio: ").append(it).append('\n') }
        r.appClass?.let { append("Clase de la app: ").append(it).append('\n') }
        val open = r.components.filter { it.exported && it.permission == null && it.name != r.launcher }
        if (open.isNotEmpty()) {
            append("Abiertas a otras apps sin permiso (").append(open.size).append("): ")
            append(open.take(6).joinToString("; ") { c ->
                "${c.kind} ${c.name.substringAfterLast('.')}" + if (c.actions.isNotEmpty()) " [${c.actions.take(2).joinToString { it.substringAfterLast('.') }}]" else ""
            })
            if (open.size > 6) append("…")
            append('\n')
        }
        if (r.flags.isNotEmpty()) append("Ajustes: ").append(r.flags.joinToString("; ")).append('\n')

        append("Código: ").append(r.dexFiles).append(" dex, ").append(r.classCount).append(" clases")
        if (r.ownClasses > 0) append(" (").append(r.ownClasses).append(" en ").append(r.packageName).append(')')
        if (r.obfuscated) append(", ofuscado (nombres tipo a.b.c)")
        append('\n')
        if (r.topPackages.isNotEmpty()) append("Paquetes propios: ").append(r.topPackages.take(6).joinToString(", ") { "${it.first} (${it.second})" }).append('\n')
        if (r.sdks.isNotEmpty()) append("Librerías conocidas: ").append(r.sdks.joinToString(", ")).append('\n')
        if (r.behaviours.isNotEmpty()) append("Lo que hace su propio código: ").append(r.behaviours.joinToString("; ")).append('\n')
        if (r.libraryBehaviours.isNotEmpty()) append("Además sus librerías: ").append(r.libraryBehaviours.joinToString("; ")).append('\n')
        if (r.hosts.isNotEmpty()) append("Direcciones web en su código: ").append(r.hosts.take(10).joinToString(", ")).append('\n')

        if (r.abis.isNotEmpty()) {
            append("Librerías nativas: ").append(r.abis.keys.joinToString("/")).append(": ")
            val libs = r.abis.values.flatten().distinct()
            append(libs.take(12).joinToString(", ")).append(if (libs.size > 12) " … (${libs.size})" else "").append('\n')
        } else {
            append("Librerías nativas: ninguna (funciona en cualquier procesador)\n")
        }
        append("Recursos: ").append(r.resourceFiles).append(" archivos")
        if (r.resources.isNotEmpty()) append(" (").append(r.resources.entries.take(6).joinToString(", ") { "${it.value} ${it.key}" }).append(')')
        append(", ").append(r.assets).append(" en assets")
        if (r.languages.isNotEmpty()) append(", ").append(r.languages.size).append(" idiomas (").append(r.languages.take(12).joinToString(", ")).append(if (r.languages.size > 12) "…" else "").append(')')
        append('\n')
        r.notes.forEach { append("Nota: ").append(it).append('\n') }
    }

    /** Todo, para guardarlo en un archivo: la IA o Maurizio pueden leerlo después entero. */
    fun fullText(r: Report): String = buildString {
        append(summary(r)).append("\n== Permisos ==\n")
        r.permissions.forEach { p -> append("- ").append(p); DANGEROUS[p]?.let { append(" — ").append(it) }; append('\n') }
        append("\n== Partes de la app ==\n")
        r.components.forEach { c ->
            append("- ").append(c.kind).append(' ').append(c.name)
            if (c.exported) append(" (abierta a otras apps)")
            c.permission?.let { append(" permiso ").append(it) }
            if (c.actions.isNotEmpty()) append(" acciones: ").append(c.actions.joinToString(", "))
            append('\n')
        }
        append("\n== Paquetes de código ==\n")
        r.topPackages.forEach { append("- ").append(it.first).append(": ").append(it.second).append(" clases\n") }
        if (r.hosts.isNotEmpty()) append("\n== Direcciones web ==\n").append(r.hosts.joinToString("\n")).append('\n')
        append("\n== Librerías nativas ==\n")
        r.abis.forEach { (abi, libs) -> append(abi).append(": ").append(libs.joinToString(", ")).append('\n') }
        append("\n== AndroidManifest.xml ==\n").append(r.manifestXml)
    }

    // --- Código ---

    /** Nombres de clases de la APK que encajan con [query] (nombre completo, nombre corto o parte). */
    fun findClasses(apk: File, query: String): List<String> {
        val all = classIndex(apk).keys
        val q = query.trim().removeSuffix(".java").removeSuffix(".class").replace('/', '.')
        if (q.isEmpty()) return emptyList()
        all.firstOrNull { it == q }?.let { return listOf(it) }
        val simple = all.filter { it.substringAfterLast('.').equals(q, ignoreCase = true) }
        if (simple.isNotEmpty()) return simple.sortedBy { it.length }
        return all.filter { it.contains(q, ignoreCase = true) && !it.contains('$') }.sortedBy { it.length }
    }

    /** Clases por dónde empezar a mirar: la app, la pantalla de inicio y las clases del paquete propio. */
    fun entryClasses(apk: File, report: Report): List<String> {
        val all = classIndex(apk).keys
        val own = all.filter { report.packageName.isNotEmpty() && it.startsWith(report.packageName + ".") && !it.contains('$') }
        return (listOfNotNull(report.appClass, report.launcher) + report.components.filter { it.kind == "activity" }.map { it.name } + own)
            .filter { it in all }.distinct()
    }

    /** Qué dex lleva cada clase. */
    private fun classIndex(apk: File): Map<String, String> {
        val key = apk.path + ":" + apk.length() + ":" + apk.lastModified()
        synchronized(indexCache) { indexCache[key]?.let { return it } }
        val map = LinkedHashMap<String, String>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) && it.size <= MAX_DEX_BYTES }
                .sortedBy { dexOrder(it.name) }
                .forEach { e -> Dex(zip.getInputStream(e).use { it.readBytes() }).classNames().forEach { map.putIfAbsent(it, e.name) } }
        }
        synchronized(indexCache) { indexCache.clear(); indexCache[key] = map }
        return map
    }

    private val indexCache = HashMap<String, Map<String, String>>()

    /**
     * El código Java (decompilado con jadx) de una clase. Solo se carga el dex que la lleva,
     * para no llenar la memoria del teléfono con apps grandes.
     */
    fun decompile(apk: File, className: String, work: File): String {
        val index = classIndex(apk)
        val top = className.substringBefore('$')
        val dexName = index[top] ?: index[className] ?: error("no encuentro la clase $className")
        work.mkdirs()
        val dexFile = File(work, "${apk.nameWithoutExtension.take(30)}-${apk.length()}-$dexName")
        if (!dexFile.exists()) {
            ZipFile(apk).use { zip -> zip.getInputStream(zip.getEntry(dexName)).use { input -> dexFile.outputStream().use { input.copyTo(it) } } }
        }
        return Decompiler.classCode(dexFile, top)
    }

    // --- Firma ---

    private fun signature(file: File, zip: ZipFile): Pair<String?, List<String>> {
        val schemes = mutableListOf<String>()
        var cert: X509Certificate? = null
        val v1 = zip.entries().asSequence().firstOrNull {
            val n = it.name.uppercase()
            n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
        }
        if (v1 != null) {
            schemes += "v1"
            cert = runCatching {
                CertificateFactory.getInstance("X.509").generateCertificates(zip.getInputStream(v1)).firstOrNull() as? X509Certificate
            }.getOrNull()
        }
        val block = signingBlock(file)
        if (block != null) {
            if (0x7109871a in block) schemes += "v2"
            if (0xf05368c0.toInt() in block || 0x1b93ad61 in block) schemes += "v3"
            if (cert == null) cert = (block[0xf05368c0.toInt()] ?: block[0x7109871a])?.let { runCatching { firstCertV2(it) }.getOrNull() }
        }
        val signer = cert?.let { c ->
            val subject = c.subjectX500Principal.name
            val cn = Regex("CN=([^,]+)").find(subject)?.groupValues?.get(1) ?: subject.take(60)
            val o = Regex("O=([^,]+)").find(subject)?.groupValues?.get(1)
            val sha = MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02X".format(it) }
            buildString {
                append(cn); o?.let { append(" (").append(it).append(')') }
                if (cn.contains("Android Debug", true)) append(", clave de pruebas")
                append(", SHA-256 ").append(sha.take(16)).append('…')
            }
        }
        return signer to schemes
    }

    /** Los bloques de la firma v2/v3 (por su número), que van justo antes del índice del zip. */
    private fun signingBlock(file: File): Map<Int, ByteArray>? = RandomAccessFile(file, "r").use { raf ->
        val len = raf.length()
        val tailLen = minOf(len, 65_557L).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(len - tailLen); raf.readFully(tail)
        val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = -1
        for (i in tailLen - 22 downTo 0) if (tb.getInt(i) == 0x06054b50) { eocd = i; break }
        if (eocd < 0) return null
        val cdOffset = tb.getInt(eocd + 16).toLong() and 0xffffffffL
        if (cdOffset < 32) return null
        val footer = ByteArray(24)
        raf.seek(cdOffset - 24); raf.readFully(footer)
        if (String(footer, 8, 16, Charsets.US_ASCII) != "APK Sig Block 42") return null
        val size = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
        if (size !in 24..(16L shl 20)) return null
        // Bloque: tamaño (8) + pares (id, valor) + tamaño (8) + "APK Sig Block 42" (16).
        val blockBytes = ByteArray(size.toInt() - 24)
        raf.seek(cdOffset - size); raf.readFully(blockBytes)
        val b = ByteBuffer.wrap(blockBytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = HashMap<Int, ByteArray>()
        var p = 0
        while (p + 12 <= blockBytes.size) {
            val pairLen = b.getLong(p)
            if (pairLen < 4 || p + 8 + pairLen > blockBytes.size) break
            val id = b.getInt(p + 8)
            out[id] = blockBytes.copyOfRange(p + 12, (p + 8 + pairLen).toInt())
            p += (8 + pairLen).toInt()
        }
        out
    }

    /** El primer certificado de una firma v2/v3: firmantes → datos firmados → certificados. */
    private fun firstCertV2(value: ByteArray): X509Certificate? {
        val b = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        fun slice(buf: ByteBuffer): ByteBuffer {
            val n = buf.int
            val s = buf.slice().order(ByteOrder.LITTLE_ENDIAN); s.limit(n)
            buf.position(buf.position() + n)
            return s
        }
        val signers = slice(b)
        val signer = slice(signers)
        val signedData = slice(signer)
        slice(signedData) // resúmenes
        val certs = slice(signedData)
        val cert = slice(certs)
        val der = ByteArray(cert.remaining()).also { cert.get(it) }
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
    }

    // --- Idiomas (resources.arsc) ---

    /** Los idiomas en los que viene traducida la app, mirando las configuraciones de resources.arsc. */
    fun locales(arsc: ByteArray): List<String> {
        val b = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN)
        if (arsc.size < 12 || (b.getShort(0).toInt() and 0xffff) != 0x0002) return emptyList()
        val found = sortedSetOf<String>()
        var pos = b.getShort(2).toInt() and 0xffff
        while (pos + 8 <= arsc.size) {
            val type = b.getShort(pos).toInt() and 0xffff
            val header = b.getShort(pos + 2).toInt() and 0xffff
            val size = b.getInt(pos + 4)
            if (size < 8 || pos + size > arsc.size) break
            if (type == 0x0200) {
                var p = pos + header
                val end = pos + size
                while (p + 8 <= end) {
                    val t = b.getShort(p).toInt() and 0xffff
                    val s = b.getInt(p + 4)
                    if (s < 8 || p + s > end) break
                    if (t == 0x0201 && p + 32 <= end) {
                        val l0 = b.get(p + 28).toInt() and 0xff
                        val l1 = b.get(p + 29).toInt() and 0xff
                        if (l0 in 'a'.code..'z'.code && l1 in 'a'.code..'z'.code) found += "${l0.toChar()}${l1.toChar()}"
                    }
                    p += s
                }
            }
            pos += size
        }
        return found.toList()
    }

    // --- Ayudas ---

    private fun dexOrder(name: String) = name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1

    private fun fullName(pkg: String, name: String) = when {
        name.startsWith(".") -> pkg + name
        !name.contains('.') && pkg.isNotEmpty() -> "$pkg.$name"
        else -> name
    }

    private fun packageGroup(cls: String): String {
        val parts = cls.split('.').dropLast(1)
        return if (parts.isEmpty()) "(sin paquete)" else parts.take(3).joinToString(".")
    }

    private fun short(permission: String) = permission.removePrefix("android.permission.")

    fun androidName(sdk: Int?): String {
        sdk ?: return "?"
        val v = when (sdk) {
            in 1..20 -> "4.x o menos"; 21, 22 -> "5"; 23 -> "6"; 24, 25 -> "7"; 26, 27 -> "8"; 28 -> "9"
            29 -> "10"; 30 -> "11"; 31, 32 -> "12"
            else -> (sdk - 20).toString()
        }
        return "Android $v (API $sdk)"
    }

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    }

    private val URL = Regex("https?://([A-Za-z0-9.-]+\\.[A-Za-z]{2,8})(?=[/:?#\\s\"']|$)")
    private val IGNORED_HOSTS = listOf(
        "schemas.android.com", "w3.org", "apache.org", "xml.org", "ns.adobe.com", "purl.org", "example.com",
        "github.com/square", "json-schema.org", "xmlpull.org", "localhost", "android.com", "googleapis.com/auth",
    )

    /** Permisos que dan acceso a cosas privadas, con lo que permiten en palabras sencillas. */
    val DANGEROUS = mapOf(
        "android.permission.READ_SMS" to "leer SMS", "android.permission.SEND_SMS" to "enviar SMS",
        "android.permission.RECEIVE_SMS" to "recibir SMS", "android.permission.READ_CONTACTS" to "leer contactos",
        "android.permission.WRITE_CONTACTS" to "cambiar contactos", "android.permission.READ_CALL_LOG" to "ver llamadas",
        "android.permission.CALL_PHONE" to "llamar", "android.permission.READ_PHONE_STATE" to "datos del teléfono",
        "android.permission.READ_PHONE_NUMBERS" to "tu número", "android.permission.CAMERA" to "cámara",
        "android.permission.RECORD_AUDIO" to "micrófono", "android.permission.ACCESS_FINE_LOCATION" to "ubicación exacta",
        "android.permission.ACCESS_COARSE_LOCATION" to "ubicación aproximada",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "ubicación en segundo plano",
        "android.permission.READ_CALENDAR" to "leer agenda", "android.permission.WRITE_CALENDAR" to "cambiar agenda",
        "android.permission.READ_EXTERNAL_STORAGE" to "leer archivos", "android.permission.WRITE_EXTERNAL_STORAGE" to "escribir archivos",
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "todos los archivos",
        "android.permission.READ_MEDIA_IMAGES" to "fotos", "android.permission.READ_MEDIA_VIDEO" to "vídeos",
        "android.permission.READ_MEDIA_AUDIO" to "audios", "android.permission.BODY_SENSORS" to "sensores del cuerpo",
        "android.permission.ACTIVITY_RECOGNITION" to "actividad física", "android.permission.SYSTEM_ALERT_WINDOW" to "dibujar sobre otras apps",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "instalar apps", "android.permission.QUERY_ALL_PACKAGES" to "ver todas tus apps",
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to "accesibilidad (leer y controlar la pantalla)",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" to "leer notificaciones",
        "android.permission.BIND_DEVICE_ADMIN" to "administrar el dispositivo", "android.permission.GET_ACCOUNTS" to "ver tus cuentas",
        "android.permission.BLUETOOTH_CONNECT" to "Bluetooth", "android.permission.BLUETOOTH_SCAN" to "buscar Bluetooth",
        "android.permission.NEARBY_WIFI_DEVICES" to "wifi cercanos", "android.permission.POST_NOTIFICATIONS" to "notificaciones",
        "android.permission.USE_BIOMETRIC" to "huella/cara", "android.permission.PROCESS_OUTGOING_CALLS" to "ver llamadas salientes",
        "android.permission.ANSWER_PHONE_CALLS" to "contestar llamadas", "android.permission.PACKAGE_USAGE_STATS" to "uso de las apps",
        "android.permission.WRITE_SETTINGS" to "cambiar ajustes", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" to "fotos elegidas",
    )

    /** Llamadas del código que dicen qué hace la app (prefijo de "clase.función"). */
    private val BEHAVIOURS = listOf(
        "android.telephony.SmsManager.send" to "envía SMS",
        "android.telephony.TelephonyManager.getDeviceId" to "lee el IMEI",
        "android.telephony.TelephonyManager.getImei" to "lee el IMEI",
        "android.telephony.TelephonyManager.getLine1Number" to "lee tu número de teléfono",
        "android.telephony.TelephonyManager.getSubscriberId" to "lee el identificador de la SIM",
        "android.provider.ContactsContract" to "lee contactos",
        "android.provider.Telephony\$Sms" to "lee SMS",
        "android.provider.CallLog" to "mira el registro de llamadas",
        "android.location.LocationManager.requestLocationUpdates" to "sigue tu ubicación",
        "android.location.LocationManager.getLastKnownLocation" to "mira tu ubicación",
        "com.google.android.gms.location.FusedLocationProviderClient" to "mira tu ubicación",
        "android.hardware.camera2.CameraManager.openCamera" to "abre la cámara",
        "android.hardware.Camera.open" to "abre la cámara",
        "androidx.camera.core" to "usa la cámara",
        "android.media.AudioRecord.startRecording" to "graba audio",
        "android.media.MediaRecorder.setAudioSource" to "graba audio",
        "android.media.projection.MediaProjectionManager" to "puede capturar la pantalla",
        "java.net.HttpURLConnection" to "se conecta a internet",
        "okhttp3.OkHttpClient" to "se conecta a internet",
        "java.net.Socket.<init>" to "abre conexiones de red directas",
        "javax.crypto.Cipher" to "cifra o descifra datos",
        "dalvik.system.DexClassLoader" to "carga código extra mientras funciona",
        "dalvik.system.InMemoryDexClassLoader" to "carga código desde la memoria",
        "java.lang.Runtime.exec" to "ejecuta órdenes del sistema",
        "java.lang.ProcessBuilder" to "ejecuta órdenes del sistema",
        "java.lang.System.loadLibrary" to "carga librerías nativas",
        "android.webkit.WebView.addJavascriptInterface" to "conecta páginas web con su código",
        "android.webkit.WebView.loadUrl" to "muestra páginas web dentro",
        "android.content.ClipboardManager.getPrimaryClip" to "lee el portapapeles",
        "android.app.admin.DevicePolicyManager" to "usa funciones de administración del dispositivo",
        "android.content.pm.PackageManager.getInstalledPackages" to "mira qué apps tienes instaladas",
        "android.content.pm.PackageManager.getInstalledApplications" to "mira qué apps tienes instaladas",
        "android.content.pm.PackageInstaller" to "puede instalar apps",
        "android.view.WindowManager.addView" to "pone ventanas encima de otras apps",
        "android.hardware.biometrics.BiometricPrompt" to "pide huella o cara",
        "androidx.biometric.BiometricPrompt" to "pide huella o cara",
        "android.bluetooth.BluetoothAdapter" to "usa Bluetooth",
        "android.nfc.NfcAdapter" to "usa NFC",
        "android.accounts.AccountManager.getAccounts" to "mira tus cuentas",
        "android.app.usage.UsageStatsManager" to "mira cuánto usas cada app",
        "android.speech.SpeechRecognizer" to "reconoce voz",
        "android.speech.tts.TextToSpeech" to "habla en voz alta",
        "androidx.work.WorkManager" to "programa tareas en segundo plano",
        "android.app.AlarmManager.setExact" to "programa alarmas exactas",
        "android.hardware.SensorManager.registerListener" to "lee sensores",
        "android.net.wifi.WifiManager.getScanResults" to "busca redes wifi",
        "com.android.billingclient" to "compras dentro de la app",
    )

    private val SUPERCLASSES = mapOf(
        "android.accessibilityservice.AccessibilityService" to "tiene servicio de accesibilidad (lee y controla la pantalla)",
        "android.service.notification.NotificationListenerService" to "lee tus notificaciones",
        "android.app.admin.DeviceAdminReceiver" to "puede ser administrador del dispositivo",
        "android.inputmethodservice.InputMethodService" to "es un teclado",
        "android.service.voice.VoiceInteractionService" to "puede ser asistente de voz",
        "android.net.VpnService" to "crea una VPN (ve el tráfico de red)",
    )

    /** Librerías conocidas por su paquete. */
    private val SDKS = listOf(
        "com.google.firebase.analytics" to "Firebase Analytics (estadísticas)",
        "com.google.firebase.crashlytics" to "Crashlytics (informes de fallos)",
        "com.google.firebase.messaging" to "Firebase Messaging (notificaciones push)",
        "com.google.firebase.auth" to "Firebase Auth (cuentas)",
        "com.google.firebase.firestore" to "Firestore (base de datos en la nube)",
        "com.google.firebase.database" to "Firebase Realtime Database",
        "com.google.firebase" to "Firebase",
        "com.google.android.gms.ads" to "AdMob (anuncios de Google)",
        "com.google.android.gms.maps" to "Google Maps",
        "com.google.android.gms.auth" to "inicio de sesión con Google",
        "com.google.android.gms" to "Google Play Services",
        "com.google.android.play.core" to "Play Core (actualizaciones/reseñas)",
        "com.facebook.ads" to "anuncios de Facebook",
        "com.facebook.react" to "React Native",
        "com.facebook" to "SDK de Facebook/Meta",
        "com.applovin" to "AppLovin (anuncios)", "com.unity3d.ads" to "Unity Ads (anuncios)",
        "com.ironsource" to "ironSource (anuncios)", "com.vungle" to "Vungle (anuncios)",
        "com.mbridge" to "Mintegral (anuncios)", "com.bytedance.sdk" to "Pangle (anuncios)",
        "com.chartboost" to "Chartboost (anuncios)", "com.inmobi" to "InMobi (anuncios)",
        "com.appsflyer" to "AppsFlyer (rastreo de marketing)", "com.adjust.sdk" to "Adjust (rastreo de marketing)",
        "com.onesignal" to "OneSignal (notificaciones)", "com.mixpanel" to "Mixpanel (estadísticas)",
        "com.amplitude" to "Amplitude (estadísticas)", "io.sentry" to "Sentry (informes de fallos)",
        "com.flurry" to "Flurry (estadísticas)", "com.yandex.metrica" to "AppMetrica (estadísticas)",
        "com.huawei.hms" to "servicios de Huawei",
        "okhttp3" to "OkHttp (internet)", "retrofit2" to "Retrofit (internet)", "io.ktor" to "Ktor (internet)",
        "com.squareup.picasso" to "Picasso (imágenes)", "com.bumptech.glide" to "Glide (imágenes)", "coil" to "Coil (imágenes)",
        "com.airbnb.lottie" to "Lottie (animaciones)", "io.reactivex" to "RxJava", "kotlinx.coroutines" to "corrutinas de Kotlin",
        "dagger" to "Dagger/Hilt", "com.google.gson" to "Gson (JSON)", "com.fasterxml.jackson" to "Jackson (JSON)",
        "com.squareup.moshi" to "Moshi (JSON)", "androidx.room" to "Room (base de datos)", "io.realm" to "Realm (base de datos)",
        "net.sqlcipher" to "SQLCipher (base de datos cifrada)", "androidx.media3" to "Media3 (vídeo/audio)",
        "com.google.android.exoplayer2" to "ExoPlayer (vídeo/audio)", "com.google.mlkit" to "ML Kit (visión/IA de Google)",
        "org.tensorflow.lite" to "TensorFlow Lite (IA)", "com.google.ai.edge" to "LiteRT (IA en el teléfono)",
        "org.pytorch" to "PyTorch (IA)", "ai.onnxruntime" to "ONNX Runtime (IA)", "com.k2fsa.sherpa" to "sherpa-onnx (voz)",
        "org.vosk" to "Vosk (voz)", "org.mozilla.javascript" to "Rhino (JavaScript)", "com.stripe" to "Stripe (pagos)",
        "com.paypal" to "PayPal (pagos)", "com.braintreepayments" to "Braintree (pagos)", "com.revenuecat" to "RevenueCat (suscripciones)",
        "com.android.billingclient" to "Google Play Billing (compras)", "org.webrtc" to "WebRTC (llamadas/vídeo)",
        "io.agora" to "Agora (llamadas/vídeo)", "com.mapbox" to "Mapbox (mapas)", "org.osmdroid" to "OpenStreetMap",
        "com.google.zxing" to "ZXing (códigos QR)", "com.journeyapps" to "ZXing (códigos QR)",
        "org.bouncycastle" to "BouncyCastle (criptografía)", "org.apache.commons.compress" to "Commons Compress (comprimidos)",
        "com.tom_roush.pdfbox" to "PDFBox (PDF)", "jadx" to "jadx (decompilador)", "net.lingala.zip4j" to "zip4j (zip)",
        "io.flutter" to "Flutter", "com.unity3d.player" to "Unity", "org.apache.cordova" to "Cordova", "com.getcapacitor" to "Capacitor",
        "com.tencent" to "SDK de Tencent", "com.alibaba" to "SDK de Alibaba", "com.baidu" to "SDK de Baidu",
    )

    private val LIB_PREFIXES = listOf(
        "android.", "androidx.", "kotlin.", "kotlinx.", "java.", "javax.", "com.google.", "org.jetbrains.", "org.intellij.",
        "com.android.", "dalvik.", "org.json.", "org.xmlpull.", "org.apache.", "org.slf4j.", "_COROUTINE.", "j$.",
        "com.squareup.", "org.chromium.", "io.grpc.", "com.sun.", "org.w3c.", "org.xml.", "org.mozilla.", "org.tukaani.",
        "org.checkerframework.", "org.reactivestreams.", "javax.inject.", "io.perfmark.", "org.codehaus.", "com.ctc.",
        "org.objectweb.", "org.conscrypt.", "org.bouncycastle.", "net.jpountz.", "com.github.luben.", "org.brotli.",
    )
}
