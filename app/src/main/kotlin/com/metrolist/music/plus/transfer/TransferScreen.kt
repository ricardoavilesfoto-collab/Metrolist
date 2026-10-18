/**
 * Metrolist Plus — pantalla "Transferir música" (se abre desde el menú de la foto de perfil).
 */

package com.metrolist.music.plus.transfer

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.YtmSyncKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

const val PlusTransferRoute = "plus/transfer"

@Composable
fun plusTransferAccountItem(onClick: () -> Unit) = Material3SettingsItem(
    icon = painterResource(R.drawable.plus_transfer),
    title = { Text(stringResource(R.string.plus_transfer_title)) },
    description = { Text(stringResource(R.string.plus_transfer_menu_description)) },
    onClick = onClick,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlusTransferScreen(navController: NavController) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val database = LocalDatabase.current
    val syncUtils = LocalSyncUtils.current
    val scope = rememberCoroutineScope()
    val progress by PlusTransferManager.progress.collectAsStateWithLifecycle()

    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val ytmSync by rememberPreference(YtmSyncKey, true)
    val isLoggedIn = remember(innerTubeCookie) { "SAPISID" in parseCookieString(innerTubeCookie) }
    var syncWithYouTube by rememberSaveable { mutableStateOf(true) }
    val canSync = isLoggedIn && ytmSync

    val sources = remember { mutableStateListOf<TransferSource>() }
    var linkText by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    fun addSources(new: List<TransferSource>) {
        new.forEach { source -> if (sources.none { it.key == source.key }) sources += source }
    }

    val fileError = stringResource(R.string.plus_transfer_file_error)
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            uris.forEach { uri ->
                runCatching { TransferSources.fromCsv(context, uri) }
                    .onSuccess(::addSources)
                    .onFailure { Toast.makeText(context, fileError, Toast.LENGTH_LONG).show() }
            }
            loading = false
        }
    }

    val linkErrorTemplate = stringResource(R.string.plus_transfer_link_error, "%s")
    fun loadLinks() {
        val links = TransferSources.extractLinks(linkText)
        if (links.isEmpty()) return
        scope.launch {
            loading = true
            val failed = mutableListOf<String>()
            links.forEach { link ->
                runCatching { TransferSources.fromLink(link) }
                    .onSuccess { addSources(listOf(it)) }
                    .onFailure { failed += link }
            }
            linkText = failed.joinToString("\n")
            failed.forEach { Toast.makeText(context, linkErrorTemplate.format(it), Toast.LENGTH_LONG).show() }
            loading = false
        }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.plus_transfer_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TransferCard(title = stringResource(R.string.plus_transfer_from_link)) {
            OutlinedTextField(
                value = linkText,
                onValueChange = { linkText = it },
                placeholder = { Text("https://open.spotify.com/playlist/…") },
                label = { Text(stringResource(R.string.plus_transfer_link_hint)) },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = ::loadLinks,
                enabled = !loading && linkText.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.plus_transfer_add))
            }
        }

        TransferCard(title = stringResource(R.string.plus_transfer_from_file)) {
            Text(
                text = stringResource(R.string.plus_transfer_file_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { uriHandler.openUri("https://exportify.app") }) { Text("Exportify") }
                TextButton(onClick = { uriHandler.openUri("https://www.tunemymusic.com/transfer") }) { Text("TuneMyMusic") }
            }
            OutlinedButton(
                onClick = {
                    fileLauncher.launch(
                        arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain", "application/octet-stream"),
                    )
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.plus_transfer_choose_files))
            }
        }

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        if (sources.isNotEmpty()) {
            TransferCard(title = stringResource(R.string.plus_transfer_ready)) {
                sources.forEachIndexed { index, source ->
                    SourceRow(
                        source = source,
                        onDestinationChange = { sources[index] = source.copy(destination = it) },
                        onRemove = { sources.removeAt(index) },
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.plus_transfer_sync), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.plus_transfer_sync_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = canSync && syncWithYouTube,
                        enabled = canSync,
                        onCheckedChange = { syncWithYouTube = it },
                    )
                }

                Button(
                    onClick = {
                        PlusTransferManager.start(sources.toList(), database, syncUtils, canSync && syncWithYouTube)
                        sources.clear()
                    },
                    enabled = !progress.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.plus_transfer_start, sources.sumOf { it.tracks.size }))
                }
            }
        }

        if (progress.running || progress.results.isNotEmpty()) {
            TransferCard(title = stringResource(R.string.plus_transfer_results)) {
                if (progress.running) {
                    Text(
                        stringResource(
                            R.string.plus_transfer_running,
                            progress.currentName.orEmpty(),
                            progress.done,
                            progress.total,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.plus_transfer_background_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                progress.results.forEach { result -> ResultRow(result) }
                TextButton(
                    onClick = { if (progress.running) PlusTransferManager.cancel() else PlusTransferManager.clearResults() },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(if (progress.running) R.string.plus_transfer_cancel else R.string.plus_transfer_clear))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.plus_transfer_title)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

@Composable
private fun TransferCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SourceRow(
    source: TransferSource,
    onDestinationChange: (TransferDestination) -> Unit,
    onRemove: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.queue_music),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${source.platform.label()} · " + stringResource(R.string.plus_transfer_songs, source.tracks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.IconButton(onClick = onRemove) {
                Icon(
                    painterResource(R.drawable.close),
                    contentDescription = stringResource(R.string.plus_transfer_remove),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 36.dp),
        ) {
            FilterChip(
                selected = source.destination == TransferDestination.NEW_PLAYLIST,
                onClick = { onDestinationChange(TransferDestination.NEW_PLAYLIST) },
                label = { Text(stringResource(R.string.plus_transfer_to_playlist)) },
            )
            FilterChip(
                selected = source.destination == TransferDestination.LIKED_SONGS,
                onClick = { onDestinationChange(TransferDestination.LIKED_SONGS) },
                label = { Text(stringResource(R.string.plus_transfer_to_liked)) },
            )
        }
        if (source.maybeTruncated) {
            Text(
                text = stringResource(R.string.plus_transfer_truncated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 36.dp),
            )
        }
    }
}

@Composable
private fun ResultRow(result: TransferResult) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(result.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = stringResource(R.string.plus_transfer_result, result.found, result.total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result.missing.isNotEmpty()) {
            val missing = result.missing.joinToString(" · ") { it.label() }
            Text(
                text = stringResource(R.string.plus_transfer_not_found, missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (result.doubtful.isNotEmpty()) {
            Text(
                text = stringResource(R.string.plus_transfer_doubtful, result.doubtful.joinToString(" · ") { it.label() }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (!expanded && result.missing.size + result.doubtful.size > 3) {
            TextButton(onClick = { expanded = true }) { Text("…") }
        }
    }
}

private fun TransferTrack.label() = listOfNotNull(title, artists.firstOrNull()).joinToString(" – ")

private fun TransferPlatform.label() = when (this) {
    TransferPlatform.SPOTIFY -> "Spotify"
    TransferPlatform.DEEZER -> "Deezer"
    TransferPlatform.FILE -> "CSV"
}
