package com.ultrayo.app

import android.graphics.Bitmap

/** Lo que Maurizio pide hacer con imágenes, por voz o por escrito. */
sealed interface ImageCommand {
    /** "Crea una imagen de un dragón en la luna". */
    data class Create(val description: String) : ImageCommand

    /** "Conviértela en estilo anime": la IA vuelve a pintar la imagen siguiendo la orden. */
    data class AiEdit(val instruction: String) : ImageCommand

    /** Un retoque sin IA: filtro, girar, recortar, texto… [done] es lo que se le dice al terminar. */
    class Basic(val done: String, val apply: (Bitmap) -> Bitmap) : ImageCommand

    data object OpenEditor : ImageCommand
}

/** Reconoce las órdenes de imágenes. Las que cambian una imagen solo valen si hay una a mano. */
object ImageCommands {

    private const val NOUNS =
        "im[aá]gen(?:es)?|foto(?:graf[ií]a)?|dibujo|ilustraci[oó]n|pintura|cuadro|retrato|logo(?:tipo)?|p[oó]ster|fondo de pantalla|wallpaper|icono"

    private val create = listOf(
        Regex(
            "^(?:por favor,?\\s+)?(?:cr[eé]a(?:me)?|genera(?:me)?|haz(?:me)?|h[aá]game|dib[uú]ja(?:me)?|pinta(?:me)?|imagina(?:te)?|quiero|necesito)\\s+" +
                "(?:una?\\s+)?(?:nuev[oa]\\s+)?(?:$NOUNS)(?:\\s+(?:de|del|con|sobre|en la que (?:salga|aparezca)|que (?:sea|muestre|tenga)))?[,:]?\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        ),
        Regex("^(?:dib[uú]ja(?:me)?|pinta(?:me)?)\\s+(.+)$", RegexOption.IGNORE_CASE),
    )

    /** Empieza con un verbo de orden: así "¿qué es el blanco y negro?" no cambia ninguna foto. */
    private val imperative = Regex(
        "^(?:p[oó]n(?:la|le|lo)?|haz(?:la|lo)?|d[eé]ja(?:la|lo)|c[aá]mbia(?:la|le|lo)?|convi[eé]rte(?:la|lo)?|p[aá]sa(?:la|lo)|" +
            "aplica(?:le)?|dale|quiero|edita(?:la|lo)?|la quiero|tr[aá]nsforma(?:la|lo)?)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val filters = listOf(
        Regex("blanco y negro|escala de grises|gris(es)?\\b", RegexOption.IGNORE_CASE) to Filter.BlackWhite,
        Regex("\\bsepia\\b|antigua|vintage", RegexOption.IGNORE_CASE) to Filter.Sepia,
        Regex("m[aá]s viv[oa]s?|colores vivos|m[aá]s color|saturad", RegexOption.IGNORE_CASE) to Filter.Vivid,
        Regex("c[aá]lid[oa]", RegexOption.IGNORE_CASE) to Filter.Warm,
        Regex("fr[ií][oa]\\b", RegexOption.IGNORE_CASE) to Filter.Cold,
        Regex("negativo|invierte los colores|colores invertidos", RegexOption.IGNORE_CASE) to Filter.Invert,
    )

    private val brighter = Regex("m[aá]s (clara|brillo|luminosa|luz)|acl[aá]ra(la)?", RegexOption.IGNORE_CASE)
    private val darker = Regex("m[aá]s oscura|oscur[eé]ce(la)?|menos brillo", RegexOption.IGNORE_CASE)
    private val rotate = Regex("^(g[ií]ra(la|lo)?|r[oó]ta(la|lo)?)\\b", RegexOption.IGNORE_CASE)
    private val flip = Regex("^(voltea(la|lo)?|dale la vuelta|refl[eé]ja(la|lo)|en espejo|ponla en espejo)", RegexOption.IGNORE_CASE)
    private val crop = Regex("^rec[oó]rta(la|lo)?\\b|^(d[eé]jala|hazla|ponla) cuadrada", RegexOption.IGNORE_CASE)
    private val text = Regex(
        "^(?:p[oó]n(?:le)?|a[ñn][aá]de(?:le)?|agr[eé]ga(?:le)?|escr[ií]be(?:le)?)\\s+(?:el\\s+|un\\s+)?(?:texto|t[ií]tulo|frase|letrero|rótulo)\\s*(?:que (?:diga|ponga)\\s*)?[:,]?\\s*[\"“'«]?(.+?)[\"”'»]?$",
        RegexOption.IGNORE_CASE,
    )
    private val editor = Regex("^(abre el editor|ab[rR]e(la)? en el editor|quiero editarla|ed[ií]tala)$", RegexOption.IGNORE_CASE)
    private val aiEdit = Regex(
        "^(?:edita(?:la)?|c[aá]mbia(?:la|le)?|modif[ií]ca(?:la)?|tr[aá]nsforma(?:la)?|convi[eé]rte(?:la)?|ret[oó]ca(?:la)?|" +
            "haz(?:la|le)?|p[oó]n(?:le|la)|qu[ií]ta(?:le)?|a[ñn][aá]de(?:le)?|que parezca|dale un estilo|ahora)\\b[,:]?\\s*(.+)$",
        RegexOption.IGNORE_CASE,
    )

    /** [hasImage]: si hay una foto adjunta o una imagen reciente en el chat a la que aplicar los cambios. */
    fun parse(input: String, hasImage: Boolean): ImageCommand? {
        val t = input.trim()
            .replace(Regex("^ultra( yo)?[,:]?\\s+", RegexOption.IGNORE_CASE), "")
            .trimEnd('.', '!', '?', ' ')
        for (pattern in create) {
            pattern.find(t)?.let { return ImageCommand.Create(it.groupValues[1].trim()) }
        }
        if (!hasImage) return null

        if (editor.matches(t)) return ImageCommand.OpenEditor
        text.find(t)?.let { m ->
            val words = m.groupValues[1].trim()
            return ImageCommand.Basic("He puesto el texto \"$words\".") {
                ImageOps.drawText(it, words, 0.5f, 0.86f, 0.09f, android.graphics.Color.WHITE)
            }
        }
        if (rotate.containsMatchIn(t)) {
            val left = t.contains("izquierda", ignoreCase = true)
            return ImageCommand.Basic("La he girado.") { ImageOps.rotate(it, if (left) -90f else 90f) }
        }
        if (flip.containsMatchIn(t)) return ImageCommand.Basic("La he puesto en espejo.") { ImageOps.flip(it) }
        if (crop.containsMatchIn(t)) return ImageCommand.Basic("La he recortado en cuadrado.") { ImageOps.cropSquare(it) }

        val short = t.split(Regex("\\s+")).size <= 4
        if (imperative.containsMatchIn(t) || short) {
            for ((pattern, filter) in filters) {
                if (pattern.containsMatchIn(t)) {
                    return ImageCommand.Basic("Listo, con el filtro ${filter.label}.") { ImageOps.filter(it, filter) }
                }
            }
            if (brighter.containsMatchIn(t)) {
                return ImageCommand.Basic("La he aclarado.") { ImageOps.applyMatrix(it, ImageOps.adjustments(35f, 1f, 1f)) }
            }
            if (darker.containsMatchIn(t)) {
                return ImageCommand.Basic("La he oscurecido.") { ImageOps.applyMatrix(it, ImageOps.adjustments(-35f, 1f, 1f)) }
            }
        }
        aiEdit.find(t)?.let { m ->
            // Que nombre la imagen o hable de ella ("-la"), para no confundirlo con otra petición.
            val aboutImage = Regex("im[aá]gen|foto|dibujo|(la|le)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t.substringBefore(' ')) ||
                Regex("im[aá]gen|foto|dibujo|estilo|que parezca|fondo|cielo|color", RegexOption.IGNORE_CASE).containsMatchIn(t)
            if (aboutImage) return ImageCommand.AiEdit(m.value.trim())
        }
        return null
    }
}
