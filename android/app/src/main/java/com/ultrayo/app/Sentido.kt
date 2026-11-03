package com.ultrayo.app

import android.content.Context
import org.json.JSONObject
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import kotlin.math.sqrt

/**
 * El "sentido" de las frases: convierte un texto en un vector de 256 números de modo que frases
 * que significan lo mismo con otras palabras ("el coche" / "el vehículo", "mi perro" / "mi can")
 * queden cerca. Es lo que deja a las fichas y a los recuerdos entender sinónimos.
 *
 * Modelo: static-similarity-mrl-multilingual-v1 (sentence-transformers, Apache-2.0), 50 idiomas.
 * Es una tabla de vectores por trozo de palabra (sin red neuronal que ejecutar): tarda
 * microsegundos, no usa librerías nativas y va dentro del APK (unos 28 MB). Es menos fino que
 * un modelo grande como EmbeddingGemma, pero ese exige registrarse en Hugging Face para bajarlo.
 */
class SentidoModel(
    private val vocab: Map<String, Int>,
    private val scales: FloatArray,
    private val data: ByteArray,
    val dims: Int,
    private val lowercase: Boolean = true,
    private val stripAccents: Boolean = true,
    private val prefix: String = "##",
    private val unk: String = "[UNK]",
    private val maxChars: Int = 100,
) {
    private val unkId = vocab[unk] ?: -1

    /** Los trozos de palabra de [text], como el tokenizador de BERT (WordPiece). */
    fun tokens(text: String): List<Int> {
        var t = text
        if (lowercase) t = t.lowercase()
        if (stripAccents) t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val ids = ArrayList<Int>()
        for (word in split(t)) {
            if (word.length > maxChars) { if (unkId >= 0) ids += unkId; continue }
            var start = 0
            val pieces = ArrayList<Int>()
            var bad = false
            while (start < word.length) {
                var end = word.length
                var found = -1
                while (start < end) {
                    val sub = (if (start > 0) prefix else "") + word.substring(start, end)
                    val id = vocab[sub]
                    if (id != null) { found = id; break }
                    end--
                }
                if (found < 0) { bad = true; break }
                pieces += found
                start = end
            }
            if (bad) { if (unkId >= 0) ids += unkId } else ids += pieces
        }
        return ids
    }

    private fun split(t: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) } }
        for (ch in t) {
            when {
                ch.isWhitespace() || ch.isISOControl() -> flush()
                isPunct(ch) || isCjk(ch) -> { flush(); out += ch.toString() }
                else -> cur.append(ch)
            }
        }
        flush()
        return out
    }

    private fun isPunct(c: Char): Boolean {
        val code = c.code
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0xF900..0xFAFF

    /** El vector de [text], de largo 1 (o todo ceros si no hay ninguna palabra conocida). */
    fun embed(text: String): FloatArray {
        val v = FloatArray(dims)
        var n = 0
        for (id in tokens(text)) {
            if (id == unkId || id < 0 || id >= scales.size) continue
            val s = scales[id]
            val base = id * dims
            for (d in 0 until dims) v[d] += data[base + d] * s
            n++
        }
        if (n == 0) return v
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) for (d in 0 until dims) v[d] /= norm
        return v
    }

    companion object {
        /** Lee el modelo que prepara scripts/modelo-sentido.sh. */
        fun read(vectors: InputStream, vocabLines: List<String>, config: JSONObject?): SentidoModel {
            val input = DataInputStream(vectors.buffered(1 shl 16))
            val magic = ByteArray(4).also { input.readFully(it) }
            require(String(magic) == "SNT1") { "formato de sentido desconocido" }
            val header = ByteArray(8).also { input.readFully(it) }
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val rows = hb.int
            val dims = hb.int
            val scaleBytes = ByteArray(rows * 4).also { input.readFully(it) }
            val scales = FloatArray(rows)
            ByteBuffer.wrap(scaleBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(scales)
            val data = ByteArray(rows * dims).also { input.readFully(it) }
            val vocab = HashMap<String, Int>(vocabLines.size * 2)
            vocabLines.forEachIndexed { i, t -> if (t.isNotEmpty() && i < rows) vocab.putIfAbsent(t, i) }
            return SentidoModel(
                vocab, scales, data, dims,
                lowercase = config?.optBoolean("lowercase", true) ?: true,
                stripAccents = config?.optBoolean("strip_accents", true) ?: true,
                prefix = config?.optString("prefix", "##") ?: "##",
                unk = config?.optString("unk", "[UNK]") ?: "[UNK]",
                maxChars = config?.optInt("max_chars", 100) ?: 100,
            )
        }

        fun cosine(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size) return 0.0
            var dot = 0.0
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }
    }
}

/** El modelo de sentido de la app: se carga una vez en segundo plano y se usa si está listo. */
object Sentido {

    @Volatile
    var model: SentidoModel? = null
        private set

    @Volatile
    private var started = false

    val ready get() = model != null

    /** Empieza a cargarlo (unos 28 MB) sin bloquear. Si el APK no lo trae, simplemente no hay sinónimos. */
    fun preload(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Thread {
            model = Safe.run("No pude cargar el sentido de las palabras") {
                val assets = app.assets
                val vocab = assets.open("sentido/vocab.txt").bufferedReader().readLines()
                val config = runCatching { JSONObject(assets.open("sentido/sentido.json").bufferedReader().readText()) }.getOrNull()
                assets.open("sentido/vectores.bin").use { SentidoModel.read(it, vocab, config) }
            }
        }.apply { name = "carga-sentido"; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Vector de [text], o null si el modelo aún no está. */
    fun embed(text: String): FloatArray? = model?.embed(text)

    /** Parecido de significado entre dos textos (de -1 a 1), o null si el modelo aún no está. */
    fun similarity(a: String, b: String): Double? {
        val m = model ?: return null
        return SentidoModel.cosine(m.embed(a), m.embed(b))
    }

    /** Para las pruebas: usar un modelo ya cargado. */
    fun use(m: SentidoModel?) {
        model = m
        started = m != null
    }
}

/**
 * Umbrales del sentido, fijados mirando los parecidos reales del modelo (ver SentidoTest.calibracion
 * en el registro de GitHub Actions). Si Ultra saca fichas que no vienen al caso, subir FICHA.
 */
object SentidoUmbrales {
    // Medido en GitHub Actions con el modelo real: frases relacionadas con otras palabras dan 0,30-0,82
    // ("médico/doctor" 0,82, "la tienda online/Nexo: app de ventas por internet" 0,44, "beber/café" 0,30)
    // y frases sin relación dan entre -0,08 y 0,09.

    /** Parecido mínimo para pensar que una frase habla de una ficha sin nombrarla. */
    const val FICHA = 0.28

    /** Y que la segunda ficha quede claramente por detrás (si no, mejor no adivinar). */
    const val FICHA_MARGEN = 0.08

    /** "¿Recuerdas…?" contestado al instante por sentido: más exigente, porque no pasa por el modelo. */
    const val FICHA_DIRECTA = 0.33

    /** Por debajo de este parecido, dos frases no tienen nada que ver (sirve de cero al mezclar). */
    const val SUELO = 0.0

    /** Mezcla el parecido por letras (trigramas) con el de sentido: se queda con el mejor de los dos. */
    fun mezcla(letras: Double, sentido: Double?): Double =
        if (sentido == null) letras else maxOf(letras, ((sentido - SUELO) / (1 - SUELO)).coerceAtLeast(0.0))
}
