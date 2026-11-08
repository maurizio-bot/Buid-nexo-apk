package com.ultrayo.app.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lee el XML binario de Android (el AndroidManifest.xml que va dentro de una APK está
 * compilado, no es texto). Devuelve un árbol de elementos y sabe escribirlo como XML normal.
 * Solo lectura y sin Android: funciona igual en el teléfono y en las pruebas del ordenador.
 */
object Axml {

    class Element(val name: String, val attrs: Map<String, String>, val children: MutableList<Element> = mutableListOf()) {
        fun attr(name: String): String? = attrs[name] ?: attrs["android:$name"]
        fun all(name: String): List<Element> = children.filter { it.name == name }
        fun first(name: String): Element? = children.firstOrNull { it.name == name }

        /** Todos los descendientes con ese nombre, a cualquier profundidad. */
        fun deep(name: String): List<Element> = children.flatMap { (if (it.name == name) listOf(it) else emptyList()) + it.deep(name) }
    }

    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML = 0x0003
    private const val XML_START_NAMESPACE = 0x0100
    private const val XML_END_NAMESPACE = 0x0101
    private const val XML_START_ELEMENT = 0x0102
    private const val XML_END_ELEMENT = 0x0103
    private const val XML_RESOURCE_MAP = 0x0180
    private const val UTF8_FLAG = 0x100
    private const val MAX_NODES = 200_000

    /** Atributos de Android por su número, para las APK que borran los nombres (ofuscadas). */
    private val ANDROID_ATTRS = mapOf(
        0x01010001 to "label", 0x01010002 to "icon", 0x01010003 to "name", 0x01010006 to "permission",
        0x0101000e to "enabled", 0x0101000f to "debuggable", 0x01010010 to "exported", 0x01010011 to "process",
        0x01010018 to "authorities", 0x0101020c to "minSdkVersion", 0x0101021b to "versionCode",
        0x0101021c to "versionName", 0x01010270 to "targetSdkVersion", 0x01010271 to "maxSdkVersion",
        0x01010280 to "allowBackup", 0x0101028e to "required", 0x010104ec to "usesCleartextTraffic",
        0x01010527 to "networkSecurityConfig", 0x01010024 to "value", 0x01010025 to "resource",
        0x01010026 to "mimeType", 0x01010027 to "scheme", 0x01010028 to "host", 0x0101002a to "path",
        0x0101002b to "pathPrefix", 0x0101002c to "pathPattern",
    )

    fun parse(bytes: ByteArray): Element {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 8 && (buf.getShort(0).toInt() and 0xffff) == RES_XML) { "no es un XML binario de Android" }
        var strings: List<String> = emptyList()
        var resMap = IntArray(0)
        val prefixes = HashMap<String, String>() // uri -> prefijo
        val root = Element("#documento", emptyMap())
        val stack = ArrayDeque<Element>().apply { addLast(root) }
        var pos = buf.getShort(2).toInt() and 0xffff
        var nodes = 0
        while (pos + 8 <= bytes.size) {
            val type = buf.getShort(pos).toInt() and 0xffff
            val headerSize = buf.getShort(pos + 2).toInt() and 0xffff
            val size = buf.getInt(pos + 4)
            if (size < 8 || pos + size > bytes.size) break
            if (++nodes > MAX_NODES) break
            when (type) {
                RES_STRING_POOL -> strings = stringPool(buf, pos)
                XML_RESOURCE_MAP -> resMap = IntArray((size - headerSize) / 4) { buf.getInt(pos + headerSize + it * 4) }
                XML_START_NAMESPACE -> {
                    val prefix = str(strings, buf.getInt(pos + 16))
                    val uri = str(strings, buf.getInt(pos + 20))
                    if (uri.isNotEmpty()) prefixes[uri] = prefix
                }
                XML_START_ELEMENT -> {
                    val ext = pos + headerSize
                    val name = str(strings, buf.getInt(ext + 4))
                    val attrStart = buf.getShort(ext + 8).toInt() and 0xffff
                    val attrSize = (buf.getShort(ext + 10).toInt() and 0xffff).coerceAtLeast(20)
                    val attrCount = buf.getShort(ext + 12).toInt() and 0xffff
                    val attrs = LinkedHashMap<String, String>()
                    for (i in 0 until attrCount) {
                        val a = ext + attrStart + i * attrSize
                        if (a + 20 > pos + size) break
                        val nsIdx = buf.getInt(a)
                        val nameIdx = buf.getInt(a + 4)
                        var attrName = str(strings, nameIdx)
                        if (attrName.isEmpty() && nameIdx in resMap.indices) {
                            attrName = ANDROID_ATTRS[resMap[nameIdx]] ?: "attr_0x%08x".format(resMap[nameIdx])
                        }
                        val ns = if (nsIdx >= 0) str(strings, nsIdx) else ""
                        val prefix = prefixes[ns] ?: if (ns.endsWith("/android")) "android" else ""
                        val key = if (prefix.isNotEmpty()) "$prefix:$attrName" else attrName
                        attrs[key] = value(strings, buf.getInt(a + 8), buf.get(a + 15).toInt() and 0xff, buf.getInt(a + 16))
                    }
                    val element = Element(name, attrs)
                    stack.last().children += element
                    stack.addLast(element)
                }
                XML_END_ELEMENT -> if (stack.size > 1) stack.removeLast()
                XML_END_NAMESPACE -> Unit
            }
            pos += size
        }
        return root.children.firstOrNull() ?: error("el XML está vacío")
    }

    private fun value(strings: List<String>, raw: Int, type: Int, data: Int): String = when (type) {
        0x03 -> str(strings, if (raw >= 0) raw else data)
        0x01 -> "@0x%08x".format(data)
        0x02 -> "?0x%08x".format(data)
        0x04 -> java.lang.Float.intBitsToFloat(data).toString()
        0x10 -> data.toString()
        0x11 -> "0x%x".format(data)
        0x12 -> if (data != 0) "true" else "false"
        in 0x1c..0x1f -> "#%08x".format(data)
        0x00 -> if (raw >= 0) str(strings, raw) else ""
        else -> if (raw >= 0) str(strings, raw) else data.toString()
    }

    private fun str(strings: List<String>, index: Int): String = strings.getOrNull(index) ?: ""

    /** El almacén de textos de un recurso de Android (UTF-8 o UTF-16). */
    fun stringPool(buf: ByteBuffer, start: Int): List<String> {
        val headerSize = buf.getShort(start + 2).toInt() and 0xffff
        val count = buf.getInt(start + 8)
        val flags = buf.getInt(start + 16)
        val stringsStart = buf.getInt(start + 20)
        val utf8 = flags and UTF8_FLAG != 0
        if (count < 0 || count > 2_000_000) return emptyList()
        return List(count) { i ->
            runCatching {
                val off = start + stringsStart + buf.getInt(start + headerSize + i * 4)
                if (utf8) readUtf8(buf, off) else readUtf16(buf, off)
            }.getOrDefault("")
        }
    }

    private fun readUtf8(buf: ByteBuffer, start: Int): String {
        var off = start
        // Primero el largo en letras (lo saltamos) y luego en bytes; cada uno ocupa 1 o 2 bytes.
        off += if (buf.get(off).toInt() and 0x80 != 0) 2 else 1
        var len = buf.get(off).toInt() and 0xff
        if (len and 0x80 != 0) {
            len = ((len and 0x7f) shl 8) or (buf.get(off + 1).toInt() and 0xff)
            off += 2
        } else {
            off += 1
        }
        val bytes = ByteArray(len)
        for (k in 0 until len) bytes[k] = buf.get(off + k)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf16(buf: ByteBuffer, start: Int): String {
        var off = start
        var len = buf.getShort(off).toInt() and 0xffff
        if (len and 0x8000 != 0) {
            len = ((len and 0x7fff) shl 16) or (buf.getShort(off + 2).toInt() and 0xffff)
            off += 4
        } else {
            off += 2
        }
        val chars = CharArray(len) { buf.getChar(off + it * 2) }
        return String(chars)
    }

    /** El árbol escrito como XML de texto, para guardarlo o enseñárselo a la IA. */
    fun toXml(element: Element, indent: String = ""): String = buildString {
        append(indent).append('<').append(element.name)
        element.attrs.forEach { (k, v) -> append(' ').append(k).append("=\"").append(escape(v)).append('"') }
        if (element.children.isEmpty()) {
            append(" />\n")
        } else {
            append(">\n")
            element.children.forEach { append(toXml(it, "$indent    ")) }
            append(indent).append("</").append(element.name).append(">\n")
        }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
