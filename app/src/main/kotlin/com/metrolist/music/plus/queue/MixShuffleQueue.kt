/**
 * Metrolist Plus — "Aleatorio con recomendaciones": la playlist en aleatorio y, después de cada canción, una que no
 * está en la playlist sacada de la radio de YouTube Music de una canción de la playlist (mismo género o vibe).
 *
 * La cola se arma por bloques de [CHUNK] canciones de la playlist; cada bloque hace a lo más [MAX_RADIOS]
 * consultas a YouTube y el reproductor pide el siguiente bloque cuando faltan pocas canciones. Sin internet
 * simplemente suena la playlist en aleatorio. Las recomendadas llevan la etiqueta "Recomendada" en la cola
 * (usa `suggestedBy`, el mismo campo con el que Escuchar juntos muestra quién sugirió una canción).
 */

package com.metrolist.music.plus.queue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.plus.player.SpotifyGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PlusMixShuffleQueue(
    private val title: String?,
    songs: List<MediaMetadata>,
    private val recommendedLabel: String,
) : Queue {
    private val remaining = ArrayDeque(songs.distinctBy { it.id }.shuffled())
    private val knownIds = songs.mapTo(HashSet()) { it.id }
    // Same song uploaded under another video id (or already recommended) is skipped by title + first artist.
    private val knownKeys = songs.mapTo(HashSet()) { key(it.title, it.artists.firstOrNull()?.name) }
    private val pool = ArrayDeque<SongItem>()

    // No preload: with a single song loaded the player adds an automix song by itself while the first block loads.
    override val preloadItem: MediaMetadata? = null
    private val mutex = Mutex()

    override suspend fun getInitialStatus() = Queue.Status(title, nextChunk(), 0)

    override fun hasNextPage() = remaining.isNotEmpty()

    override suspend fun nextPage() = nextChunk()

    private suspend fun nextChunk(): List<MediaItem> =
        withContext(Dispatchers.IO) { mutex.withLock { buildChunk() } }

    private suspend fun buildChunk(): List<MediaItem> {
        val own = List(minOf(CHUNK, remaining.size)) { remaining.removeFirst() }
        if (pool.size < own.size) refillPool(seeds = own)
        return buildList {
            own.forEach { song ->
                add(song.toMediaItem())
                pool.removeFirstOrNull()?.let { add(it.toMediaMetadata().copy(suggestedBy = recommendedLabel).toMediaItem()) }
            }
        }
    }

    // Radios are fetched in parallel so the first block (and the start of playback) waits for one round trip.
    private suspend fun refillPool(seeds: List<MediaMetadata>) {
        val radios = coroutineScope {
            seeds.shuffled().take(MAX_RADIOS).map { seed ->
                async {
                    YouTube.next(WatchEndpoint(videoId = seed.id, playlistId = "RDAMVM${seed.id}")).getOrNull()?.items.orEmpty()
                }
            }.awaitAll()
        }
        radios.forEach { radio ->
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
        const val CHUNK = 8
        const val MAX_RADIOS = 3
        const val PER_RADIO = 4
    }
}

@Composable
fun PlusMixShuffleButton(
    title: String?,
    songs: () -> List<MediaMetadata>,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val recommendedLabel = stringResource(R.string.plus_mix_recommended)
    Surface(
        onClick = {
            val list = songs()
            if (list.isNotEmpty()) playerConnection.playQueue(PlusMixShuffleQueue(title, list, recommendedLabel))
        },
        shape = CircleShape,
        color = SpotifyGreen,
        contentColor = Color.Black,
        modifier = Modifier.size(48.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.plus_shuffle_mix),
                contentDescription = stringResource(R.string.plus_mix_shuffle),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
