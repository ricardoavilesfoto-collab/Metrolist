/**
 * Metrolist Plus — fase 2: "enseñarle" a Metrolist y a YouTube Music el historial de Spotify.
 *
 * Busca en YouTube las canciones con 10+ reproducciones en Spotify (las más escuchadas primero), copia sus
 * reproducciones al historial de Metrolist (tabla event, con la marca TAUGHT_EVENT para que Estadísticas
 * Plus no las cuente dos veces) y da "Me gusta" a las 300 más escuchadas. Va despacio (≈1 búsqueda por
 * segundo), se puede pausar y repetir sin duplicar: cada canción borra sus reproducciones marcadas antes de
 * volver a escribirlas.
 */

package com.metrolist.music.plus.stats

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.YtmSyncKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Event
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.plus.transfer.PlusMatcher
import com.metrolist.music.plus.transfer.TransferTrack
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

const val TEACH_MIN_PLAYS = 10
private const val LIKE_TOP = 300
private const val TRIAL_SIZE = 50
private const val SEARCH_PAUSE_MS = 900L
private const val LIKE_PAUSE_MS = 300L
private const val MAX_ERRORS = 4

enum class TeachStop { DONE, PAUSED, TRIAL_DONE, NO_NETWORK }

data class TeachProgress(
    val running: Boolean = false,
    val total: Int = 0,
    val taught: Int = 0,
    val notFound: Int = 0,
    val liked: Int = 0,
    val stop: TeachStop? = null,
)

object PlusTeacher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _progress = MutableStateFlow(TeachProgress())
    val progress: StateFlow<TeachProgress> = _progress.asStateFlow()

    /** Reads the counters from the database (when the screen opens and nothing is running). */
    fun refresh(context: Context) {
        if (job?.isActive == true) return
        scope.launch {
            runCatching {
                val list = PlusHistoryStore.get(context).teachList(TEACH_MIN_PLAYS)
                _progress.update { current ->
                    current.copy(
                        total = list.size,
                        taught = list.count { it.taught },
                        notFound = list.count { it.match == -1 && it.ytId == null },
                    )
                }
            }.onFailure { Timber.w(it, "Could not read teach progress") }
        }
    }

    fun start(context: Context, database: MusicDatabase) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            _progress.update { it.copy(running = true, stop = null) }
            var stop = TeachStop.PAUSED
            try {
                stop = teachAll(appContext, database)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Teaching failed")
                stop = TeachStop.NO_NETWORK
            } finally {
                _progress.update { it.copy(running = false, stop = stop) }
            }
        }
    }

    fun pause() {
        job?.cancel()
        _progress.update { it.copy(running = false, stop = TeachStop.PAUSED) }
    }

    /** Forgets what was taught so the next run writes everything again (e.g. after restoring a Metrolist backup). */
    fun reset(context: Context) {
        if (job?.isActive == true) return
        scope.launch {
            PlusHistoryStore.get(context).resetTaught()
            _progress.update { TeachProgress() }
            refresh(context)
        }
    }

    private suspend fun teachAll(context: Context, database: MusicDatabase): TeachStop {
        val store = PlusHistoryStore.get(context)
        val list = store.teachList(TEACH_MIN_PLAYS)
        val likeKeys = list.take(LIKE_TOP).map { it.key }.toSet()
        val trial = list.none { it.taught }
        val canSync = context.dataStore.get(YtmSyncKey, true) &&
            "SAPISID" in parseCookieString(context.dataStore.get(InnerTubeCookieKey, ""))

        var taught = list.count { it.taught }
        var notFound = list.count { it.match == -1 && it.ytId == null }
        var liked = 0
        var processed = 0
        var errors = 0
        _progress.update { it.copy(total = list.size, taught = taught, notFound = notFound, liked = 0) }

        for (track in list) {
            if (track.taught) continue
            if (track.match == -1 && track.ytId == null) continue
            if (trial && processed >= TRIAL_SIZE) return TeachStop.TRIAL_DONE

            var ytId = track.ytId
            var metadata: MediaMetadata? = null
            if (ytId == null) {
                val match = try {
                    PlusMatcher.findOnYouTubeMusicOrThrow(TransferTrack(track.title, listOf(track.artist), track.album))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Search failed for ${track.title}")
                    if (++errors >= MAX_ERRORS) return TeachStop.NO_NETWORK
                    delay(15_000L * errors)
                    continue
                }
                errors = 0
                delay(SEARCH_PAUSE_MS)
                store.setMatch(track.key, match?.item?.id, match?.item?.thumbnail)
                if (match == null) {
                    notFound++
                    _progress.update { it.copy(notFound = notFound) }
                    processed++
                    continue
                }
                ytId = match.item.id
                metadata = match.item.toMediaMetadata()
            } else {
                store.setMatch(track.key, ytId, null)
            }

            teachSong(database, ytId, metadata ?: fallbackMetadata(track, ytId), store.spotifyPlaysFor(ytId))
            store.setTaught(ytId)
            taught++
            processed++

            if (track.key in likeKeys && like(database, ytId, canSync)) liked++
            _progress.update { it.copy(taught = taught, liked = liked) }
        }
        return TeachStop.DONE
    }

    private fun fallbackMetadata(track: TeachTrack, ytId: String) = MediaMetadata(
        id = ytId,
        title = track.title,
        artists = listOf(MediaMetadata.Artist(id = null, name = track.artist)),
        duration = -1,
        thumbnailUrl = track.thumb,
    )

    /** Replaces this song's taught plays with its Spotify plays, so running it twice never duplicates. */
    private suspend fun teachSong(database: MusicDatabase, ytId: String, metadata: MediaMetadata, plays: List<Pair<Long, Long>>) {
        if (plays.isEmpty()) return
        if (database.getSongByIdBlocking(ytId) == null) database.insert(metadata)
        val marked = plays.map { (ts, ms) -> ts + 1 to ms / 1000 * 1000 + 1 }
        database.withTransaction {
            val main = openHelper.writableDatabase
            main.execSQL(
                "UPDATE song SET totalPlayTime = MAX(0, totalPlayTime - COALESCE((SELECT SUM(playTime) FROM event " +
                    "WHERE songId = ? AND ${PlusHistoryStore.TAUGHT_EVENT}), 0)) WHERE id = ?",
                arrayOf<Any?>(ytId, ytId),
            )
            main.execSQL("DELETE FROM event WHERE songId = ? AND ${PlusHistoryStore.TAUGHT_EVENT}", arrayOf<Any?>(ytId))
            marked.forEach { (ts, ms) ->
                insert(
                    Event(
                        songId = ytId,
                        // ofInstant keeps the milliseconds (the +1 marker); seconds-based converters would drop it.
                        timestamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneOffset.UTC),
                        playTime = ms,
                    ),
                )
            }
            incrementTotalPlayTime(ytId, marked.sumOf { it.second })
        }
    }

    private suspend fun like(database: MusicDatabase, ytId: String, canSync: Boolean): Boolean {
        val song = database.getSongByIdBlocking(ytId)?.song ?: return false
        if (song.liked) return false
        database.update(song.toggleLike(syncToYouTube = false))
        if (canSync) {
            YouTube.likeVideo(ytId, true).onFailure { Timber.w(it, "Could not like $ytId on YouTube Music") }
            delay(LIKE_PAUSE_MS)
        }
        return true
    }
}
