/**
 * Metrolist Plus — historial completo (Spotify + Metrolist), día por día y con carga por páginas.
 */

package com.metrolist.music.plus.stats

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

const val PlusHistoryRoute = "plus/stats/history"

@HiltViewModel
class PlusHistoryListViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    private val database: MusicDatabase,
) : ViewModel() {
    private val store = PlusHistoryStore.get(context)

    private val _rows = MutableStateFlow<List<HistoryRow>>(emptyList())
    val rows: StateFlow<List<HistoryRow>> = _rows.asStateFlow()

    private val _source = MutableStateFlow<Int?>(null)
    val source: StateFlow<Int?> = _source.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val hasSpotify: Boolean get() = runCatching { store.hasSpotify() }.getOrDefault(false)

    private var endReached = false
    private var generation = 0

    init {
        loadMore()
    }

    fun setSource(source: Int?) {
        if (source == _source.value) return
        generation++
        _source.value = source
        _rows.value = emptyList()
        endReached = false
        _loading.value = false
        loadMore()
    }

    fun loadMore() {
        if (_loading.value || endReached) return
        _loading.value = true
        val current = generation
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { store.history(_source.value, _rows.value.lastOrNull()) }
                if (current != generation) return@launch
                if (page.isEmpty()) endReached = true
                _rows.value = _rows.value + page
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Could not load history page")
                endReached = true
            } finally {
                if (current == generation) _loading.value = false
            }
        }
    }

    suspend fun resolveForPlayback(trackId: Long): Pair<String, MediaMetadata?>? = withContext(Dispatchers.IO) {
        val resolved = runCatching { store.resolve(trackId) }.getOrNull() ?: return@withContext null
        val metadata = resolved.item?.toMediaMetadata()
            ?: runCatching { database.getSongByIdBlocking(resolved.ytId)?.toMediaMetadata() }.getOrNull()
        resolved.ytId to metadata
    }
}

private sealed interface HistoryItem {
    data class Header(val day: String) : HistoryItem
    data class Play(val row: HistoryRow) : HistoryItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlusHistoryListScreen(
    navController: NavController,
    viewModel: PlusHistoryListViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val showSource = remember { viewModel.hasSpotify }
    val dayFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    val items = remember(rows) {
        buildList<HistoryItem> {
            var lastDay: String? = null
            rows.forEach { row ->
                val day = storedMillisToDate(row.ts).toLocalDate().format(dayFormat)
                if (day != lastDay) {
                    add(HistoryItem.Header(day))
                    lastDay = day
                }
                add(HistoryItem.Play(row))
            }
        }
    }

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 20
        }
    }
    LaunchedEffect(nearEnd, rows.size) {
        if (nearEnd) viewModel.loadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current),
    ) {
        if (showSource) {
            item(key = "source") {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        null to R.string.plus_stats_source_all,
                        SOURCE_SPOTIFY to R.string.plus_stats_source_spotify,
                        SOURCE_METROLIST to R.string.plus_stats_source_metrolist,
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = source == value,
                            onClick = { viewModel.setSource(value) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }
        }

        items(
            items = items,
            key = { item ->
                when (item) {
                    is HistoryItem.Header -> "h:${item.day}"
                    is HistoryItem.Play -> "p:${item.row.id}"
                }
            },
        ) { item ->
            when (item) {
                is HistoryItem.Header -> Text(
                    item.day.replaceFirstChar { it.titlecase() },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                is HistoryItem.Play -> {
                    val row = item.row
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                playHistoryTrack(context, scope, playerConnection, row.trackId, row.thumb != null, viewModel::resolveForPlayback)
                            }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            if (!row.thumb.isNullOrEmpty()) {
                                AsyncImage(model = row.thumb, contentDescription = null, modifier = Modifier.size(44.dp))
                            }
                        }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                row.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(72.dp)) {
                            Text(
                                storedMillisToDate(row.ts).toLocalTime().format(timeFormat),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                stringResource(
                                    if (row.source == SOURCE_SPOTIFY) R.string.plus_stats_source_spotify else R.string.plus_stats_source_metrolist,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (loading) {
            item(key = "loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.plus_stats_full_history)) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}
