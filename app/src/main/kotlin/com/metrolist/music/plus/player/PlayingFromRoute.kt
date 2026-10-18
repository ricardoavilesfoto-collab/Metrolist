/**
 * Metrolist Plus — al tocar el nombre de la playlist/álbum en el encabezado del reproductor, abrirla.
 *
 * La cola solo guarda su nombre, no de dónde salió. Se busca ese nombre en la biblioteca (listas automáticas,
 * playlists, álbumes y artistas) y se navega a la misma pantalla que abre la Biblioteca. Las playlists de YouTube
 * que no están guardadas, las radios y los mixes no están en la biblioteca: en ese caso no se puede tocar.
 */

package com.metrolist.music.plus.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Composable
fun rememberPlusPlayingFromRoute(): String? {
    val playerConnection = LocalPlayerConnection.current ?: return null
    val context = LocalContext.current
    val database = LocalDatabase.current
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val albumId = mediaMetadata?.album?.id
    val route by produceState<String?>(null, queueTitle, albumId) {
        value = resolvePlayingFromRoute(context, database, queueTitle, albumId)
    }
    return route
}

private suspend fun resolvePlayingFromRoute(
    context: Context,
    database: MusicDatabase,
    queueTitle: String?,
    albumId: String?,
): String? {
    // Sin título de cola el encabezado muestra el álbum de la canción.
    if (queueTitle.isNullOrBlank()) return albumId?.let { "album/$it" }

    when (queueTitle) {
        context.getString(R.string.liked) -> return "auto_playlist/liked"
        context.getString(R.string.offline) -> return "auto_playlist/downloaded"
        context.getString(R.string.uploaded_playlist) -> return "auto_playlist/uploaded"
        "Cache Songs" -> return "cache_playlist/cached"
    }
    val topPrefix = context.getString(R.string.my_top) + " "
    if (queueTitle.startsWith(topPrefix)) {
        queueTitle.removePrefix(topPrefix).toIntOrNull()?.let { return "top_playlist/$it" }
    }

    return withContext(Dispatchers.IO) {
        try {
            val db = database.openHelper.readableDatabase
            db.query(
                "SELECT p.id, p.browseId, p.isEditable, " +
                    "(SELECT COUNT(*) FROM playlist_song_map m WHERE m.playlistId = p.id) " +
                    "FROM playlist p WHERE p.name = ? " +
                    "ORDER BY p.bookmarkedAt IS NULL, p.bookmarkedAt DESC LIMIT 1",
                arrayOf<Any?>(queueTitle),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getString(0)
                    val browseId = cursor.getString(1)
                    val isEditable = cursor.getInt(2) != 0
                    val songCount = cursor.getInt(3)
                    // Misma regla que la Biblioteca (LibraryMixScreen) al abrir una playlist.
                    return@withContext if (!isEditable && songCount == 0 && browseId != null) {
                        "online_playlist/$browseId"
                    } else {
                        "local_playlist/$id"
                    }
                }
            }
            db.query(
                "SELECT id FROM album WHERE title = ? ORDER BY bookmarkedAt IS NULL LIMIT 1",
                arrayOf<Any?>(queueTitle),
            ).use { cursor ->
                if (cursor.moveToFirst()) return@withContext "album/${cursor.getString(0)}"
            }
            db.query(
                "SELECT id FROM artist WHERE name = ? ORDER BY bookmarkedAt IS NULL LIMIT 1",
                arrayOf<Any?>(queueTitle),
            ).use { cursor ->
                if (cursor.moveToFirst()) return@withContext "artist/${cursor.getString(0)}"
            }
            null
        } catch (e: Exception) {
            Timber.e(e, "No se pudo buscar el origen de la cola \"$queueTitle\"")
            null
        }
    }
}
