/**
 * Metrolist Plus — limita la sincronización de playlists al entrar a la Biblioteca.
 *
 * El original sincroniza todas las playlists (y descarga la lista de canciones de cada una) cada vez que se abre
 * la pantalla; con más de cien playlists eso ocupa la base de datos y las playlists tardan en aparecer.
 */

package com.metrolist.music.plus.library

import android.os.SystemClock

object PlusLibrarySyncThrottle {
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L

    private var lastSyncAt = 0L

    /** True at most once every 15 minutes; the first call after the app starts always syncs. */
    @Synchronized
    fun shouldSyncSavedPlaylists(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastSyncAt != 0L && now - lastSyncAt < MIN_INTERVAL_MS) return false
        lastSyncAt = now
        return true
    }
}
