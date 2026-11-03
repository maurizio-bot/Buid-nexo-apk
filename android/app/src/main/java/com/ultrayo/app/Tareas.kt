package com.ultrayo.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Un paso de una tarea de varios pasos. [texto] es como lo dijo Maurizio. */
sealed class Paso {
    abstract val texto: String

    /** Buscar algo en internet y quedarse con la respuesta concreta (que pasa al paso siguiente). */
    data class Buscar(val consulta: String, override val texto: String) : Paso()

    /** Mandar lo que salió del paso anterior a alguien ("mándaselo a Juan por WhatsApp"). */
    data class Enviar(val quien: String, val sms: Boolean, override val texto: String) : Paso()

    /** Una orden directa del teléfono (abrir una app, pulsar, escribir, alarma…). */
    data class Orden(override val texto: String) : Paso()

    /** Algo que hay que hacer mirando la pantalla: lo hace el modelo con sus herramientas. */
    data class Libre(override val texto: String) : Paso()
}

/**
 * Tareas de varios pasos entre apps: "busca el vuelo más barato a Madrid y mándaselo a Juan por WhatsApp".
 * Ultra parte la frase en pasos, enseña el plan y espera el "sí" de Maurizio. Luego lo hace paso a paso,
 * mirando la pantalla con el control del teléfono. Lo que envía, paga o borra vuelve a pedir el "sí"
 * con el contenido exacto, aunque el plan ya estuviera aprobado. "Para" o PARAR lo corta en cualquier paso.
 */
object Tareas {

    /** Verbos con los que empieza un paso nuevo (sirven para partir "… y mándaselo a Juan"). */
    private const val VERBOS =
        "busca|b[uú]scame|mira|m[ií]rame|consulta|averigua|abre|[aá]breme|entra|ve a|vete a|m[aá]nda|env[ií]a|escr[ií]be|" +
            "p[aá]sa|comp[aá]rte|pulsa|toca|dale a|pon|ll[aá]ma|lee|l[eé]e|resume|res[uú]me|copia|guarda|gu[aá]rda|apunta|ap[uú]nta|" +
            "baja|sube|desliza|selecciona|elige|haz|reproduce|ll[eé]vame|d[ií]le|d[ií]me|cierra|vuelve|comparte"

    private val separador = Regex(
        "\\s*(?:,\\s*)?(?:\\by (?:luego|despu[eé]s|entonces|a continuaci[oó]n)\\b,?|\\b(?:luego|despu[eé]s|y)\\b,?|;)\\s+(?=(?:$VERBOS)\\S*(?:\\s|$))",
        RegexOption.IGNORE_CASE,
    )

    private val buscar = Regex(
        "^(?:busca(?:me)?|b[uú]scame|mira(?:me)?|m[ií]rame|consulta|averigua)(?: en (?:internet|google|la web|la red))?(?: (?:cu[aá]l es|qu[eé]|si|c[oó]mo|d[oó]nde))? (.+?)(?: en (?:internet|google|la web))?$",
        RegexOption.IGNORE_CASE,
    )

    /** "mándaselo a Juan por WhatsApp", "envíale el resultado a mamá", "pásaselo a Pedro por SMS". */
    private val enviar = Regex(
        "^(?:m[aá]nda|env[ií]a|p[aá]sa|comp[aá]rte)(?:selo|sela|selos|selas|le|les|lo|la)?" +
            "(?: (?:eso|esto|el resultado|lo que encuentres|lo que hayas encontrado|la respuesta|el enlace|la informaci[oó]n))?" +
            "(?: por (whatsapp|wasap|guasap|sms|mensaje))? (?:a|al) (.+?)(?: por (whatsapp|wasap|guasap|sms|mensaje))?$",
        RegexOption.IGNORE_CASE,
    )

    /** Pasos que suelen ser órdenes directas (si no lo son, los hace el modelo mirando la pantalla). */
    private val orden = Regex(
        "^(?:abre|[aá]breme|entra en|ve a|vete a|pulsa|toca|dale a|selecciona|escribe|teclea|baja|sube|desliza|lee(?:me)? la pantalla|" +
            "pon (?:una alarma|un temporizador|la alarma|m[uú]sica|el volumen)|ll[aá]ma|ll[eé]vame|reproduce|enciende|apaga|atr[aá]s|vuelve atr[aá]s|" +
            "m[aá]nd\\S* (?:un )?(?:whatsapp|wasap|sms|mensaje)|haz (?:una )?captura)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Si [said] es una tarea de dos o más pasos, devuelve los pasos; si es una orden sola, null
     * (entonces sigue el camino de siempre).
     */
    fun plan(said: String): List<Paso>? {
        val text = QuickActions.clean(said)
        if (text.length < 8) return null
        val partes = text.split(separador).map { it.trim().trimEnd(',', '.').trim() }.filter { it.isNotEmpty() }
        if (partes.size < 2) return null
        val pasos = partes.mapIndexed { i, parte -> clasificar(parte, hayAnterior = i > 0) }
        // Una frase como "llama a Juan y pon una alarma" son dos órdenes sueltas: también vale como plan.
        return pasos
    }

    fun clasificar(parte: String, hayAnterior: Boolean): Paso {
        val t = parte.trim()
        if (hayAnterior && !t.contains(" que ") && !t.contains(":")) {
            enviar.find(t)?.let { m ->
                val quien = m.groupValues[2].trim()
                val via = (m.groupValues[1] + m.groupValues[3]).lowercase()
                if (quien.isNotEmpty() && quien.split(" ").size <= 4) return Paso.Enviar(quien, sms = via.contains("sms"), texto = t)
            }
        }
        // "busca X en google" a secas lo abre en Google (orden directa); en un plan se lee la web.
        buscar.find(t)?.let { m ->
            val q = m.groupValues[1].trim()
            if (q.length > 2 && !Regex("^(?:la pantalla|en la pantalla)").containsMatchIn(q)) return Paso.Buscar(q, t)
        }
        if (orden.containsMatchIn(t)) return Paso.Orden(t)
        return Paso.Libre(t)
    }

    /** Cómo se le enseña a Maurizio cada paso del plan. */
    fun describir(paso: Paso): String = when (paso) {
        is Paso.Buscar -> "Buscar en internet «${paso.consulta}» y quedarme con la respuesta"
        is Paso.Enviar -> "Mandárselo a ${paso.quien} por ${if (paso.sms) "SMS" else "WhatsApp"} (antes de enviar te enseño el texto)"
        is Paso.Orden -> paso.texto.replaceFirstChar { it.uppercase() }
        is Paso.Libre -> paso.texto.replaceFirstChar { it.uppercase() } + " (mirando la pantalla)"
    }

    fun textoDelPlan(pasos: List<Paso>): String =
        "Plan de ${pasos.size} pasos:\n" + pasos.mapIndexed { i, p -> "${i + 1}. ${describir(p)}" }.joinToString("\n")

    /** Enseña el plan y lo deja esperando el "sí". Devuelve qué decirle. Se puede llamar desde cualquier hilo. */
    fun proponer(context: Context, pasos: List<Paso>): String {
        val app = context.applicationContext
        return Control.ask(textoDelPlan(pasos)) {
            ejecutar(app, pasos)
            "Empiezo. Di «para» o pulsa PARAR si quieres cortarlo."
        }
    }

    /** El plan que propone el modelo: un paso por línea (o separados por "|"). */
    fun desdeModelo(pasos: String): List<Paso>? {
        val lineas = pasos.split('\n', '|')
            .map { it.trim().replace(Regex("^(?:\\d+[.)-]|[-•*])\\s*"), "").trim() }
            .filter { it.isNotEmpty() }
        if (lineas.isEmpty()) return null
        return lineas.mapIndexed { i, l -> clasificar(QuickActions.clean(l), hayAnterior = i > 0) }
    }

    // --- Hacerlo ---

    /** Hay un plan en marcha. */
    @Volatile
    var enMarcha = false
        private set

    private val scope by lazy { Safe.mainScope("tareas") { main { terminar() } } }

    private fun main(block: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(block)

    private fun terminar() {
        enMarcha = false
        if (Assistant.status.startsWith("Paso ")) Assistant.status = ""
    }

    /** Hace los pasos desde [desde]. [previo] es lo que salió del paso anterior (por ejemplo, lo encontrado). */
    fun ejecutar(context: Context, pasos: List<Paso>, desde: Int = 0, previo: String? = null) {
        val app = context.applicationContext
        enMarcha = true
        scope.launch {
            var resultado = previo
            for (i in desde until pasos.size) {
                val paso = pasos[i]
                if (Control.stopped) {
                    terminar()
                    Assistant.say("Parado en el paso ${i + 1}. No sigo.")
                    return@launch
                }
                Assistant.status = "Paso ${i + 1} de ${pasos.size}: ${paso.texto}"
                Control.log("Plan, paso ${i + 1}: ${paso.texto}")
                val antes = UltraAccessibility.instance?.currentPackage()
                val dicho = try {
                    hacer(app, paso, resultado, i, pasos.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLog.add("Fallo en el paso ${i + 1} del plan (${paso.texto})", e)
                    terminar()
                    Assistant.say("No pude hacer el paso ${i + 1} (${paso.texto}): ${Safe.describe(e)}. Paro aquí.")
                    return@launch
                }
                if (Control.pending != null) {
                    // Este paso espera el sí de Maurizio (enviar, llamar, pagar, borrar…): el resto, después.
                    val siguiente = i + 1
                    val r = resultado
                    Control.thenAfter {
                        if (siguiente < pasos.size) ejecutar(app, pasos, siguiente, r) else terminar()
                    }
                    Assistant.status = ""
                    Assistant.sayAndListen(dicho)
                    enMarcha = false
                    return@launch
                }
                when (paso) {
                    is Paso.Buscar -> {
                        resultado = dicho
                        Assistant.say(dicho)
                    }
                    is Paso.Libre -> {
                        resultado = dicho
                        Assistant.say(dicho)
                    }
                    else -> if (i == pasos.lastIndex) Assistant.say(dicho)
                }
                if (i < pasos.lastIndex) esperarPantalla(antes)
            }
            terminar()
            if (pasos.last() !is Paso.Orden) Assistant.status = "Plan terminado."
        }
    }

    private suspend fun hacer(app: Context, paso: Paso, resultado: String?, i: Int, total: Int): String = when (paso) {
        is Paso.Buscar -> buscarYResumir(paso.consulta)
        is Paso.Enviar -> {
            val cuerpo = resultado?.trim()?.take(700)
            if (cuerpo.isNullOrEmpty()) {
                "No tengo nada que mandarle a ${paso.quien}: el paso anterior no dio resultado."
            } else {
                val orden = "mándale un ${if (paso.sms) "sms" else "whatsapp"} a ${paso.quien} que $cuerpo"
                QuickActions.handle(app, orden) ?: "No supe cómo mandárselo a ${paso.quien}."
            }
        }
        is Paso.Orden -> QuickActions.handle(app, paso.texto) ?: conElModelo(app, paso.texto, resultado, i, total)
        is Paso.Libre -> conElModelo(app, paso.texto, resultado, i, total)
    }

    /** Busca, lee las mejores páginas y saca la respuesta concreta en una o dos frases, con la web de donde sale. */
    private suspend fun buscarYResumir(consulta: String): String {
        val paginas = withContext(Dispatchers.IO) { WebSearch.readTop(consulta, pages = 3) }
        if (paginas.isBlank()) return "No encontré nada en internet sobre «$consulta» (¿hay conexión?)."
        val brain = Ai.brain ?: return "Lo primero que encuentro sobre «$consulta»: " + paginas.lines().take(3).joinToString(" ").take(400)
        val resumen = withContext(Ai.thread) {
            Assistant.brainDirty = true
            brain.answerOnce(
                "Eres Ultra Yo. Te paso lo que dicen unas páginas web. Contesta en español, en una o dos frases, " +
                    "con el dato concreto que se busca (precio, fecha, nombre, enlace) y di de qué web sale. " +
                    "No inventes nada que no esté en el texto. Si no hay un dato claro, dilo así.",
                "Búsqueda: $consulta\n\n$paginas",
            )
        }
        return Thinking.strip(resumen).ifBlank { "No saqué nada claro de la búsqueda «$consulta»." }
    }

    /** Un paso que hay que hacer mirando la pantalla: el modelo lo hace con sus herramientas (pulsar, escribir…). */
    private suspend fun conElModelo(app: Context, paso: String, resultado: String?, i: Int, total: Int): String {
        val brain = Ai.brain ?: return "Para el paso «$paso» necesito el cerebro cargado y aún no lo está."
        val pantalla = UltraAccessibility.instance?.readScreen(maxChars = 1800)
            ?: "(No veo la pantalla: el control del teléfono no está activado en Ajustes.)"
        val prompt = buildString {
            append("Estás haciendo el paso ${i + 1} de $total de un plan que Maurizio ya ha aprobado: «$paso».\n")
            if (!resultado.isNullOrBlank()) append("Lo que salió del paso anterior: $resultado\n")
            append("Lo que hay ahora en la pantalla:\n$pantalla\n\n")
            append("Hazlo tú con tus herramientas (leerPantalla, pulsar, escribirEnPantalla, desplazar, botonSistema, accionTelefono). ")
            append("Si algo envía, paga o borra, la app le pedirá el sí a Maurizio: no lo esquives. ")
            append("Al terminar di en una sola frase lo que has hecho, o lo que no has podido hacer y por qué.")
        }
        val systemPrompt = Assistant.memory.systemPrompt()
        val dicho = withContext(Ai.thread) {
            Assistant.brainDirty = true
            brain.reset(systemPrompt, emptyList())
            brain.ask(prompt)
        }
        return Thinking.strip(dicho).ifBlank { "Hecho el paso «$paso»." }
    }

    /** Tras abrir o pulsar algo, espera a que la pantalla cambie (hasta unos segundos) y a que se asiente. */
    private suspend fun esperarPantalla(antes: String?) {
        val robot = UltraAccessibility.instance ?: return delay(1200)
        val hasta = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < hasta && !Control.stopped) {
            val ahora = robot.currentPackage()
            if (ahora != null && ahora != antes) break
            delay(200)
        }
        delay(700)
    }
}
