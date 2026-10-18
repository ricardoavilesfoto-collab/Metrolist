/**
 * Metrolist Plus — componentes visuales de carpetas y playlists fijadas.
 */

package com.metrolist.music.plus.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.ui.component.GridItem
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import com.metrolist.music.ui.component.TextFieldDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface FolderNameRequest {
    data class Create(val playlistKey: String?) : FolderNameRequest

    data class Rename(val folder: PlaylistFolder) : FolderNameRequest
}

/** Dialogs live at screen level so they survive the bottom sheet that opened them being dismissed. */
@Stable
class PlusLibraryDialogState {
    var moveTarget by mutableStateOf<String?>(null)
    var nameRequest by mutableStateOf<FolderNameRequest?>(null)
    var deleteTarget by mutableStateOf<PlaylistFolder?>(null)
}

@Composable
fun rememberPlusLibraryDialogState() = remember { PlusLibraryDialogState() }

@Composable
private fun FolderIcon(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.plus_folder),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun PinIndicator() {
    Icon(
        painter = painterResource(R.drawable.ic_push_pin),
        contentDescription = stringResource(R.string.plus_pinned),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
fun PlusFolderListItem(
    folder: PlaylistFolder,
    playlists: List<Playlist>,
    isPinned: Boolean,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        title = folder.name,
        subtitle = pluralStringResource(R.plurals.n_playlist, playlists.size, playlists.size),
        thumbnailContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(ListThumbnailSize)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                FolderIcon(Modifier.size(ListThumbnailSize / 2))
            }
        },
        trailingContent = {
            if (isPinned) PinIndicator()
            IconButton(onClick = onMenuClick) {
                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = null,
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlusFolderGridItem(
    folder: PlaylistFolder,
    playlists: List<Playlist>,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GridItem(
        title = folder.name,
        subtitle = pluralStringResource(R.plurals.n_playlist, playlists.size, playlists.size),
        thumbnailContent = {
            val width = maxWidth
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius * 2))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                FolderIcon(Modifier.size(width / 3))
            }
        },
        fillMaxWidth = true,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMenuClick),
    )
}

@Composable
fun PlusFolderHeader(
    folder: PlaylistFolder,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.arrow_back),
                contentDescription = null,
            )
        }
        FolderIcon(Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = folder.name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Quick actions shown on top of the regular playlist menu. */
@Composable
fun PlusPlaylistQuickActions(
    playlist: Playlist,
    coroutineScope: CoroutineScope,
    dialogs: PlusLibraryDialogState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val organization by remember { context.libraryOrganizationFlow() }
        .collectAsStateWithLifecycle(LibraryOrganization())
    val key = playlist.organizationKey
    val isPinned = organization.isPinned(key)
    val currentFolder = organization.folderOf(key)

    NewActionGrid(
        actions = buildList {
            add(
                NewAction(
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_push_pin),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = stringResource(if (isPinned) R.string.plus_unpin else R.string.plus_pin),
                    onClick = {
                        coroutineScope.launch { context.updateLibraryOrganization { it.setPinned(key, !isPinned) } }
                        onDismiss()
                    },
                ),
            )
            add(
                NewAction(
                    icon = { FolderIcon(Modifier.size(28.dp)) },
                    text = stringResource(R.string.plus_move_to_folder),
                    onClick = {
                        dialogs.moveTarget = key
                        onDismiss()
                    },
                ),
            )
            if (currentFolder != null) {
                add(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.remove),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = stringResource(R.string.plus_remove_from_folder),
                        onClick = {
                            coroutineScope.launch { context.updateLibraryOrganization { it.removeFromFolders(key) } }
                            onDismiss()
                        },
                    ),
                )
            }
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        columns = 3,
    )
    HorizontalDivider()
}

@Composable
fun PlusFolderMenu(
    folder: PlaylistFolder,
    playlistCount: Int,
    isPinned: Boolean,
    coroutineScope: CoroutineScope,
    dialogs: PlusLibraryDialogState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    ListItem(
        title = folder.name,
        subtitle = pluralStringResource(R.plurals.n_playlist, playlistCount, playlistCount),
        thumbnailContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(ListThumbnailSize)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                FolderIcon(Modifier.size(ListThumbnailSize / 2))
            }
        },
    )
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    Column(
        modifier = Modifier.padding(
            start = 16.dp,
            end = 16.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        Material3MenuGroup(
            items = listOf(
                Material3MenuItemData(
                    title = { Text(stringResource(if (isPinned) R.string.plus_unpin else R.string.plus_pin)) },
                    icon = { Icon(painterResource(R.drawable.ic_push_pin), contentDescription = null) },
                    onClick = {
                        coroutineScope.launch { context.updateLibraryOrganization { it.setPinned(folder.key, !isPinned) } }
                        onDismiss()
                    },
                ),
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.plus_rename_folder)) },
                    icon = { Icon(painterResource(R.drawable.edit), contentDescription = null) },
                    onClick = {
                        dialogs.nameRequest = FolderNameRequest.Rename(folder)
                        onDismiss()
                    },
                ),
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.plus_delete_folder)) },
                    icon = { Icon(painterResource(R.drawable.delete), contentDescription = null) },
                    onClick = {
                        dialogs.deleteTarget = folder
                        onDismiss()
                    },
                ),
            ),
        )
    }
}

@Composable
fun PlusCreateMenu(
    onCreatePlaylist: () -> Unit,
    onCreateFolder: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        Text(
            text = stringResource(R.string.plus_create),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
        )
        Material3MenuGroup(
            items = listOf(
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.create_playlist)) },
                    icon = { Icon(painterResource(R.drawable.playlist_add), contentDescription = null) },
                    onClick = onCreatePlaylist,
                ),
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.plus_new_folder)) },
                    icon = { FolderIcon() },
                    onClick = onCreateFolder,
                ),
            ),
        )
    }
}

@Composable
private fun DialogRow(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
fun PlusLibraryDialogs(
    state: PlusLibraryDialogState,
    coroutineScope: CoroutineScope,
) {
    val context = LocalContext.current
    val organization by remember { context.libraryOrganizationFlow() }
        .collectAsStateWithLifecycle(LibraryOrganization())

    state.moveTarget?.let { playlistKey ->
        val currentFolderId = organization.folderOf(playlistKey)?.id
        ListDialog(onDismiss = { state.moveTarget = null }) {
            item {
                Text(
                    text = stringResource(R.string.plus_move_to_folder),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                )
            }
            item {
                DialogRow(
                    text = stringResource(R.string.plus_new_folder),
                    icon = { Icon(painterResource(R.drawable.plus_create_new_folder), contentDescription = null) },
                    onClick = {
                        state.moveTarget = null
                        state.nameRequest = FolderNameRequest.Create(playlistKey)
                    },
                )
            }
            items(organization.folders.sortedBy { it.name.lowercase() }, key = { it.id }) { folder ->
                DialogRow(
                    text = folder.name,
                    icon = { FolderIcon() },
                    onClick = {
                        state.moveTarget = null
                        coroutineScope.launch {
                            context.updateLibraryOrganization { it.moveToFolder(playlistKey, folder.id) }
                        }
                    },
                    trailing = {
                        if (folder.id == currentFolderId) {
                            Icon(
                                painter = painterResource(R.drawable.check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }

    state.nameRequest?.let { request ->
        val initialName = (request as? FolderNameRequest.Rename)?.folder?.name.orEmpty()
        TextFieldDialog(
            icon = { FolderIcon() },
            title = {
                Text(
                    stringResource(
                        if (request is FolderNameRequest.Rename) R.string.plus_rename_folder else R.string.plus_new_folder,
                    ),
                )
            },
            initialTextFieldValue = TextFieldValue(initialName, TextRange(initialName.length)),
            placeholder = { Text(stringResource(R.string.plus_folder_name)) },
            isInputValid = { it.isNotBlank() },
            onDone = { name ->
                coroutineScope.launch {
                    context.updateLibraryOrganization {
                        when (request) {
                            is FolderNameRequest.Create -> it.createFolder(name, request.playlistKey)
                            is FolderNameRequest.Rename -> it.renameFolder(request.folder.id, name)
                        }
                    }
                }
            },
            onDismiss = { state.nameRequest = null },
        )
    }

    state.deleteTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { state.deleteTarget = null },
            icon = { FolderIcon() },
            title = { Text(stringResource(R.string.plus_delete_folder)) },
            text = { Text(stringResource(R.string.plus_delete_folder_message, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.deleteTarget = null
                        coroutineScope.launch { context.updateLibraryOrganization { it.deleteFolder(folder.id) } }
                    },
                ) {
                    Text(stringResource(R.string.plus_delete_folder))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
