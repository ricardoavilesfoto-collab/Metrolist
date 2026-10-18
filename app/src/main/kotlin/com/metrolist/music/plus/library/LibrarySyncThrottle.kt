/**
 * Metrolist Plus — limita la sincronización que hace la Biblioteca cada vez que se entra a una de sus pantallas.
 *
 * El original sincroniza playlists, álbumes o artistas en cada entrada; con más de cien playlists eso ocupa la base
 * de datos (y cada escritura vuelve a ejecutar las consultas de la pantalla), así que las listas tardan en aparecer.
 */

package com.metrolist.music.plus.library

import android.os.SystemClock

object PlusLibrarySyncThrottle {
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L

    private val lastSyncAt = HashMap<String, Long>()

    /** True at most once every 15 minutes per [key]; the first call after the app starts always syncs. */
    @Synchronized
    fun shouldSync(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = lastSyncAt[key]
        if (last != null && now - last < MIN_INTERVAL_MS) return false
        lastSyncAt[key] = now
        return true
    }

    fun shouldSyncSavedPlaylists() = shouldSync("playlists")
}
