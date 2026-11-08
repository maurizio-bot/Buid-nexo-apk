package com.ultrayo.app.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lee lo justo de un archivo .dex (el código compilado de una app Android) para entenderlo sin
 * decompilar: qué clases trae, a qué funciones de Android o de otras librerías llama y qué textos
 * lleva dentro (direcciones web, por ejemplo). Solo lectura y sin Android.
 */
class Dex(private val bytes: ByteArray) {

    private val buf: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    init {
        require(bytes.size >= 0x70 && String(bytes, 0, 3, Charsets.US_ASCII) == "dex") { "no es un archivo dex" }
    }

    private val stringCount = buf.getInt(0x38)
    private val stringOff = buf.getInt(0x3C)
    private val typeCount = buf.getInt(0x40)
    private val typeOff = buf.getInt(0x44)
    private val methodCount = buf.getInt(0x58)
    private val methodOff = buf.getInt(0x5C)
    private val classCount = buf.getInt(0x60)
    private val classOff = buf.getInt(0x64)

    val version: String get() = String(bytes, 4, 3, Charsets.US_ASCII)
    val classDefs: Int get() = classCount
    val methodRefs: Int get() = methodCount

    private val stringCache = HashMap<Int, String>()

    fun string(index: Int): String {
        if (index !in 0 until stringCount) return ""
        return stringCache.getOrPut(index) {
            var off = buf.getInt(stringOff + index * 4)
            // Largo en letras (uleb128) y después MUTF-8 terminado en 0.
            while (bytes[off].toInt() and 0x80 != 0) off++
            off++
            decodeMutf8(off)
        }
    }

    private fun decodeMutf8(start: Int): String {
        val sb = StringBuilder()
        var i = start
        while (i < bytes.size) {
            val a = bytes[i].toInt() and 0xff
            if (a == 0) break
            when {
                a < 0x80 -> { sb.append(a.toChar()); i++ }
                a and 0xe0 == 0xc0 && i + 1 < bytes.size -> {
                    sb.append((((a and 0x1f) shl 6) or (bytes[i + 1].toInt() and 0x3f)).toChar()); i += 2
                }
                a and 0xf0 == 0xe0 && i + 2 < bytes.size -> {
                    sb.append((((a and 0x0f) shl 12) or ((bytes[i + 1].toInt() and 0x3f) shl 6) or (bytes[i + 2].toInt() and 0x3f)).toChar()); i += 3
                }
                else -> { sb.append('?'); i++ }
            }
        }
        return sb.toString()
    }

    /** "Lcom/ejemplo/Clase;" de un tipo. */
    fun typeDescriptor(index: Int): String =
        if (index !in 0 until typeCount) "" else string(buf.getInt(typeOff + index * 4))

    /** Nombres normales (com.ejemplo.Clase) de las clases que define este dex. */
    fun classNames(): List<String> = List(classCount) { javaName(typeDescriptor(buf.getInt(classOff + it * 32))) }

    /** Las clase superiores de cada clase definida: nombre -> padre. */
    fun superClasses(): Map<String, String> = (0 until classCount).associate {
        val base = classOff + it * 32
        javaName(typeDescriptor(buf.getInt(base))) to javaName(typeDescriptor(buf.getInt(base + 8)))
    }

    /** Cada función a la que llama el código, como "android.telephony.SmsManager.sendTextMessage". */
    fun methodReferences(): Sequence<String> = (0 until methodCount).asSequence().map {
        val base = methodOff + it * 8
        val cls = javaName(typeDescriptor(buf.getShort(base).toInt() and 0xffff))
        "$cls.${string(buf.getInt(base + 4))}"
    }

    /** Lo que usa el código de unas clases concretas: funciones a las que llama y textos que escribe. */
    class CodeUse(val methods: Set<String>, val strings: Set<String>)

    /**
     * Recorre las instrucciones de las clases que cumplen [include] (por ejemplo, solo las de la
     * propia app y no las de sus librerías) y apunta a qué funciones llaman y qué textos usan.
     */
    fun codeUse(include: (String) -> Boolean): CodeUse {
        val methodIdx = HashSet<Int>()
        val stringIdx = HashSet<Int>()
        for (c in 0 until classCount) {
            val base = classOff + c * 32
            if (!include(javaName(typeDescriptor(buf.getInt(base))))) continue
            val dataOff = buf.getInt(base + 24)
            if (dataOff <= 0 || dataOff >= bytes.size) continue
            runCatching { scanClassData(dataOff, methodIdx, stringIdx) }
        }
        val methods = methodIdx.mapTo(HashSet()) { i ->
            val b = methodOff + i * 8
            javaName(typeDescriptor(buf.getShort(b).toInt() and 0xffff)) + "." + string(buf.getInt(b + 4))
        }
        return CodeUse(methods, stringIdx.mapTo(HashSet()) { string(it) })
    }

    private fun scanClassData(start: Int, methods: MutableSet<Int>, strings: MutableSet<Int>) {
        var p = start
        fun uleb(): Int {
            var result = 0; var shift = 0
            while (true) {
                val b = bytes[p++].toInt() and 0xff
                result = result or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        val staticFields = uleb(); val instanceFields = uleb(); val direct = uleb(); val virtual = uleb()
        repeat((staticFields + instanceFields) * 2) { uleb() }
        repeat(direct + virtual) {
            uleb(); uleb()
            val codeOff = uleb()
            if (codeOff > 0 && codeOff + 16 < bytes.size) scanCode(codeOff, methods, strings)
        }
    }

    private fun scanCode(codeOff: Int, methods: MutableSet<Int>, strings: MutableSet<Int>) {
        val units = buf.getInt(codeOff + 12)
        val start = codeOff + 16
        if (units <= 0 || start + units * 2L > bytes.size) return
        var i = 0
        while (i < units) {
            val unit = buf.getShort(start + i * 2).toInt() and 0xffff
            val op = unit and 0xff
            if (op == 0x00 && unit != 0) {
                // Datos de switch o de arrays metidos entre las instrucciones: se saltan enteros.
                val size = buf.getShort(start + (i + 1) * 2).toInt() and 0xffff
                i += when (unit) {
                    0x0100 -> size * 2 + 4
                    0x0200 -> size * 4 + 2
                    0x0300 -> (buf.getInt(start + (i + 2) * 2).toLong() * size + 1).toInt() / 2 + 4
                    else -> 1
                }
                continue
            }
            when (op) {
                in 0x6e..0x72, in 0x74..0x78 -> if (i + 1 < units) methods += buf.getShort(start + (i + 1) * 2).toInt() and 0xffff
                0x1a -> if (i + 1 < units) strings += buf.getShort(start + (i + 1) * 2).toInt() and 0xffff
                0x1b -> if (i + 2 < units) strings += buf.getInt(start + (i + 1) * 2)
            }
            i += WIDTH[op]
        }
    }

    /** Todos los textos del dex (nombres, mensajes, direcciones…). */
    fun strings(): Sequence<String> = (0 until stringCount).asSequence().map { string(it) }

    companion object {
        /** Cuántas unidades de 16 bits ocupa cada instrucción de Dalvik, por su código. */
        private val WIDTH = IntArray(256) { 1 }.also { w ->
            fun set(range: IntRange, n: Int) = range.forEach { w[it] = n }
            set(0x02..0x02, 2); set(0x03..0x03, 3); set(0x05..0x05, 2); set(0x06..0x06, 3); set(0x08..0x08, 2); set(0x09..0x09, 3)
            set(0x13..0x13, 2); set(0x14..0x14, 3); set(0x15..0x16, 2); set(0x17..0x17, 3); set(0x18..0x18, 5); set(0x19..0x1a, 2)
            set(0x1b..0x1b, 3); set(0x1c..0x1c, 2); set(0x1f..0x20, 2); set(0x22..0x23, 2); set(0x24..0x26, 3); set(0x29..0x29, 2)
            set(0x2a..0x2c, 3); set(0x2d..0x3d, 2); set(0x44..0x6d, 2); set(0x6e..0x72, 3); set(0x74..0x78, 3); set(0x90..0xaf, 2)
            set(0xd0..0xe2, 2); set(0xfa..0xfb, 4); set(0xfc..0xfd, 3); set(0xfe..0xff, 2)
        }

        fun javaName(descriptor: String): String {
            var d = descriptor
            var dims = 0
            while (d.startsWith("[")) { d = d.substring(1); dims++ }
            val base = when {
                d.startsWith("L") && d.endsWith(";") -> d.substring(1, d.length - 1).replace('/', '.')
                d == "V" -> "void"; d == "Z" -> "boolean"; d == "B" -> "byte"; d == "S" -> "short"; d == "C" -> "char"
                d == "I" -> "int"; d == "J" -> "long"; d == "F" -> "float"; d == "D" -> "double"
                else -> d
            }
            return base + "[]".repeat(dims)
        }
    }
}
