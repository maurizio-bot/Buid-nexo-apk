package com.ultrayo.app.apk

import jadx.api.CommentsLevel
import jadx.api.DecompilationMode
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.loader.JadxPluginLoader
import jadx.plugins.input.dex.DexInputPlugin
import java.io.File

/**
 * Decompila código de Android (dex) a Java legible con jadx, dentro del teléfono. Se le pasa
 * solo el dex que lleva la clase pedida y se usa un solo hilo, para no gastar mucha memoria.
 */
object Decompiler {

    /** Las líneas "import" no explican nada y gastan sitio en la conversación: se resumen en una. */
    fun withoutImports(code: String): String {
        val lines = code.lines()
        val imports = lines.count { it.startsWith("import ") }
        // @Metadata(...) de Kotlin es un bloque ilegible de datos internos: no explica nada.
        val rest = lines.filterNot { it.startsWith("import ") || it.trimStart().startsWith("@Metadata(") }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n")
        return if (imports == 0) rest else rest.replaceFirst("\n", "\n// ($imports imports)\n")
    }

    fun classCode(dex: File, className: String): String {
        val args = JadxArgs().apply {
            setInputFile(dex)
            threadsCount = 1
            isSkipResources = true
            isShowInconsistentCode = true
            isDeobfuscationOn = false
            commentsLevel = CommentsLevel.WARN
            decompilationMode = DecompilationMode.AUTO
            // Sin buscar complementos con ServiceLoader: solo el lector de dex.
            pluginLoader = object : JadxPluginLoader {
                override fun load(): List<JadxPlugin> = listOf(DexInputPlugin())
                override fun close() = Unit
            }
        }
        JadxDecompiler(args).use { jadx ->
            jadx.load()
            val cls = jadx.searchJavaClassByOrigFullName(className)
                ?: jadx.searchJavaClassOrItsParentByOrigFullName(className)
                ?: error("jadx no encuentra la clase $className")
            return withoutImports(cls.code)
        }
    }
}
