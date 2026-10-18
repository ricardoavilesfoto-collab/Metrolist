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
    val syncedToYouTube: Boolean,
)

data class TransferProgress(
    val running: Boolean = false,
    val currentName: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val results: List<TransferResult> = emptyList(),
)

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
        val semaphore = Semaphore(6)
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

        val found = matches.filterNotNull().distinctBy { it.id }
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
                    if (!song.liked) database.update(song.toggleLike(syncToYouTube = syncWithYouTube))
                }
                synced = syncWithYouTube
            }
            TransferDestination.NEW_PLAYLIST -> {
                val browseId = if (syncWithYouTube) {
                    runCatching { YouTube.createPlaylist(source.name) }
                        .onFailure { Timber.w(it, "Could not create ${source.name} on YouTube Music") }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                val entity = PlaylistEntity(
                    name = source.name,
                    browseId = browseId,
                    bookmarkedAt = LocalDateTime.now(),
                    isEditable = true,
                    isAutoSync = browseId != null,
                )
                database.insert(entity)
                database.playlist(entity.id).first()?.let { playlist ->
                    database.addSongsToPlaylist(playlist, ids.map { it to null })
                }
                if (browseId != null) {
                    syncUtils.scheduleAddToPlaylist(browseId, entity.id, ids)
                    synced = true
                }
            }
        }

        return TransferResult(
            name = source.name,
            destination = source.destination,
            found = matches.count { it != null },
            total = source.tracks.size,
            missing = source.tracks.filterIndexed { index, _ -> matches[index] == null },
            syncedToYouTube = synced,
        )
    }

    private suspend fun findOnYouTubeMusic(track: TransferTrack): SongItem? {
        val artist = track.artists.firstOrNull().orEmpty()
        val candidates = search("${track.title} $artist".trim())
            .ifEmpty { if (artist.isNotEmpty()) search(cleanTitle(track.title)) else emptyList() }
        return candidates.withIndex().maxByOrNull { (index, item) -> score(track, item) - index * 0.2 }?.value
    }

    private suspend fun search(query: String): List<SongItem> =
        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            ?.items
            ?.filterIsInstance<SongItem>()
            ?.distinctBy { it.id }
            ?.take(6)
            .orEmpty()

    private fun score(track: TransferTrack, item: SongItem): Double {
        var score = 0.0
        val wanted = normalize(cleanTitle(track.title))
        val got = normalize(cleanTitle(item.title))
        score += when {
            wanted == got -> 4.0
            wanted.isNotEmpty() && (got.contains(wanted) || wanted.contains(got)) -> 2.0
            else -> 0.0
        }
        val wantedArtists = track.artists.map(::normalize).filter { it.isNotEmpty() }
        val gotArtists = item.artists.map { normalize(it.name) }
        if (wantedArtists.any { w -> gotArtists.any { g -> g.contains(w) || w.contains(g) } }) score += 3.0

        val itemDuration = item.duration
        if (track.durationSec != null && itemDuration != null) {
            val diff = abs(track.durationSec - itemDuration)
            score += when {
                diff <= 3 -> 2.0
                diff <= 10 -> 1.0
                diff > 30 -> -2.0
                else -> 0.0
            }
        }
        return score
    }

    private val bracketsRegex = Regex("""[(\[].*?[)\]]""")
    private val suffixRegex = Regex("""\s-\s.*(remaster|version|versión|live|en vivo|mono|stereo|edit|mix).*$""", RegexOption.IGNORE_CASE)
    private val accentsRegex = Regex("""\p{M}+""")
    private val symbolsRegex = Regex("""[^\p{L}\p{N} ]""")

    private fun cleanTitle(title: String) =
        title.replace(bracketsRegex, " ").replace(suffixRegex, "").trim().ifEmpty { title }

    private fun normalize(text: String) =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(accentsRegex, "")
            .replace(symbolsRegex, " ")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
