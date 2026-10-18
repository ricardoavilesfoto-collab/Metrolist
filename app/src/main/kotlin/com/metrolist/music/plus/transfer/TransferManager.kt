/**
 * Metrolist Plus — busca en YouTube Music las canciones importadas y las guarda en la biblioteca.
 * Corre en su propio scope para que la transferencia siga aunque se salga de la pantalla.
 */

package com.metrolist.music.plus.transfer

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.SyncUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.text.Normalizer
import java.time.LocalDateTime
import kotlin.math.abs

data class TransferResult(
    val name: String,
    val destination: TransferDestination,
    val found: Int,
    val total: Int,
    val missing: List<TransferTrack>,
    val doubtful: List<TransferTrack>,
    val syncedToYouTube: Boolean,
)

data class TransferProgress(
    val running: Boolean = false,
    val currentName: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val results: List<TransferResult> = emptyList(),
)

private const val MAX_PER_PLAYLIST = 4900
private const val ACCEPT_SCORE = 0.55
private const val SURE_SCORE = 0.75
private const val LIKE_PAUSE_MS = 300L

object PlusTransferManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _progress = MutableStateFlow(TransferProgress())
    val progress: StateFlow<TransferProgress> = _progress.asStateFlow()

    fun start(
        sources: List<TransferSource>,
        database: MusicDatabase,
        syncUtils: SyncUtils,
        syncWithYouTube: Boolean,
    ) {
        if (job?.isActive == true || sources.isEmpty()) return
        _progress.value = TransferProgress(running = true, total = sources.sumOf { it.tracks.size })
        job = scope.launch {
            try {
                sources.forEach { source ->
                    _progress.update { it.copy(currentName = source.name) }
                    val result = transfer(source, database, syncUtils, syncWithYouTube)
                    _progress.update { it.copy(results = it.results + result) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Transfer failed")
            } finally {
                _progress.update { it.copy(running = false, currentName = null) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun clearResults() {
        if (job?.isActive != true) _progress.value = TransferProgress()
    }

    private suspend fun transfer(
        source: TransferSource,
        database: MusicDatabase,
        syncUtils: SyncUtils,
        syncWithYouTube: Boolean,
    ): TransferResult {
        val semaphore = Semaphore(4)
        val matches = coroutineScope {
            source.tracks.map { track ->
                async {
                    semaphore.withPermit {
                        val match = runCatching { findOnYouTubeMusic(track) }
                            .onFailure { if (it is CancellationException) throw it }
                            .getOrNull()
                        _progress.update { it.copy(done = it.done + 1) }
                        match
                    }
                }
            }.awaitAll()
        }

        val found = matches.mapNotNull { it?.item }.distinctBy { it.id }
        found.forEach { song ->
            runCatching { database.insert(song.toMediaMetadata()) }
                .onFailure { Timber.w(it, "Could not insert ${song.id}") }
        }
        val ids = found.map { it.id }

        var synced = false
        when (source.destination) {
            TransferDestination.LIKED_SONGS -> {
                ids.forEach { id ->
                    val song = database.getSongByIdBlocking(id)?.song ?: return@forEach
                    if (song.liked) return@forEach
                    database.update(song.toggleLike(syncToYouTube = false))
                    // One at a time: thousands of parallel likes get the account rate-limited.
                    if (syncWithYouTube) {
                        YouTube.likeVideo(id, true).onFailure { Timber.w(it, "Could not like $id on YouTube Music") }
                        delay(LIKE_PAUSE_MS)
                    }
                }
                synced = syncWithYouTube
            }
            TransferDestination.NEW_PLAYLIST -> {
                // YouTube Music caps playlists at 5,000 songs: split evenly, keeping the original order.
                val parts = (ids.size + MAX_PER_PLAYLIST - 1) / MAX_PER_PLAYLIST
                val perPart = if (parts <= 1) ids.size else (ids.size + parts - 1) / parts
                val chunks = if (parts <= 1) listOf(ids) else ids.chunked(perPart)
                chunks.forEachIndexed { index, chunk ->
                    val name = if (chunks.size == 1) source.name else "${source.name} (${index + 1}/${chunks.size})"
                    if (savePlaylist(name, chunk, database, syncUtils, syncWithYouTube)) synced = true
                }
            }
        }

        return TransferResult(
            name = source.name,
            destination = source.destination,
            found = matches.count { it != null },
            total = source.tracks.size,
            missing = source.tracks.filterIndexed { index, _ -> matches[index] == null },
            doubtful = source.tracks.filterIndexed { index, _ -> matches[index]?.let { it.score < SURE_SCORE } == true },
            syncedToYouTube = synced,
        )
    }

    /** Returns true when the playlist was also created in the YouTube Music account. */
    private suspend fun savePlaylist(
        name: String,
        ids: List<String>,
        database: MusicDatabase,
        syncUtils: SyncUtils,
        syncWithYouTube: Boolean,
    ): Boolean {
        val browseId = if (syncWithYouTube) {
            runCatching { YouTube.createPlaylist(name) }
                .onFailure { Timber.w(it, "Could not create $name on YouTube Music") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val entity = PlaylistEntity(
            name = name,
            browseId = browseId,
            bookmarkedAt = LocalDateTime.now(),
            isEditable = true,
            isAutoSync = browseId != null,
        )
        database.insert(entity)
        database.playlist(entity.id).first()?.let { playlist ->
            database.addSongsToPlaylist(playlist, ids.map { it to null })
        }
        if (browseId == null) return false
        syncUtils.scheduleAddToPlaylist(browseId, entity.id, ids)
        return true
    }

    private class Match(val item: SongItem, val score: Double)

    /** Same criteria as herramientas/spotify-a-ytmusic: songs first, then videos if unsure. */
    private suspend fun findOnYouTubeMusic(track: TransferTrack): Match? {
        val query = "${track.title} ${track.artists.firstOrNull().orEmpty()}".trim()
        var best: Match? = null
        for (filter in listOf(YouTube.SearchFilter.FILTER_SONG, YouTube.SearchFilter.FILTER_VIDEO)) {
            search(query, filter).forEach { item ->
                val score = score(track, item)
                if (score > (best?.score ?: 0.0)) best = Match(item, score)
            }
            if ((best?.score ?: 0.0) >= SURE_SCORE) break
        }
        return best?.takeIf { it.score >= ACCEPT_SCORE }
    }

    private suspend fun search(query: String, filter: YouTube.SearchFilter): List<SongItem> =
        YouTube.search(query, filter).getOrNull()
            ?.items
            ?.filterIsInstance<SongItem>()
            ?.distinctBy { it.id }
            ?.take(5)
            .orEmpty()

    private fun score(track: TransferTrack, item: SongItem): Double {
        val titleScore = similarity(track.title, item.title)
        val resultArtists = item.artists.joinToString(" ") { it.name }
        var artistScore = track.artists.maxOfOrNull { similarity(it, resultArtists) } ?: 0.0
        val normalizedArtists = normalize(resultArtists)
        if (track.artists.any { normalize(it).let { a -> a.isNotEmpty() && normalizedArtists.contains(a) } }) {
            artistScore = maxOf(artistScore, 0.9)
        }

        val itemDuration = item.duration
        val durationScore = if (track.durationSec != null && itemDuration != null) {
            val diff = abs(track.durationSec - itemDuration)
            if (diff <= 3) 1.0 else if (diff <= 10) 0.6 else 0.0
        } else {
            0.5
        }
        val score = 0.55 * titleScore + 0.35 * artistScore + 0.10 * durationScore
        // A matching title by another artist is usually a cover or a different song.
        return if (artistScore >= 0.5) score else score * 0.6
    }

    private fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        return 1.0 - levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private val bracketsRegex = Regex("""[(\[].*?[)\]]""")
    private val suffixRegex = Regex("""\s-\s.*(remaster|version|versión|live|en vivo|mono|stereo|edit|mix).*$""", RegexOption.IGNORE_CASE)
    private val accentsRegex = Regex("""\p{M}+""")
    private val symbolsRegex = Regex("""[^\p{L}\p{N} ]""")

    private fun normalize(text: String) =
        Normalizer.normalize(text.replace(suffixRegex, "").replace(bracketsRegex, " ").lowercase(), Normalizer.Form.NFD)
            .replace(accentsRegex, "")
            .replace(symbolsRegex, " ")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
