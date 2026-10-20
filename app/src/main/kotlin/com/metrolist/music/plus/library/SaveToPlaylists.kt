/**
 * Metrolist Plus — "Guardar en playlists": reemplaza la lista de "Agregar a playlist" del original.
 *
 * - Se marcan varias playlists con casillas y se guarda en todas con un solo botón.
 * - Buscador por nombre y los mismos chips de orden del original (se guardan en las mismas preferencias).
 * - Orden de la lista: usadas recientemente, fijadas, carpetas (se abren al tocarlas) y el resto.
 * - Las playlists que ya tienen la canción aparecen marcadas y bloqueadas. Al guardar varias canciones a la vez,
 *   las que ya estaban en una playlist se omiten en esa playlist.
 */

package com.metrolist.music.plus.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.AddToPlaylistPosition
import com.metrolist.music.constants.AddToPlaylistPositionKey
import com.metrolist.music.constants.AddToPlaylistSortDescendingKey
import com.metrolist.music.constants.AddToPlaylistSortTypeKey
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.PlaylistSortType
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.ui.component.CreatePlaylistDialog
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.PlaylistListItem
import com.metrolist.music.ui.menu.playlistDuplicatesBatched
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Switch kept as a function so the original dialog code below the call stays reachable. */
fun plusSaveToPlaylistsEnabled() = true

private val RecentTargetsKey = stringPreferencesKey("plusRecentAddToPlaylist")
private const val MAX_RECENTS = 5

@Composable
fun PlusSaveToPlaylistsDialog(
    playlists: List<Playlist>,
    allowSyncing: Boolean,
    initialTextFieldValue: String?,
    onGetSong: suspend () -> List<String>,
    onGetSongIds: (suspend () -> List<String>)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val syncUtils = LocalSyncUtils.current
    val scope = rememberCoroutineScope()

    val (addPosition) = rememberEnumPreference(AddToPlaylistPositionKey, AddToPlaylistPosition.BEGINNING)
    val (sortType, onSortTypeChange) = rememberEnumPreference(AddToPlaylistSortTypeKey, PlaylistSortType.NAME)
    val (sortDescending, onSortDescendingChange) = rememberPreference(AddToPlaylistSortDescendingKey, false)
    val organization by remember { context.libraryOrganizationFlow() }
        .collectAsStateWithLifecycle(LibraryOrganization())
    val recentKeys by remember {
        context.dataStore.data.map { prefs -> prefs[RecentTargetsKey]?.split('\n')?.filter(String::isNotBlank).orEmpty() }
    }.collectAsStateWithLifecycle(emptyList())

    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var openFolders by remember { mutableStateOf(emptySet<String>()) }
    var alreadyIn by remember { mutableStateOf(emptySet<String>()) }
    var saving by remember { mutableStateOf(false) }
    var showCreatePlaylist by rememberSaveable { mutableStateOf(false) }

    val editable = remember(playlists) { playlists.filter { it.playlist.isEditable } }

    LaunchedEffect(editable) {
        val ids = withContext(Dispatchers.IO) { onGetSongIds?.invoke() ?: onGetSong() }
        if (ids.isEmpty()) return@LaunchedEffect
        alreadyIn = withContext(Dispatchers.IO) {
            editable.filter { database.playlistDuplicatesBatched(it.id, ids).size >= ids.distinct().size }
                .mapTo(HashSet()) { it.id }
        }
    }

    fun save() {
        if (selected.isEmpty() || saving) return
        saving = true
        val targets = editable.filter { it.id in selected }
        scope.launch(Dispatchers.IO) {
            val songIds = onGetSong()
            targets.forEach { playlist ->
                val duplicates = database.playlistDuplicatesBatched(playlist.id, songIds).toSet()
                val toAdd = songIds.filterNot { it in duplicates }
                if (toAdd.isEmpty()) return@forEach
                database.addSongsToPlaylist(playlist, toAdd.map { it to null }, prepend = addPosition.prepend)
                playlist.playlist.browseId?.let { syncUtils.scheduleAddToPlaylist(it, playlist.id, toAdd) }
            }
            val usedKeys = targets.map { it.organizationKey }
            context.safeDataStoreEdit { prefs ->
                val previous = prefs[RecentTargetsKey]?.split('\n').orEmpty()
                prefs[RecentTargetsKey] = (usedKeys + (previous - usedKeys.toSet())).take(MAX_RECENTS).joinToString("\n")
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.resources.getQuantityString(R.plurals.plus_saved_to_playlists, targets.size, targets.size),
                    Toast.LENGTH_SHORT,
                ).show()
                onDismiss()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 32.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .imePadding(),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 20.dp),
                ) {
                    Text(
                        text = stringResource(R.string.plus_save_to_playlists),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showCreatePlaylist = true }) {
                        Icon(painterResource(R.drawable.add), null, Modifier.size(18.dp))
                        Text(stringResource(R.string.plus_new_playlist), modifier = Modifier.padding(start = 4.dp))
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.plus_search_playlists)) },
                    leadingIcon = { Icon(painterResource(R.drawable.search), null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.close), null) }
                        }
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    PlaylistSortType.entries.forEach { type ->
                        FilterChip(
                            selected = sortType == type,
                            onClick = {
                                if (sortType == type) onSortDescendingChange(!sortDescending) else onSortTypeChange(type)
                            },
                            label = { Text(stringResource(type.labelRes())) },
                            trailingIcon = if (sortType == type) {
                                {
                                    Icon(
                                        painterResource(if (sortDescending) R.drawable.arrow_downward else R.drawable.arrow_upward),
                                        null,
                                        Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                    val toggle: (String) -> Unit = { id -> selected = if (id in selected) selected - id else selected + id }
                    fun rowFor(list: List<Playlist>, keyPrefix: String) =
                        playlistRows(list, keyPrefix, alreadyIn, selected, toggle)

                    val search = query.trim()
                    if (search.isNotEmpty()) {
                        rowFor(editable.filter { it.playlist.name.contains(search, ignoreCase = true) }, "s:")
                    } else {
                        val byKey = editable.associateBy { it.organizationKey }
                        val recents = recentKeys.mapNotNull(byKey::get)
                        val pinned = organization.pinnedKeys.mapNotNull(byKey::get)
                        val folders = organization.folders
                            .map { folder -> folder to folder.playlistKeys.mapNotNull(byKey::get) }
                            .filter { (_, list) -> list.isNotEmpty() }
                            .sortedBy { (folder, _) -> folder.name.lowercase() }
                        val inFolders = organization.folders.flatMap { it.playlistKeys }.toSet()
                        val rest = editable.filter {
                            it.organizationKey !in inFolders && !organization.isPinned(it.organizationKey)
                        }

                        if (recents.isNotEmpty()) {
                            sectionHeader(R.string.plus_recent_playlists)
                            rowFor(recents, "r:")
                        }
                        if (pinned.isNotEmpty()) {
                            sectionHeader(R.string.plus_pinned_playlists)
                            rowFor(pinned, "p:")
                        }
                        if (folders.isNotEmpty()) {
                            sectionHeader(R.string.plus_folders)
                            folders.forEach { (folder, list) ->
                                val open = folder.id in openFolders
                                item(key = "f:" + folder.id) {
                                    FolderRow(
                                        name = folder.name,
                                        count = list.size,
                                        selectedCount = list.count { it.id in selected },
                                        open = open,
                                        onClick = {
                                            openFolders = if (open) openFolders - folder.id else openFolders + folder.id
                                        },
                                    )
                                }
                                if (open) rowFor(list, "f:${folder.id}:")
                            }
                        }
                        if (rest.isNotEmpty()) {
                            if (recents.isNotEmpty() || pinned.isNotEmpty() || folders.isNotEmpty()) {
                                sectionHeader(R.string.plus_other_playlists)
                            }
                            rowFor(rest, "o:")
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    Box(Modifier.weight(1f))
                    Button(onClick = { save() }, enabled = selected.isNotEmpty() && !saving) {
                        Text(
                            if (selected.isEmpty()) {
                                stringResource(R.string.plus_save)
                            } else {
                                stringResource(R.string.plus_save_count, selected.size)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylist = false },
            initialTextFieldValue = initialTextFieldValue,
            allowSyncing = allowSyncing,
        )
    }
}

private fun PlaylistSortType.labelRes() = when (this) {
    PlaylistSortType.CREATE_DATE -> R.string.sort_by_create_date
    PlaylistSortType.NAME -> R.string.sort_by_name
    PlaylistSortType.SONG_COUNT -> R.string.sort_by_song_count
    PlaylistSortType.LAST_UPDATED -> R.string.sort_by_last_updated
}

private fun LazyListScope.playlistRows(
    list: List<Playlist>,
    keyPrefix: String,
    alreadyIn: Set<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    items(list, key = { keyPrefix + it.id }) { playlist ->
        SelectablePlaylistRow(
            playlist = playlist,
            alreadyHasSong = playlist.id in alreadyIn,
            checked = playlist.id in selected,
            onToggle = { onToggle(playlist.id) },
        )
    }
}

private fun LazyListScope.sectionHeader(title: Int) {
    item(key = "h:$title") {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SelectablePlaylistRow(
    playlist: Playlist,
    alreadyHasSong: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    PlaylistListItem(
        playlist = playlist,
        badges = {},
        trailingContent = {
            Checkbox(
                checked = checked || alreadyHasSong,
                enabled = !alreadyHasSong,
                onCheckedChange = { onToggle() },
            )
        },
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !alreadyHasSong, onClick = onToggle),
    )
}

@Composable
private fun FolderRow(
    name: String,
    count: Int,
    selectedCount: Int,
    open: Boolean,
    onClick: () -> Unit,
) {
    val subtitle = pluralStringResource(R.plurals.n_playlist, count, count) +
        if (selectedCount > 0) " · " + stringResource(R.string.plus_n_selected, selectedCount) else ""
    ListItem(
        title = name,
        subtitle = subtitle,
        thumbnailContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(ListThumbnailSize)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(
                    painter = painterResource(R.drawable.plus_folder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ListThumbnailSize / 2),
                )
            }
        },
        trailingContent = {
            Icon(
                painter = painterResource(if (open) R.drawable.expand_less else R.drawable.expand_more),
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp),
            )
        },
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    )
}
