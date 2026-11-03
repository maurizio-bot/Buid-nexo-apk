package com.ultrayo.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

/**
 * Que Ultra vea: lo que hay en tu pantalla (en cualquier app) y lo que enfoca la cámara.
 * La pantalla se mira con el control del teléfono (texto de la pantalla y una captura que solo
 * ve el modelo dentro del teléfono); la cámara, con una foto que haces tú. Nada sale del teléfono.
 */
object Vista {

    enum class Pedido {
        /** "Qué es esto", "qué ves en mi pantalla": mirar la imagen de la pantalla. */
        PANTALLA,

        /** "Resume lo que estoy leyendo", "explícame esto", "tradúceme esto": leer el texto de la pantalla. */
        LEER,

        /** "Qué es esto que enfoco", "mira con la cámara": hacer una foto. */
        CAMARA,

        /** "Qué es esto" a secas: la pantalla si hay otra app delante; si estás en Ultra, la cámara. */
        ESTO,
    }

    private val camara = Regex(
        "(?:con|por) (?:la )?c[aá]mara|usa la c[aá]mara|qu[eé] (?:es|ves en) (?:esto|lo) que (?:enfoco|tengo delante|estoy enfocando|te ense[nñ]o)|" +
            "qu[eé] tengo delante|m[ií]ra(?:me)? esto que te ense[nñ]o|hazle una foto y dime",
        RegexOption.IGNORE_CASE,
    )
    private val leer = Regex(
        "^(?:res[uú]me(?:me)?|haz(?:me)? un resumen de|expl[ií]ca(?:me)?|trad[uú]ce(?:me)?|qu[eé] (?:quiere decir|significa)) " +
            "(?:lo que estoy leyendo|lo que (?:sale|hay|pone) en (?:la |mi )?pantalla|(?:la |mi |esta )?pantalla|esta p[aá]gina|este art[ií]culo|este mensaje|esto|eso|lo que estoy viendo)$|" +
            "^qu[eé] (?:pone|dice) aqu[ií]$",
        RegexOption.IGNORE_CASE,
    )
    private val pantalla = Regex(
        "^(?:qu[eé] (?:ves|hay|sale) en (?:la |mi )?pantalla|mira (?:la |mi )?pantalla|qu[eé] estoy viendo|mira esto|qu[eé] (?:te )?parece esto|" +
            "qu[eé] app es esta|d[oó]nde estoy)$",
        RegexOption.IGNORE_CASE,
    )
    private val esto = Regex("^(?:qu[eé] es esto|qu[eé] es eso|y esto qu[eé] es|esto qu[eé] es)$", RegexOption.IGNORE_CASE)

    /** Si [said] pide mirar algo, qué hay que mirar; si no, null. */
    fun pedido(said: String): Pedido? {
        val t = QuickActions.clean(said)
        return when {
            camara.containsMatchIn(t) -> Pedido.CAMARA
            leer.containsMatchIn(t) -> Pedido.LEER
            pantalla.matches(t) -> Pedido.PANTALLA
            esto.matches(t) -> Pedido.ESTO
            else -> null
        }
    }

    /** Abre la cámara; al hacer la foto se la pasa a Ultra con [pregunta]. */
    fun abrirCamara(context: Context, pregunta: String) {
        context.startActivity(
            Intent(context, CameraActivity::class.java)
                .putExtra(CameraActivity.EXTRA_QUESTION, pregunta)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Hace una foto con la app de cámara del teléfono y se la enseña a Ultra con la pregunta. */
class CameraActivity : ComponentActivity() {

    private lateinit var file: File

    private val take = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val question = intent.getStringExtra(EXTRA_QUESTION)?.ifBlank { null } ?: "¿Qué es esto?"
        if (ok && file.exists() && file.length() > 0) {
            Assistant.send(question, Attachment("foto de la cámara", imagePath = file.path))
        } else {
            Assistant.say("No has hecho la foto. Cuando quieras, dime «qué es esto» otra vez.")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Assistant.init(this)
        val dir = File(filesDir, "camara").apply { mkdirs() }
        // Solo se guardan las últimas fotos: son para mirarlas, no para quedárselas.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        file = savedInstanceState?.getString(KEY_FILE)?.let(::File) ?: File(dir, "foto-${System.currentTimeMillis()}.jpg")
        if (savedInstanceState == null) {
            try {
                take.launch(FileProvider.getUriForFile(this, "$packageName.archivos", file))
            } catch (e: Exception) {
                ErrorLog.add("No pude abrir la cámara", e)
                Assistant.say("No pude abrir la cámara: ${Safe.describe(e)}")
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_FILE, file.path)
    }

    companion object {
        const val EXTRA_QUESTION = "pregunta"
        private const val KEY_FILE = "archivo"
    }
}
