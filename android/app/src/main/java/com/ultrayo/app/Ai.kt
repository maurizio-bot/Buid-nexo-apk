package com.ultrayo.app

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** El modelo vive mientras viva la app, aunque la pantalla se recree (por ejemplo al girarla). */
object Ai {
    /** Todo lo que toca el modelo se ejecuta en este único hilo. */
    val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Volatile
    var brain: Brain? = null
}
