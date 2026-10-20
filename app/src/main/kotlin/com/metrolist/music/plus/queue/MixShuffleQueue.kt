/**
 * Metrolist Plus — "Aleatorio con recomendaciones": la playlist en aleatorio y, cada dos canciones, una que no
 * está en la playlist sacada de la radio de YouTube Music de una canción de la playlist (mismo género o vibe).
 *
 * La cola se arma por bloques de [CHUNK] canciones de la playlist; cada bloque hace a lo más [MAX_RADIOS]
 * consultas a YouTube y el reproductor pide el siguiente bloque cuando faltan pocas canciones. Sin internet
 * simplemente suena la playlist en aleatorio.
 */

package com.metrolist.music.plus.queue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.Queue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlusMixShuffleQueue(
    private val title: String?,
    songs: List<MediaMetadata>,
) : Queue {
    private val remaining = ArrayDeque(songs.distinctBy { it.id }.shuffled())
    private val knownIds = songs.mapTo(HashSet()) { it.id }
    // Same song uploaded under another video id (or already recommended) is skipped by title + first artist.
    private val knownKeys = songs.mapTo(HashSet()) { key(it.title, it.artists.firstOrNull()?.name) }
    private val pool = ArrayDeque<SongItem>()

    override val preloadItem: MediaMetadata? = remaining.firstOrNull()

    override suspend fun getInitialStatus() = Queue.Status(title, nextChunk(), 0)

    override fun hasNextPage() = remaining.isNotEmpty()

    override suspend fun nextPage() = nextChunk()

    private suspend fun nextChunk(): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val own = List(minOf(CHUNK, remaining.size)) { remaining.removeFirst() }
            refillPool(seeds = own, needed = own.size / 2)
            buildList {
                own.forEachIndexed { index, song ->
                    add(song.toMediaItem())
                    if (index % 2 == 1) pool.removeFirstOrNull()?.let { add(it.toMediaItem()) }
                }
            }
        }

    private suspend fun refillPool(seeds: List<MediaMetadata>, needed: Int) {
        for (seed in seeds.shuffled().take(MAX_RADIOS)) {
            if (pool.size >= needed) return
            val radio = YouTube.next(WatchEndpoint(videoId = seed.id, playlistId = "RDAMVM${seed.id}"))
                .getOrNull()?.items ?: continue
            radio
                .filter { it.id !in knownIds && key(it.title, it.artists.firstOrNull()?.name) !in knownKeys }
                .shuffled()
                .take(PER_RADIO)
                .forEach {
                    knownIds += it.id
                    knownKeys += key(it.title, it.artists.firstOrNull()?.name)
                    pool += it
                }
        }
    }

    private fun key(title: String, artist: String?) = "${title.lowercase().trim()}|${artist?.lowercase()?.trim()}"

    private companion object {
        const val CHUNK = 10
        const val MAX_RADIOS = 3
        const val PER_RADIO = 3
    }
}

@Composable
fun PlusMixShuffleButton(
    title: String?,
    songs: () -> List<MediaMetadata>,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    Surface(
        onClick = {
            val list = songs()
            if (list.isNotEmpty()) playerConnection.playQueue(PlusMixShuffleQueue(title, list))
        },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(48.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.plus_shuffle_mix),
                contentDescription = stringResource(R.string.plus_mix_shuffle),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
