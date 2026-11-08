package com.ultrayo.app

/**
 * La "puerta" del micrófono mientras Ultra habla.
 *
 * Mientras suena su voz, el oído no recibe lo que capta el micrófono (recibe silencio), así que no puede
 * transcribir su propio eco. La puerta solo se abre cuando el micrófono oye bastante más fuerte de lo que
 * sonaría el eco de lo que ella está diciendo: es decir, otra voz de fuera, como la de Maurizio.
 *
 * Cómo sabe cuánto suena el eco: compara, cada 50 ms, el volumen del micrófono ([feed] mic) con el de lo
 * que está sonando por el altavoz (ref) y va aprendiendo la proporción normal ([ratio]). Si de golpe el
 * micrófono suena [FACTOR] veces más de lo esperado durante 150 ms seguidos, hay alguien más hablando.
 * Sin Android: se prueba en el PC (EchoGateTest).
 */
class EchoGate {

    /** Cuánto del volumen del altavoz llega al micrófono como eco (se aprende). */
    var ratio = START_RATIO
        private set

    private var chunks = 0
    private var loud = 0

    /** Mete un trozo de 50 ms: [mic] y [ref] son volúmenes (RMS de 0 a 1). Devuelve true si hay que abrir. */
    fun feed(mic: Float, ref: Float): Boolean {
        chunks++
        val expected = ratio * ref
        val learning = chunks <= LEARN_CHUNKS
        val threshold = maxOf(ABS_MIN, (if (learning) LEARN_FACTOR else FACTOR) * expected)
        if (mic > threshold) {
            loud++
            if (loud >= NEEDED_CHUNKS) {
                loud = 0
                return true
            }
            return false
        }
        loud = 0
        // Lo normal: es eco o silencio. Se ajusta la proporción (más deprisa al principio).
        if (ref > REF_MIN) {
            val r = (mic / ref).coerceIn(MIN_RATIO, MAX_RATIO)
            val alpha = if (learning) 0.25f else 0.06f
            ratio = ratio * (1 - alpha) + r * alpha
        }
        return false
    }

    /** Tras abrir y ver que no era nadie: vuelve a cerrar sin olvidar lo aprendido. */
    fun close() {
        loud = 0
    }

    companion object {
        /** Al empezar se supone un eco fuerte (mejor no abrir por error). */
        const val START_RATIO = 0.5f
        const val MIN_RATIO = 0.01f
        const val MAX_RATIO = 3f

        /** Cuántas veces más fuerte que el eco esperado tiene que sonar otra voz. */
        const val FACTOR = 3f
        const val LEARN_FACTOR = 6f
        const val LEARN_CHUNKS = 10
        const val NEEDED_CHUNKS = 3

        /** Por debajo de esto es silencio de la habitación, sea lo que sea. */
        const val ABS_MIN = 0.015f
        const val REF_MIN = 0.003f

        fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
            if (to <= from) return 0f
            var sum = 0.0
            for (i in from until to) sum += samples[i] * samples[i]
            return kotlin.math.sqrt(sum / (to - from)).toFloat()
        }
    }
}
