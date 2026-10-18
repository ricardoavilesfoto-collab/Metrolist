/**
 * Metrolist Plus — recuerda en memoria la última lista de cada pantalla de la Biblioteca.
 *
 * Los ViewModels del original empiezan con una lista vacía y esperan a la base de datos cada vez que se vuelve a la
 * Biblioteca. Con esto la pantalla muestra al instante lo último que se vio, y la consulta la actualiza al llegar.
 * Solo vive mientras la app está abierta.
 */

package com.metrolist.music.plus.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap

object PlusLibraryListCache {
    private val lists = ConcurrentHashMap<String, List<Any>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): List<T> = (lists[key] as List<T>?).orEmpty()

    fun <T : Any> Flow<List<T>>.rememberedAs(key: String): Flow<List<T>> = onEach { lists[key] = it }
}
