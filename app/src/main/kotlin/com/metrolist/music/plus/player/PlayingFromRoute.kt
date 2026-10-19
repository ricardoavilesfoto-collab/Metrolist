/**
 * Metrolist Plus — al tocar el nombre de la playlist/álbum en el encabezado del reproductor, abrirla.
 *
 * Orden: (1) la pantalla desde la que se inició la cola (álbum, playlist, artista, búsqueda…), que
 * PlusPlayingFrom recuerda; (2) la playlist o mix de YouTube de la cola; (3) el nombre de la cola buscado en la
 * biblioteca; (4) el artista de la canción (radios iniciadas desde el Inicio).
 */

package com.metrolist.music.plus.player

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubePlaylistQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Remembers which screen each queue was started from, so the player header can go back there. */
object PlusPlayingFrom {
    private const val PREFS = "plus_playing_from"
    private const val KEY = "origin"

    /** Main tabs say nothing about what is playing. */
    private val ROOT_ROUTES = setOf("home", "search_input", "listen_together", "library", "explore")

    @Volatile
    private var currentRoute: String? = null
    private var context: Context? = null

    private val _origin = MutableStateFlow<String?>(null)
    val origin: StateFlow<String?> = _origin.asStateFlow()

    /** Called once from MainActivity with its NavController. */
    @Composable
    fun Track(navController: NavController) {
        val appContext = LocalContext.current.applicationContext
        DisposableEffect(navController) {
            if (context == null) {
                context = appContext
                _origin.value = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            }
            val listener = NavController.OnDestinationChangedListener { controller, _, _ ->
                currentRoute = controller.currentBackStackEntry?.let(::concreteRoute)
            }
            navController.addOnDestinationChangedListener(listener)
            onDispose { navController.removeOnDestinationChangedListener(listener) }
        }
    }

    /** Called from PlayerConnection.playQueue. */
    fun onPlayQueue(queue: Queue) {
        val origin = currentRoute?.takeIf { it.substringBefore('/').substringBefore('?') !in ROOT_ROUTES }
            ?: queueRoute(queue)
        _origin.value = origin
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, origin)?.apply()
    }

    private fun queueRoute(queue: Queue): String? {
        val playlistId = when (queue) {
            is YouTubePlaylistQueue -> queue.plusPlaylistId
            is YouTubeQueue -> queue.plusEndpoint.playlistId
            else -> null
        } ?: return null
        // RDAMVM… is the radio of a single song: there is no page for it.
        return if (playlistId.startsWith("RDAMVM")) null else "online_playlist/$playlistId"
    }

    private val argRegex = Regex("""\{([^}]+)\}""")

    @Suppress("DEPRECATION")
    private fun concreteRoute(entry: NavBackStackEntry): String? {
        val pattern = entry.destination.route ?: return null
        val args = entry.arguments
        return argRegex.replace(pattern) { match -> Uri.encode(args?.get(match.groupValues[1])?.toString().orEmpty()) }
    }
}

@Composable
fun rememberPlusPlayingFromRoute(): String? {
    val playerConnection = LocalPlayerConnection.current ?: return null
    val context = LocalContext.current
    val database = LocalDatabase.current
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val origin by PlusPlayingFrom.origin.collectAsStateWithLifecycle()
    val albumId = mediaMetadata?.album?.id
    val artistId = mediaMetadata?.artists?.firstOrNull()?.id
    val route by produceState<String?>(null, origin, queueTitle, albumId, artistId) {
        value = origin
            ?: resolvePlayingFromRoute(context, database, queueTitle, albumId)
            ?: artistId?.let { "artist/$it" }
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
