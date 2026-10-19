/**
 * Metrolist Plus — fila "Para ti" del Inicio: listas armadas con el historial completo
 * (Spotify + Metrolist). Solo aparece si se importó el historial de Spotify.
 */

package com.metrolist.music.plus.stats

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/** How many unmatched songs to look up on YouTube before starting a list. */
private const val MIN_READY = 15
private const val LIST_SIZE = 50

enum class ForYouKind { HOUR, REDISCOVER, RADIO, ALL_TIME, YEAR }

data class ForYouCard(
    val kind: ForYouKind,
    val year: Int? = null,
    val weekend: Boolean = false,
    val artist: String? = null,
    val tracks: List<PickTrack>,
) {
    val thumbs: List<String> get() = tracks.mapNotNull { it.thumb?.takeIf(String::isNotEmpty) }.distinct().take(4)
}

@HiltViewModel
class PlusForYouViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    private val store by lazy { PlusHistoryStore.get(context) }

    /** Starts from a remembered flag so Home can reserve the row's space without opening the database. */
    private val _visible = MutableStateFlow(PlusHistoryStore.hasSpotifyHint(context))
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _cards = MutableStateFlow<List<ForYouCard>>(emptyList())
    val cards: StateFlow<List<ForYouCard>> = _cards.asStateFlow()

    private var loadedHour = -1

    init {
        viewModelScope.launch {
            val has = withContext(Dispatchers.IO) { runCatching { store.hasSpotify() }.getOrDefault(false) }
            store.rememberHasSpotify(has)
            _visible.value = has
            if (has) refresh()
        }
    }

    /** Recomputes the lists; "Para esta hora" changes every hour. */
    fun refreshIfHourChanged() {
        if (_visible.value && loadedHour != -1 && LocalDateTime.now().hour != loadedHour) refresh()
    }

    private fun refresh() {
        val now = LocalDateTime.now()
        loadedHour = now.hour
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { buildCards(now) { _cards.value = it } }.onFailure { Timber.e(it, "Could not build Para ti") }
        }
    }

    /** Publishes the cards as they are ready; "Redescubre" scans the whole history, so it goes last. */
    private fun buildCards(now: LocalDateTime, publish: (List<ForYouCard>) -> Unit) {
        val today = now.toLocalDate()
        val weekend = now.dayOfWeek.value >= 6
        val all = HistoryFilter(0, Long.MAX_VALUE, null)
        val cards = mutableListOf<ForYouCard>()
        fun add(card: ForYouCard) {
            cards += card
            publish(cards.sortedWith(compareBy<ForYouCard> { it.kind.ordinal }.thenByDescending { it.year ?: 0 }))
        }

        store.forHour(now.hour, weekend, today.minusYears(3).atStartOfDay().toStoredMillis(), 80)
            .takeIf { it.size >= 10 }
            ?.let { add(ForYouCard(ForYouKind.HOUR, weekend = weekend, tracks = it.shuffled().take(LIST_SIZE))) }

        val recent = HistoryFilter(today.minusDays(90).atStartOfDay().toStoredMillis(), Long.MAX_VALUE, null)
        (store.radioSeed(recent) ?: store.radioSeed(all))?.let { (artist, track) ->
            add(ForYouCard(ForYouKind.RADIO, artist = artist, tracks = listOf(track)))
        }

        store.topPicks(all, LIST_SIZE).takeIf { it.isNotEmpty() }
            ?.let { add(ForYouCard(ForYouKind.ALL_TIME, tracks = it)) }

        val firstYear = store.bounds(null)?.let { storedMillisToDate(it.first).year }
        if (firstYear != null) {
            for (year in today.year downTo firstYear) {
                val filter = HistoryFilter(
                    LocalDate.of(year, 1, 1).atStartOfDay().toStoredMillis(),
                    LocalDate.of(year + 1, 1, 1).atStartOfDay().toStoredMillis(),
                    null,
                )
                store.topPicks(filter, LIST_SIZE).takeIf { it.size >= 10 }
                    ?.let { add(ForYouCard(ForYouKind.YEAR, year = year, tracks = it)) }
            }
        }

        store.rediscover(
            oldBefore = today.minusYears(2).atStartOfDay().toStoredMillis(),
            notSince = today.minusMonths(9).atStartOfDay().toStoredMillis(),
            limit = 100,
        ).takeIf { it.size >= 10 }
            ?.let { add(ForYouCard(ForYouKind.REDISCOVER, tracks = it.shuffled().take(LIST_SIZE))) }
    }

    fun play(context: Context, playerConnection: PlayerConnection?, card: ForYouCard, title: String) {
        if (playerConnection == null) return
        viewModelScope.launch {
            if (card.kind == ForYouKind.RADIO) {
                val seed = card.tracks.first()
                val resolved = resolve(seed) ?: run {
                    Toast.makeText(context, R.string.plus_stats_not_found, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                playerConnection.playQueue(YouTubeQueue(endpoint = WatchEndpoint(resolved.id), preloadItem = resolved))
                return@launch
            }

            val missing = card.tracks.count { it.ytId == null }
            if (missing > 0 && card.tracks.size - missing < MIN_READY) {
                Toast.makeText(context, R.string.plus_foryou_preparing, Toast.LENGTH_SHORT).show()
            }
            val items = withContext(Dispatchers.IO) { readyItems(card.tracks) }
            if (items.isEmpty()) {
                Toast.makeText(context, R.string.plus_stats_not_found, Toast.LENGTH_SHORT).show()
                return@launch
            }
            playerConnection.playQueue(ListQueue(title = title, items = items.map { it.toMediaItem() }))
        }
    }

    /**
     * Songs already found on YouTube, plus a few looked up now if there are too few.
     * The rest are skipped so nothing is appended to the queue after it starts.
     */
    private suspend fun readyItems(tracks: List<PickTrack>): List<MediaMetadata> {
        val ready = tracks.count { it.ytId != null }
        var budget = (MIN_READY - ready).coerceAtLeast(0)
        val toLookUp = tracks.filter { it.ytId == null && budget-- > 0 }.map { it.trackId }.toSet()
        val semaphore = Semaphore(3)
        return kotlinx.coroutines.coroutineScope {
            tracks.map { track ->
                async {
                    when {
                        track.ytId != null -> metadataFor(track, track.ytId)
                        track.trackId in toLookUp -> semaphore.withPermit { resolve(track) }
                        else -> null
                    }
                }
            }.awaitAll().filterNotNull().distinctBy { it.id }
        }
    }

    private suspend fun resolve(track: PickTrack): MediaMetadata? {
        if (track.ytId != null) return metadataFor(track, track.ytId)
        val resolved = runCatching { store.resolve(track.trackId, database) }.getOrNull() ?: return null
        return resolved.item?.toMediaMetadata() ?: metadataFor(track, resolved.ytId)
    }

    private suspend fun metadataFor(track: PickTrack, ytId: String): MediaMetadata = withContext(Dispatchers.IO) {
        runCatching { database.getSongByIdBlocking(ytId)?.toMediaMetadata() }.getOrNull()
            ?: MediaMetadata(
                id = ytId,
                title = track.title,
                artists = listOf(MediaMetadata.Artist(id = null, name = track.artist)),
                duration = -1,
                thumbnailUrl = track.thumb,
            )
    }
}

@Composable
fun PlusForYouRow(viewModel: PlusForYouViewModel = hiltViewModel()) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    if (!visible) return
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val cards by viewModel.cards.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshIfHourChanged()
            delay(60_000)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.plus_foryou_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            // Fixed height so Home doesn't jump while the lists load.
            modifier = Modifier.height(222.dp),
        ) {
            items(cards, key = { "${it.kind}-${it.year}" }) { card ->
                val title = cardTitle(card)
                ForYouCardView(card, title, cardSubtitle(card)) {
                    viewModel.play(context, playerConnection, card, title)
                }
            }
        }
    }
}

@Composable
private fun cardTitle(card: ForYouCard): String = when (card.kind) {
    ForYouKind.HOUR -> stringResource(R.string.plus_foryou_hour)
    ForYouKind.REDISCOVER -> stringResource(R.string.plus_foryou_rediscover)
    ForYouKind.RADIO -> stringResource(R.string.plus_foryou_radio, card.artist.orEmpty())
    ForYouKind.ALL_TIME -> stringResource(R.string.plus_foryou_all_time)
    ForYouKind.YEAR -> stringResource(R.string.plus_foryou_year, card.year.toString())
}

@Composable
private fun cardSubtitle(card: ForYouCard): String = when (card.kind) {
    ForYouKind.HOUR -> stringResource(
        if (card.weekend) R.string.plus_foryou_hour_weekend else R.string.plus_foryou_hour_weekday,
    )
    ForYouKind.REDISCOVER -> stringResource(R.string.plus_foryou_rediscover_description)
    ForYouKind.RADIO -> stringResource(R.string.plus_foryou_radio_description)
    ForYouKind.ALL_TIME -> stringResource(R.string.plus_foryou_all_time_description)
    ForYouKind.YEAR -> stringResource(R.string.plus_foryou_year_description, card.year.toString())
}

@Composable
private fun ForYouCardView(card: ForYouCard, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    Column(
        Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .size(150.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(colors)),
        ) {
            val thumbs = card.thumbs
            when {
                thumbs.size >= 4 -> Column {
                    thumbs.chunked(2).forEach { row ->
                        Row {
                            row.forEach { thumb ->
                                AsyncImage(
                                    model = thumb,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(75.dp),
                                )
                            }
                        }
                    }
                }
                thumbs.isNotEmpty() -> AsyncImage(
                    model = thumbs.first(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))),
            )
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(
                        when (card.kind) {
                            ForYouKind.HOUR -> R.drawable.history
                            ForYouKind.RADIO -> R.drawable.radio
                            else -> R.drawable.play
                        },
                    ),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
