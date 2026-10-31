package com.ultrayo.app

import android.app.KeyguardManager
import android.content.Context
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context as JsContext
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TIME_LIMIT_MS = 5_000L
private const val MAX_OUTPUT = 4000

/** Una habilidad programada: código JavaScript con una función ejecutar(entrada) que devuelve texto. */
data class CodeSkill(val description: String, val code: String)

/** Lo que se le enseña a la IA como ejemplo cuando tiene que escribir una habilidad. */
const val CODE_SKILL_TEMPLATE = """// Recibe lo que pide Maurizio como texto y devuelve la respuesta como texto.
function ejecutar(entrada) {
  var gastos = ultra.buscarDatos("", "gastos");
  var total = 0;
  for (var i = 0; i < gastos.length; i++) {
    var n = parseFloat(gastos[i].replace(/^.*\] /, "").replace(",", "."));
    if (!isNaN(n)) total += n;
  }
  return "Llevas gastado " + total.toFixed(2) + " euros.";
}"""

/**
 * El motor que ejecuta las habilidades programadas. Es JavaScript interpretado dentro de la app
 * (Rhino), encerrado: no puede tocar Android ni Java, solo lo que le da el objeto `ultra`
 * (datos guardados, archivos en modo lectura, fecha). Si tarda más de 5 segundos se corta.
 */
object SkillEngine {

    private class LimitedContext(factory: ContextFactory) : JsContext(factory) {
        var started = 0L
    }

    private val factory = object : ContextFactory() {
        override fun makeContext(): JsContext = LimitedContext(this).apply {
            optimizationLevel = -1 // Android no puede cargar clases generadas: hay que interpretar.
            languageVersion = JsContext.VERSION_ES6
            instructionObserverThreshold = 10_000
            setClassShutter(ClassShutter { false }) // Nada de Java desde los scripts.
        }

        override fun observeInstructionCount(cx: JsContext, instructionCount: Int) {
            val started = (cx as LimitedContext).started
            if (System.currentTimeMillis() - started > TIME_LIMIT_MS) {
                throw Error("la habilidad tardó demasiado (más de ${TIME_LIMIT_MS / 1000} s) y la he parado")
            }
        }
    }

    /** Comprueba que el código se puede ejecutar. Devuelve el problema o null si está bien. Ejecuta nada. */
    fun check(code: String): String? {
        if (!Regex("function\\s+ejecutar\\s*\\(").containsMatchIn(code)) return "falta la función ejecutar(entrada)"
        val cx = factory.enterContext()
        return try {
            cx.compileString(code, "habilidad", 1, null)
            null
        } catch (e: EvaluatorException) {
            "error en la línea ${e.lineNumber()}: ${e.details()}"
        } finally {
            JsContext.exit()
        }
    }

    /** Ejecuta la habilidad con [input] y devuelve lo que responde, o el error. */
    fun run(context: Context, code: String, input: String): String {
        val cx = factory.enterContext() as LimitedContext
        return try {
            cx.started = System.currentTimeMillis()
            val scope = cx.initSafeStandardObjects()
            ScriptableObject.putProperty(scope, "ultra", bridge(context.applicationContext, cx, scope))
            cx.evaluateString(scope, code, "habilidad", 1, null)
            val main = ScriptableObject.getProperty(scope, "ejecutar") as? Function
                ?: return "La habilidad no tiene la función ejecutar(entrada)."
            val result = main.call(cx, scope, scope, arrayOf(input))
            val text = if (result == null || result is Undefined) "(la habilidad no devolvió nada)" else JsContext.toString(result)
            text.take(MAX_OUTPUT)
        } catch (e: RhinoException) {
            "La habilidad falló en la línea ${e.lineNumber()}: ${e.details()}"
        } catch (e: Throwable) {
            "La habilidad falló: ${e.message}"
        } finally {
            JsContext.exit()
        }
    }

    /** El objeto `ultra` que ven los scripts. Todo lo que hace es leer, salvo guardarDato. */
    private fun bridge(context: Context, cx: JsContext, scope: Scriptable): Scriptable {
        val ultra = cx.newObject(scope)
        fun define(name: String, body: (List<String>) -> Any) {
            ScriptableObject.putProperty(ultra, name, object : BaseFunction() {
                override fun call(cx: JsContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val strings = args.map { if (it == null || it is Undefined) "" else JsContext.toString(it) }
                    val result = body(strings)
                    return if (result is List<*>) cx.newArray(scope, result.map { it.toString() }.toTypedArray<Any>()) else result
                }
            })
        }
        val store = DataStore.get(context)
        define("guardarDato") { a -> store.add(a.getOrElse(0) { "" }, a.getOrElse(1) { "" }).toDouble() }
        define("buscarDatos") { a -> store.search(a.getOrElse(0) { "" }, a.getOrElse(1) { "" }, limit = 500).map { it.line() } }
        define("buscarArchivos") { a -> DeviceFiles.search(context, a.getOrElse(0) { "" }, a.getOrElse(1) { "" }).map { it.path } }
        define("leerArchivo") { a ->
            val file = DeviceFiles.find(context, a.getOrElse(0) { "" }) ?: error("no encontré ese archivo")
            DeviceFiles.readText(context, file)
        }
        define("recuerdos") { Memory(context).facts() }
        define("fecha") { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()) }
        return ultra
    }
}

/** Herramientas para usar habilidades programadas y, solo si Maurizio lo pide, escribirlas. */
class SkillTools(context: Context) : ToolSet {

    private val context = context.applicationContext

    @Tool(description = "Ejecuta una de tus habilidades programadas y devuelve su resultado.")
    fun usarHabilidad(
        @ToolParam(description = "Nombre de la habilidad programada") nombre: String,
        @ToolParam(description = "Lo que hay que pasarle, o vacío") entrada: String,
    ): String {
        if (locked()) return LOCKED
        val memory = Memory(context)
        val key = memory.findCodeSkill(nombre) ?: return "No tengo ninguna habilidad programada llamada $nombre."
        return SkillEngine.run(context, memory.codeSkills().getValue(key).code, entrada)
    }

    @Tool(
        description = "Escribe el código JavaScript de una habilidad programada nueva o reescribe una existente. " +
            "Úsalo SOLO si Maurizio te pide expresamente programar o crear una habilidad. Maurizio debe aceptarlo."
    )
    fun proponerCodigoHabilidad(
        @ToolParam(description = "Nombre corto de la habilidad, por ejemplo Total de gastos") nombre: String,
        @ToolParam(description = "Para qué sirve y cuándo usarla, en una frase") descripcion: String,
        @ToolParam(description = "Código JavaScript con function ejecutar(entrada) que devuelve un texto. Puede usar ultra.guardarDato(coleccion, texto), ultra.buscarDatos(texto, coleccion), ultra.buscarArchivos(texto, tipo), ultra.leerArchivo(ruta), ultra.recuerdos() y ultra.fecha()") codigo: String,
    ): String {
        if (locked()) return LOCKED
        if (!Assistant.userAskedForCode()) {
            return "No puedes programarte sin que Maurizio te lo pida expresamente. No lo hagas."
        }
        val code = codigo.trim().removePrefix("```javascript").removePrefix("```js").removePrefix("```").removeSuffix("```").trim()
        SkillEngine.check(code)?.let { return "El código no es válido ($it). Corrígelo y vuelve a proponerlo." }
        val existing = Memory(context).findCodeSkill(nombre)
        val name = existing ?: nombre.trim()
        val title = if (existing != null) "Reprogramar la habilidad \"$existing\"" else "Programar la habilidad \"$name\""
        SelfEdits.propose(SelfChange(title, "${descripcion.trim()}\n\n$code", restartsSkills = true) {
            it.setCodeSkill(name, CodeSkill(descripcion.trim(), code))
        })
        return "Propuesta enviada con el código. No se instala hasta que Maurizio pulse Aplicar. Díselo."
    }

    @Tool(description = "Propone borrar una de tus habilidades programadas. Maurizio debe aceptarlo.")
    fun proponerBorrarCodigo(
        @ToolParam(description = "Nombre de la habilidad programada") nombre: String,
    ): String {
        if (locked()) return LOCKED
        val existing = Memory(context).findCodeSkill(nombre) ?: return "No tengo ninguna habilidad programada llamada $nombre."
        SelfEdits.propose(SelfChange("Borrar la habilidad programada \"$existing\"", "", restartsSkills = true) {
            it.removeCodeSkill(existing)
        })
        return "Propuesta enviada. No se borra hasta que Maurizio pulse Aplicar. Díselo."
    }

    private fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private companion object {
        const val LOCKED = "Para eso hay que desbloquear el teléfono."
    }
}
