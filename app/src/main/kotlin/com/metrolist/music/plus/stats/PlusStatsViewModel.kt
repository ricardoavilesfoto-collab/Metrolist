/**
 * Metrolist Plus — datos de la pantalla Estadísticas Plus.
 */

package com.metrolist.music.plus.stats

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import javax.inject.Inject

sealed interface StatsPeriod {
    data object All : StatsPeriod
    data object Last30 : StatsPeriod
    data class Year(val year: Int) : StatsPeriod
    data class Month(val year: Int, val month: Int) : StatsPeriod
}

data class ChartBar(val label: String, val ms: Long, val drillTo: StatsPeriod?)

data class EvolutionData(
    val topByYear: List<YearArtist>,
    val years: List<Int>,
    val series: List<Pair<String, List<Long>>>,
    val discovered: Map<Int, Long>,
)

data class StatsData(
    val summary: StatsSummary,
    val bars: List<ChartBar>,
    val heatmap: Array<LongArray>,
    val topArtists: List<TopEntry>,
    val topTracks: List<TopEntry>,
    val topAlbums: List<TopEntry>,
    val habits: Habits,
    val evolution: EvolutionData?,
)

const val TOP_LIMIT = 50

/** Local wall-clock time stored as a UTC epoch, the convention used by Metrolist's database. */
fun LocalDateTime.toStoredMillis(): Long = toEpochSecond(ZoneOffset.UTC) * 1000

fun monthLabel(month: Int): String =
    java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()).trimEnd('.')

fun storedMillisToDate(ms: Long): LocalDateTime = LocalDateTime.ofEpochSecond(ms / 1000, 0, ZoneOffset.UTC)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlusStatsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    val store = PlusHistoryStore.get(context)

    val period = MutableStateFlow<StatsPeriod>(StatsPeriod.All)
    val source = MutableStateFlow<Int?>(null)

    private val _data = MutableStateFlow<StatsData?>(null)
    val data: StateFlow<StatsData?> = _data.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _hasSpotify = MutableStateFlow(false)
    val hasSpotify: StateFlow<Boolean> = _hasSpotify.asStateFlow()

    /** Years with any listening, newest first, for the period selector. */
    private val _years = MutableStateFlow<List<Int>>(emptyList())
    val years: StateFlow<List<Int>> = _years.asStateFlow()

    val importState = store.importState

    init {
        viewModelScope.launch {
            runCatching { store.syncMetrolist(database) }.onFailure { Timber.e(it, "Could not read Metrolist history") }
            combine(period, source, store.version) { p, s, _ -> p to s }.collectLatest { (p, s) -> load(p, s) }
        }
    }

    private suspend fun load(period: StatsPeriod, source: Int?) {
        _loading.value = true
        try {
            val result = withContext(Dispatchers.IO) {
                _hasSpotify.value = store.hasSpotify().also(store::rememberHasSpotify)
                val bounds = store.bounds(source)
                _years.value = bounds?.let { (from, to) ->
                    (storedMillisToDate(to).year downTo storedMillisToDate(from).year).toList()
                }.orEmpty()

                val filter = filterFor(period, source)
                val summary = async { store.summary(filter) }
                val bars = async { bars(period, filter, bounds) }
                val heatmap = async { store.heatmap(filter) }
                val artists = async { store.topArtists(filter, TOP_LIMIT) }
                val tracks = async { store.topTracks(filter, TOP_LIMIT) }
                val albums = async { store.topAlbums(filter, TOP_LIMIT) }
                val habits = async { store.habits(filter, LocalDateTime.now().toStoredMillis()) }
                val evolution = async { if (period == StatsPeriod.All) evolution(source) else null }
                StatsData(
                    summary.await(),
                    bars.await(),
                    heatmap.await(),
                    artists.await(),
                    tracks.await(),
                    albums.await(),
                    habits.await(),
                    evolution.await(),
                )
            }
            _data.value = result
            fillThumbnails(result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Could not load Plus stats")
        } finally {
            _loading.value = false
        }
    }

    private fun fillThumbnails(result: StatsData) {
        viewModelScope.launch {
            val changed = runCatching {
                store.fillThumbnails(result.topArtists.take(10), result.topTracks.take(10), database)
            }.getOrDefault(false)
            if (changed) reloadQuietly()
        }
    }

    /** Refreshes the lists without showing the loading state (used after pictures arrive). */
    private suspend fun reloadQuietly() {
        val current = _data.value ?: return
        val filter = filterFor(period.value, source.value)
        val updated = withContext(Dispatchers.IO) {
            runCatching {
                current.copy(
                    topArtists = store.topArtists(filter, TOP_LIMIT),
                    topTracks = store.topTracks(filter, TOP_LIMIT),
                    topAlbums = store.topAlbums(filter, TOP_LIMIT),
                )
            }.getOrNull()
        }
        if (updated != null) _data.value = updated
    }

    private fun filterFor(period: StatsPeriod, source: Int?): HistoryFilter = when (period) {
        StatsPeriod.All -> HistoryFilter(0, Long.MAX_VALUE, source)
        StatsPeriod.Last30 -> {
            val start = LocalDate.now().minusDays(29).atStartOfDay()
            HistoryFilter(start.toStoredMillis(), Long.MAX_VALUE, source)
        }
        is StatsPeriod.Year -> HistoryFilter(
            LocalDate.of(period.year, 1, 1).atStartOfDay().toStoredMillis(),
            LocalDate.of(period.year + 1, 1, 1).atStartOfDay().toStoredMillis(),
            source,
        )
        is StatsPeriod.Month -> {
            val month = YearMonth.of(period.year, period.month)
            HistoryFilter(
                month.atDay(1).atStartOfDay().toStoredMillis(),
                month.plusMonths(1).atDay(1).atStartOfDay().toStoredMillis(),
                source,
            )
        }
    }

    private fun bars(period: StatsPeriod, filter: HistoryFilter, bounds: Pair<Long, Long>?): List<ChartBar> = when (period) {
        StatsPeriod.All -> {
            val byYear = store.timeBy(filter, "%Y")
            val years = bounds?.let { (from, to) -> storedMillisToDate(from).year..storedMillisToDate(to).year } ?: IntRange.EMPTY
            years.map { year -> ChartBar(year.toString(), byYear[year.toString()] ?: 0L, StatsPeriod.Year(year)) }
        }
        is StatsPeriod.Year -> {
            val byMonth = store.timeBy(filter, "%m")
            (1..12).map { month ->
                ChartBar(monthLabel(month), byMonth["%02d".format(month)] ?: 0L, StatsPeriod.Month(period.year, month))
            }
        }
        is StatsPeriod.Month -> {
            val byDay = store.timeBy(filter, "%d")
            (1..YearMonth.of(period.year, period.month).lengthOfMonth()).map { day ->
                ChartBar(day.toString(), byDay["%02d".format(day)] ?: 0L, null)
            }
        }
        StatsPeriod.Last30 -> {
            val byDate = store.timeBy(filter, "%Y-%m-%d")
            val today = LocalDate.now()
            (29 downTo 0).map { back ->
                val date = today.minusDays(back.toLong())
                ChartBar(date.dayOfMonth.toString(), byDate[date.toString()] ?: 0L, null)
            }
        }
    }

    private fun evolution(source: Int?): EvolutionData {
        val rows = store.artistsByYear(source)
        val topByYear = rows.groupBy { it.year }.values.map { yearRows -> yearRows.maxBy { it.ms } }.sortedBy { it.year }
        val years = topByYear.map { it.year }
        val top5 = rows.groupBy { it.key }
            .map { (key, list) -> Triple(key, list.maxBy { it.ms }.name, list.sumOf { it.ms }) }
            .sortedByDescending { it.third }
            .take(5)
        val series = top5.map { (key, name, _) ->
            val perYear = rows.filter { it.key == key }.associate { it.year to it.ms }
            name to years.map { perYear[it] ?: 0L }
        }
        return EvolutionData(topByYear, years, series, store.discoveredByYear(source))
    }

    // ---------------------------------------------------------------- Actions

    fun import(uri: Uri) {
        viewModelScope.launch { store.import(uri, database) }
    }

    fun clearSpotify() {
        viewModelScope.launch { store.clearSpotify(database) }
    }

    fun dismissImportResult() = store.clearImportState()

    suspend fun export(uri: Uri): Boolean = store.export(uri)

    /** Finds the song on YouTube (only the first time) and returns what the player needs. */
    suspend fun resolveForPlayback(trackId: Long): Pair<String, MediaMetadata?>? = withContext(Dispatchers.IO) {
        val resolved = runCatching { store.resolve(trackId, database) }.getOrNull() ?: return@withContext null
        val metadata = resolved.item?.toMediaMetadata()
            ?: runCatching { database.getSongByIdBlocking(resolved.ytId)?.toMediaMetadata() }.getOrNull()
        resolved.ytId to metadata
    }

    fun artistYtId(key: String): String? = store.artistYtId(key)
}
