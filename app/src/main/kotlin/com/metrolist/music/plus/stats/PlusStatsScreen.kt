/**
 * Metrolist Plus — pantalla "Estadísticas Plus": historial de Spotify + Metrolist con gráficas.
 * Se abre desde el ícono de estadísticas; la pantalla original queda en el menú como "Estadísticas clásicas".
 */

package com.metrolist.music.plus.stats

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

const val PlusStatsRoute = "plus/stats"

private enum class TopTab { ARTISTS, SONGS, ALBUMS }

/** "1,234 h" or "45 min". */
fun formatListening(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "%,d h".format(minutes / 60) else "$minutes min"
}

/** Plays a history track: finds it on YouTube Music the first time, then plays it. */
fun playHistoryTrack(
    context: android.content.Context,
    scope: CoroutineScope,
    playerConnection: com.metrolist.music.playback.PlayerConnection?,
    trackId: Long,
    alreadyMatched: Boolean,
    resolve: suspend (Long) -> Pair<String, com.metrolist.music.models.MediaMetadata?>?,
) {
    if (playerConnection == null) return
    if (!alreadyMatched) Toast.makeText(context, R.string.plus_stats_searching, Toast.LENGTH_SHORT).show()
    scope.launch {
        val resolved = resolve(trackId)
        if (resolved == null) {
            Toast.makeText(context, R.string.plus_stats_not_found, Toast.LENGTH_SHORT).show()
            return@launch
        }
        playerConnection.playQueue(YouTubeQueue(endpoint = WatchEndpoint(resolved.first), preloadItem = resolved.second))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlusStatsScreen(
    navController: NavController,
    viewModel: PlusStatsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current

    val data by viewModel.data.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val years by viewModel.years.collectAsStateWithLifecycle()
    val hasSpotify by viewModel.hasSpotify.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var topTab by rememberSaveable { mutableStateOf(TopTab.ARTISTS) }
    var showAllTops by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }
    val openPicker = { picker.launch(arrayOf("*/*")) }

    LazyColumn(
        modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "filters") {
            Filters(
                period = period,
                years = years,
                source = source,
                showSource = hasSpotify,
                onPeriod = { viewModel.period.value = it },
                onSource = { viewModel.source.value = it },
            )
        }

        if (!hasSpotify && !loading) {
            item(key = "import") {
                StatsCard(stringResource(R.string.plus_stats_import)) {
                    Text(
                        stringResource(R.string.plus_stats_import_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = openPicker, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.plus_stats_import))
                    }
                }
            }
        }

        if (loading) {
            item(key = "loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
        }

        val stats = data
        if (stats != null && stats.summary.totalMs == 0L && !loading) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.plus_stats_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (stats != null && stats.summary.totalMs > 0) {
            item(key = "summary") { SummaryCard(stats.summary, period) }

            item(key = "bars") {
                StatsCard(stringResource(R.string.plus_stats_time_listened)) {
                    BarChart(stats.bars, onOpen = { bar -> bar.drillTo?.let { viewModel.period.value = it } })
                    if (stats.bars.any { it.drillTo != null }) {
                        Hint(stringResource(R.string.plus_stats_bars_hint))
                    }
                }
            }

            item(key = "heatmap") { WhenCard(stats.heatmap) }

            item(key = "tops") {
                StatsCard(stringResource(R.string.plus_stats_tops)) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TopTab.entries.forEach { tab ->
                            FilterChip(
                                selected = topTab == tab,
                                onClick = { topTab = tab },
                                label = {
                                    Text(
                                        stringResource(
                                            when (tab) {
                                                TopTab.ARTISTS -> R.string.plus_stats_artists
                                                TopTab.SONGS -> R.string.plus_stats_songs
                                                TopTab.ALBUMS -> R.string.plus_stats_albums
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    val entries = when (topTab) {
                        TopTab.ARTISTS -> stats.topArtists
                        TopTab.SONGS -> stats.topTracks
                        TopTab.ALBUMS -> stats.topAlbums
                    }
                    val visible = if (showAllTops) entries else entries.take(10)
                    val maxValue = entries.maxOfOrNull { if (topTab == TopTab.SONGS) it.plays else it.ms } ?: 1L
                    visible.forEachIndexed { index, entry ->
                        TopRow(
                            rank = index + 1,
                            entry = entry,
                            shape = if (topTab == TopTab.ARTISTS) CircleShape else RoundedCornerShape(6.dp),
                            fraction = (if (topTab == TopTab.SONGS) entry.plays else entry.ms).toFloat() / maxValue.coerceAtLeast(1),
                            onClick = when (topTab) {
                                TopTab.SONGS -> entry.trackId?.let { trackId ->
                                    {
                                        playHistoryTrack(context, scope, playerConnection, trackId, entry.ytId != null, viewModel::resolveForPlayback)
                                    }
                                }
                                TopTab.ARTISTS -> {
                                    {
                                        val ytId = entry.ytId ?: viewModel.artistYtId(entry.key)
                                        if (ytId != null) navController.navigate("artist/$ytId")
                                    }
                                }
                                TopTab.ALBUMS -> null
                            },
                        )
                    }
                    if (entries.size > 10) {
                        TextButton(onClick = { showAllTops = !showAllTops }, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(if (showAllTops) R.string.plus_stats_show_less else R.string.plus_stats_show_more))
                        }
                    }
                }
            }

            stats.evolution?.let { evolution ->
                item(key = "evolution") { EvolutionCard(evolution) }
            }

            item(key = "habits") { HabitsCard(stats.habits, source) }

            item(key = "history") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(CardDefaults.shape)
                        .clickable { navController.navigate(PlusHistoryRoute) },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.history), contentDescription = null)
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(stringResource(R.string.plus_stats_full_history), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.plus_stats_full_history_description),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item(key = "bottom") { Spacer(Modifier.height(16.dp)) }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.plus_stats_title)) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
        actions = {
            Box {
                androidx.compose.material3.IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.more_vert), contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.plus_stats_import)) },
                        leadingIcon = { Icon(painterResource(R.drawable.upload), contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            openPicker()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.plus_stats_classic)) },
                        leadingIcon = { Icon(painterResource(R.drawable.stats), contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            navController.navigate("stats")
                        },
                    )
                    if (hasSpotify) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.plus_stats_clear)) },
                            leadingIcon = { Icon(painterResource(R.drawable.delete), contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                confirmClear = true
                            },
                        )
                    }
                }
            }
        },
    )

    ImportDialog(importState, onDismiss = viewModel::dismissImportResult)

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.plus_stats_clear)) },
            text = { Text(stringResource(R.string.plus_stats_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearSpotify()
                }) { Text(stringResource(R.string.plus_stats_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun Filters(
    period: StatsPeriod,
    years: List<Int>,
    source: Int?,
    showSource: Boolean,
    onPeriod: (StatsPeriod) -> Unit,
    onSource: (Int?) -> Unit,
) {
    var yearMenu by remember { mutableStateOf(false) }
    var monthMenu by remember { mutableStateOf(false) }
    val selectedYear = when (period) {
        is StatsPeriod.Year -> period.year
        is StatsPeriod.Month -> period.year
        else -> null
    }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = period == StatsPeriod.All,
                onClick = { onPeriod(StatsPeriod.All) },
                label = { Text(stringResource(R.string.plus_stats_all)) },
            )
            FilterChip(
                selected = period == StatsPeriod.Last30,
                onClick = { onPeriod(StatsPeriod.Last30) },
                label = { Text(stringResource(R.string.plus_stats_last30)) },
            )
            Box {
                FilterChip(
                    selected = selectedYear != null,
                    onClick = { yearMenu = true },
                    label = { Text(selectedYear?.toString() ?: stringResource(R.string.plus_stats_year)) },
                    trailingIcon = { Icon(painterResource(R.drawable.expand_more), contentDescription = null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
                    years.forEach { year ->
                        DropdownMenuItem(
                            text = { Text(year.toString()) },
                            onClick = {
                                yearMenu = false
                                onPeriod(StatsPeriod.Year(year))
                            },
                        )
                    }
                }
            }
            if (selectedYear != null) {
                Box {
                    FilterChip(
                        selected = period is StatsPeriod.Month,
                        onClick = { monthMenu = true },
                        label = {
                            Text((period as? StatsPeriod.Month)?.let { monthLabel(it.month) } ?: stringResource(R.string.plus_stats_month))
                        },
                        trailingIcon = { Icon(painterResource(R.drawable.expand_more), contentDescription = null, Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = monthMenu, onDismissRequest = { monthMenu = false }) {
                        (1..12).forEach { month ->
                            DropdownMenuItem(
                                text = { Text(monthLabel(month)) },
                                onClick = {
                                    monthMenu = false
                                    onPeriod(StatsPeriod.Month(selectedYear, month))
                                },
                            )
                        }
                    }
                }
            }
        }
        if (showSource) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    null to R.string.plus_stats_source_all,
                    SOURCE_SPOTIFY to R.string.plus_stats_source_spotify,
                    SOURCE_METROLIST to R.string.plus_stats_source_metrolist,
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = source == value,
                        onClick = { onSource(value) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        detail?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TileGrid(tiles: List<@Composable (Modifier) -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: StatsSummary, period: StatsPeriod) {
    val days = if (summary.firstTs != null && summary.lastTs != null) {
        val span = when (period) {
            StatsPeriod.Last30 -> 30L
            else -> (summary.lastTs - summary.firstTs) / 86_400_000 + 1
        }
        span.coerceAtLeast(1)
    } else {
        1L
    }
    val hours = summary.totalMs / 3_600_000
    StatsCard(stringResource(R.string.plus_stats_summary)) {
        TileGrid(
            listOf<@Composable (Modifier) -> Unit>(
                { m -> Tile("%,d".format(hours), stringResource(R.string.plus_stats_hours), m) },
                { m -> Tile("%,d".format(hours / 24), stringResource(R.string.plus_stats_days), m) },
                { m -> Tile("%,d".format(summary.plays), stringResource(R.string.plus_stats_plays), m) },
                { m -> Tile("%,d".format(summary.tracks), stringResource(R.string.plus_stats_songs), m) },
                { m -> Tile("%,d".format(summary.artists), stringResource(R.string.plus_stats_artists), m) },
                { m -> Tile(formatListening(summary.totalMs / days), stringResource(R.string.plus_stats_daily_average), m) },
            ),
        )
    }
}

@Composable
private fun WhenCard(grid: Array<LongArray>) {
    val locale = Locale.getDefault()
    val dayLabels = remember(locale) {
        DayOfWeek.values().map { it.getDisplayName(JavaTextStyle.NARROW, locale) }
    }
    val favoriteHour = (0 until 24).maxByOrNull { hour -> grid.sumOf { it[hour] } } ?: 0
    val favoriteDay = (0 until 7).maxByOrNull { day -> grid[day].sum() } ?: 0
    StatsCard(stringResource(R.string.plus_stats_when)) {
        HeatmapGrid(grid, dayLabels)
        Text(
            stringResource(
                R.string.plus_stats_favorite_hour,
                "%02d:00".format(favoriteHour),
                DayOfWeek.of(favoriteDay + 1).getDisplayName(JavaTextStyle.FULL, locale),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TopRow(rank: Int, entry: TopEntry, shape: Shape, fraction: Float, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            rank.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Box(
            Modifier
                .size(48.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (!entry.thumb.isNullOrEmpty()) {
                AsyncImage(model = entry.thumb, contentDescription = null, modifier = Modifier.size(48.dp))
            }
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = stringResource(R.string.plus_stats_entry_detail, "%,d".format(entry.plays), formatListening(entry.ms))
            Text(
                listOfNotNull(entry.subtitle, detail).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProportionBar(fraction, Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun EvolutionCard(evolution: EvolutionData) {
    StatsCard(stringResource(R.string.plus_stats_evolution)) {
        Text(stringResource(R.string.plus_stats_top_per_year), style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            evolution.topByYear.asReversed().forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.year.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(48.dp),
                    )
                    Text(
                        row.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatListening(row.ms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (evolution.years.size >= 2 && evolution.series.isNotEmpty()) {
            Text(stringResource(R.string.plus_stats_top5_years), style = MaterialTheme.typography.titleSmall)
            LineChart(evolution.series, evolution.years.map { "'" + (it % 100).toString().padStart(2, '0') })
        }
        if (evolution.discovered.isNotEmpty()) {
            Text(stringResource(R.string.plus_stats_discovered), style = MaterialTheme.typography.titleSmall)
            BarChart(
                bars = evolution.discovered.toSortedMap().map { (year, count) -> ChartBar(year.toString(), count, null) },
                onOpen = {},
                valueText = { count -> if (count >= 1000) "%.1fk".format(count / 1000.0) else count.toString() },
            )
        }
    }
}

@Composable
private fun HabitsCard(habits: Habits, source: Int?) {
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    StatsCard(stringResource(R.string.plus_stats_habits)) {
        val tiles = buildList<@Composable (Modifier) -> Unit> {
            habits.skipRate?.let { rate ->
                add({ m -> Tile("%.0f%%".format(rate * 100), stringResource(R.string.plus_stats_skipped), m) })
            }
            habits.shuffleRate?.let { rate ->
                add({ m -> Tile("%.0f%%".format(rate * 100), stringResource(R.string.plus_stats_shuffle), m) })
            }
            add({ m ->
                Tile(
                    stringResource(R.string.plus_stats_n_days, habits.longestStreak),
                    stringResource(R.string.plus_stats_longest_streak),
                    m,
                    habits.streakStart?.let { stringResource(R.string.plus_stats_since, storedMillisToDate(it).toLocalDate().format(dateFormat)) },
                )
            })
            add({ m ->
                Tile(stringResource(R.string.plus_stats_n_days, habits.currentStreak), stringResource(R.string.plus_stats_current_streak), m)
            })
        }
        TileGrid(tiles)
        if (habits.skipRate != null && source == null) Hint(stringResource(R.string.plus_stats_habits_note))
        if (habits.platforms.isNotEmpty()) {
            Text(stringResource(R.string.plus_stats_platforms), style = MaterialTheme.typography.titleSmall)
            val other = stringResource(R.string.plus_stats_platform_other)
            val speakers = stringResource(R.string.plus_stats_platform_speakers)
            HorizontalBars(
                items = habits.platforms.map { (platform, ms) ->
                    when (platform) {
                        "android" -> "Android"
                        "windows" -> "Windows"
                        "ios" -> "iPhone / iPad"
                        "mac" -> "Mac"
                        "web" -> "Web"
                        "metrolist" -> "Metrolist"
                        "bocina/tv" -> speakers
                        else -> other
                    } to ms
                }.groupBy({ it.first }, { it.second }).map { (name, values) -> name to values.sum() }.sortedByDescending { it.second },
                valueText = ::formatListening,
            )
        }
    }
}

@Composable
private fun ImportDialog(state: ImportState?, onDismiss: () -> Unit) {
    when (state) {
        null -> Unit
        is ImportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.plus_stats_importing)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            when (state.step) {
                                PlusHistoryStore.STEP_COPY -> R.string.plus_stats_step_copy
                                PlusHistoryStore.STEP_TIME -> R.string.plus_stats_step_time
                                PlusHistoryStore.STEP_NAMES -> R.string.plus_stats_step_names
                                else -> R.string.plus_stats_step_index
                            },
                        ),
                    )
                    val fraction = state.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
        )
        is ImportState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.plus_stats_import)) },
            text = {
                Text(
                    stringResource(
                        R.string.plus_stats_import_done,
                        "%,d".format(state.plays),
                        "%,d".format(state.tracks),
                        "%,d".format(state.hours),
                    ),
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        )
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.plus_stats_import_failed)) },
            text = { if (state.notHistoryFile) Text(stringResource(R.string.plus_stats_import_wrong_file)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}
