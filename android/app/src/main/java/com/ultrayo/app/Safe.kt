package com.ultrayo.app

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus

/**
 * Red de seguridad: un fallo en algo que corre en segundo plano (un hilo, una corrutina) no debe
 * cerrar la app entera. Se apunta en el registro de errores (Ajustes > Registro de errores) y la
 * app sigue. Solo se deja pasar la falta de memoria, porque después de eso nada es fiable.
 */
object Safe {

    /** Un MainScope cuyos fallos sin capturar se apuntan en vez de cerrar la app. */
    fun mainScope(what: String, onFailure: ((Throwable) -> Unit)? = null): CoroutineScope =
        MainScope() + CoroutineExceptionHandler { _, error ->
            ErrorLog.add("Fallo en segundo plano ($what)", error)
            onFailure?.let { runCatching { it(error) } }
        }

    /** Ejecuta [block]; si falla, lo apunta y devuelve null. */
    inline fun <T> run(what: String, block: () -> T): T? = try {
        block()
    } catch (e: OutOfMemoryError) {
        throw e
    } catch (e: Throwable) {
        ErrorLog.add(what, e)
        null
    }

    /** Envuelve una tarea para un Executor: si falla, se apunta y el hilo sigue vivo. */
    fun task(what: String, block: () -> Unit): Runnable = Runnable { run(what, block) }

    /** Un texto corto para Maurizio a partir de un fallo. */
    fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() }?.take(160) ?: error.javaClass.simpleName
}
