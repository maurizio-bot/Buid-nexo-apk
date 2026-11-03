package com.ultrayo.app

/**
 * Entender "eso", "el de antes", "mándaselo", "llámale" o "ábrelo" sin repetir el nombre:
 * Ultra recuerda a quién nombraste, qué app abrió y qué te contestó la última vez, y reescribe
 * la orden completa ("mándaselo a Juan" → "mándale un whatsapp a Juan que <lo último que dijo>").
 * Todo es rápido y sin el modelo; si no encaja, la frase sigue su camino normal.
 */
object Referencias {

    /** El último contacto con el que se habló (llamar, mensaje…). */
    @Volatile
    var ultimoContacto: String? = null

    /** La última app que abrió. */
    @Volatile
    var ultimaApp: String? = null

    /** Qué hacer con una frase que se refiere a algo anterior. */
    sealed class Resuelto {
        /** Una orden ya completa, para hacerla como si la hubiera dicho entera. */
        data class Orden(val texto: String) : Resuelto()

        /** Repetir lo último que dijo. */
        object Repetir : Resuelto()

        /** Copiar lo último que dijo al portapapeles. */
        object Copiar : Resuelto()

        /** Abrir el enlace de la última respuesta. */
        data class AbrirEnlace(val url: String) : Resuelto()
    }

    private val mandarEso = Regex(
        "^(?:m[aá]nda|env[ií]a|p[aá]sa|comp[aá]rte|reenv[ií]a)(?:selo|sela|selos|selas|le|les|lo|la)?" +
            "(?: (?:eso|esto|lo de antes|lo que me has dicho|lo que has dicho|el resultado|la respuesta|el enlace|la informaci[oó]n))?" +
            "(?: por (whatsapp|wasap|guasap|sms|mensaje))?(?: (?:a|al) (.+?))?(?: por (whatsapp|wasap|guasap|sms|mensaje))?$",
        RegexOption.IGNORE_CASE,
    )
    private val llamarle = Regex("^(?:ll[aá]ma(?:le|la|lo)|ll[aá]male otra vez|vuelve a llamar(?:le|la)?|ll[aá]mal[eao] de nuevo)$", RegexOption.IGNORE_CASE)
    private val escribirleQue = Regex(
        "^(?:m[aá]ndale|env[ií]ale|escr[ií]bele|d[ií]le)(?: un)?(?: (whatsapp|wasap|guasap|mensaje|sms))?(?:,|:| que| diciendo)\\s+(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val abrirlo = Regex("^(?:[aá]brelo|[aá]brela|[aá]breme eso|abre eso|abre el enlace|abre ese enlace|[aá]bremelo)$", RegexOption.IGNORE_CASE)
    private val repetir = Regex(
        "^(?:rep[ií]te(?:lo|melo)?|rep[ií]temelo|qu[eé] has dicho|c[oó]mo has dicho|no te he o[ií]do|otra vez|dilo otra vez|perd[oó]n,? qu[eé])$",
        RegexOption.IGNORE_CASE,
    )
    private val copiar = Regex("^(?:c[oó]pia(?:lo|la|melo)?|copia eso|c[oó]pialo al portapapeles)$", RegexOption.IGNORE_CASE)
    private val apuntarlo = Regex("^(?:ap[uú]nta(?:lo|la)|gu[aá]rda(?:lo|la)) en (.+)$", RegexOption.IGNORE_CASE)
    private val enlace = Regex("https?://[^\\s)\\]»\"]+")

    private val despedida = Regex(
        "^(?:(?:vale,? |muy bien,? |perfecto,? |ok,? )?(?:muchas )?gracias(?: ultra)?|nada m[aá]s|eso es todo|ya est[aá]|ya no necesito nada|" +
            "hasta luego|adi[oó]s|chao|nada,? gracias|no,? gracias|listo|termina|terminamos|hemos terminado)$",
        RegexOption.IGNORE_CASE,
    )

    /** "gracias", "nada más", "eso es todo": se acaba la conversación por voz (no vuelve a escuchar). */
    fun esDespedida(said: String): Boolean = despedida.matches(QuickActions.clean(said))

    /**
     * Si [said] se refiere a algo anterior, devuelve qué hacer; si no, null.
     * [ultimaRespuesta] es lo último que dijo Ultra en el chat.
     */
    fun resolver(said: String, ultimaRespuesta: String?): Resuelto? {
        val t = QuickActions.clean(said)
        if (t.isEmpty()) return null
        val respuesta = ultimaRespuesta?.trim()?.takeIf { it.isNotEmpty() }
        if (repetir.matches(t)) return if (respuesta != null) Resuelto.Repetir else null
        if (copiar.matches(t)) return if (respuesta != null) Resuelto.Copiar else null
        if (abrirlo.matches(t)) {
            respuesta?.let { r -> enlace.find(r)?.value?.trimEnd('.', ',', ';')?.let { return Resuelto.AbrirEnlace(it) } }
            ultimaApp?.let { return Resuelto.Orden("abre $it") }
            return null
        }
        if (llamarle.matches(t)) return ultimoContacto?.let { Resuelto.Orden("llama a $it") }
        apuntarlo.find(t)?.let { m ->
            if (respuesta == null) return null
            return Resuelto.Orden("apunta en ${m.groupValues[1].trim()}: ${corto(respuesta)}")
        }
        // "mándale un whatsapp que llego tarde" sin decir a quién: al de antes.
        escribirleQue.find(t)?.let { m ->
            val quien = ultimoContacto ?: return null
            val tipo = m.groupValues[1].ifEmpty { "whatsapp" }
            return Resuelto.Orden("mándale un $tipo a $quien que ${m.groupValues[2].trim()}")
        }
        // "mándaselo a Juan", "envíale eso", "pásaselo a mamá por SMS": lo último que dijo Ultra.
        if (!t.contains(" que ") && !t.contains(":")) {
            mandarEso.find(t)?.let { m ->
                // Tiene que llevar un pronombre o "eso": "manda" a secas no se refiere a nada.
                val refiere = Regex("^\\S*(?:selo|sela|selos|selas)\\b|\\b(?:eso|esto|lo de antes|el resultado|la respuesta|el enlace|la informaci[oó]n)\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(t)
                if (!refiere || respuesta == null) return null
                val quien = m.groupValues[2].trim().ifEmpty { ultimoContacto ?: return null }
                if (quien.split(" ").size > 4) return null
                val tipo = (m.groupValues[1] + m.groupValues[3]).lowercase().let { if (it.contains("sms")) "sms" else "whatsapp" }
                return Resuelto.Orden("mándale un $tipo a $quien que ${corto(respuesta)}")
            }
        }
        return null
    }

    /** Lo que se manda o se apunta: sin emojis de adorno y no demasiado largo. */
    private fun corto(text: String): String =
        text.replace(Regex("^(?:🎓|📎|🎙)\\s*"), "").replace(Regex("\\s+"), " ").trim().take(700)
}
