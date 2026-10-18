/**
 * Metrolist Plus — eliminar las descargas de una playlist sin romper otras playlists descargadas.
 *
 * Metrolist descarga por canción: si una canción está en dos playlists hay un solo archivo. Antes de borrar las
 * descargas de una playlist, se conservan las canciones que también pertenecen a otra playlist de la Biblioteca
 * (o a "Me gusta") que el usuario tenga descargada.
 *
 * Como la app no registra qué playlists se descargaron, una playlist Q cuenta como descargada si al menos el 80 %
 * de sus canciones que NO están en la playlist que se borra tienen descarga (así no cuentan las que se bajaron
 * solo por la playlist que se está borrando).
 */

package com.metrolist.music.plus.library

import android.content.Context
import android.widget.Toast
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.ExoDownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val DOWNLOADED_RATIO = 0.8
private const val LIKED_GROUP = "__liked__"

data class RemovalPlan(val toRemove: List<String>, val kept: List<String>)

suspend fun planPlaylistDownloadRemoval(
    database: MusicDatabase,
    downloads: Map<String, Download>,
    songIds: Collection<String>,
    playlistId: String?,
): RemovalPlan = withContext(Dispatchers.IO) {
    val downloaded = downloads.filterValues { it.state != Download.STATE_REMOVING }.keys
    val candidates = songIds.filter { it in downloaded }.distinct()
    if (candidates.isEmpty()) return@withContext RemovalPlan(emptyList(), emptyList())

    val groups = HashMap<String, MutableList<String>>()
    try {
        val db = database.openHelper.readableDatabase
        db.query(
            "SELECT m.playlistId, m.songId FROM playlist_song_map m " +
                "JOIN playlist p ON p.id = m.playlistId WHERE p.bookmarkedAt IS NOT NULL",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val otherPlaylistId = cursor.getString(0)
                if (otherPlaylistId != playlistId) {
                    groups.getOrPut(otherPlaylistId) { mutableListOf() }.add(cursor.getString(1))
                }
            }
        }
        db.query("SELECT id FROM song WHERE liked = 1").use { cursor ->
            val liked = groups.getOrPut(LIKED_GROUP) { mutableListOf() }
            while (cursor.moveToNext()) liked.add(cursor.getString(0))
        }
    } catch (e: Exception) {
        // If the check fails, keep everything rather than deleting something the user still needs.
        Timber.e(e, "Could not check shared downloads")
        return@withContext RemovalPlan(emptyList(), candidates)
    }

    val target = songIds.toHashSet()
    val protected = HashSet<String>()
    for (songs in groups.values) {
        val outside = songs.filter { it !in target }
        if (outside.isEmpty()) continue
        val ratio = outside.count { it in downloaded }.toDouble() / outside.size
        if (ratio >= DOWNLOADED_RATIO) protected += songs
    }

    RemovalPlan(
        toRemove = candidates.filterNot { it in protected },
        kept = candidates.filter { it in protected },
    )
}

fun removeDownloads(context: Context, songIds: Collection<String>) {
    songIds.forEach { songId ->
        DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, songId, false)
    }
}

/** For the original app's "remove download" buttons: removes only what no other downloaded playlist needs. */
fun CoroutineScope.removePlaylistDownloadsKeepingShared(
    context: Context,
    database: MusicDatabase,
    downloads: Map<String, Download>,
    songIds: Collection<String>,
    playlistId: String?,
) {
    launch {
        val plan = planPlaylistDownloadRemoval(database, downloads, songIds, playlistId)
        removeDownloads(context, plan.toRemove)
        if (plan.kept.isNotEmpty()) {
            Toast.makeText(
                context,
                context.getString(R.string.plus_remove_downloads_kept_toast, plan.kept.size),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
