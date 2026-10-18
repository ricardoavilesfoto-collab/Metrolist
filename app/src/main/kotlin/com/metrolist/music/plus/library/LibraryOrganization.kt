/**
 * Metrolist Plus — carpetas y playlists fijadas en la Biblioteca.
 *
 * Se guardan como JSON dentro del DataStore de ajustes (que ya forma parte de los respaldos de la app)
 * para no modificar el esquema de la base de datos del proyecto original.
 */

package com.metrolist.music.plus.library

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

val PlusLibraryOrganizationKey = stringPreferencesKey("plusLibraryOrganization")

private const val FOLDER_KEY_PREFIX = "folder:"

@Serializable
data class PlaylistFolder(
    val id: String,
    val name: String,
    val playlistKeys: List<String> = emptyList(),
)

@Serializable
data class LibraryOrganization(
    /** Most recently pinned first. Holds playlist keys and folder keys. */
    val pinnedKeys: List<String> = emptyList(),
    val folders: List<PlaylistFolder> = emptyList(),
) {
    fun isPinned(key: String) = key in pinnedKeys

    fun folderOf(playlistKey: String) = folders.firstOrNull { playlistKey in it.playlistKeys }
}

/** Survives re-syncs and re-logins because synced playlists are keyed by their YouTube id. */
val Playlist.organizationKey: String
    get() = playlist.browseId ?: playlist.id

val PlaylistFolder.key: String
    get() = FOLDER_KEY_PREFIX + id

private val json = Json { ignoreUnknownKeys = true }

private fun decodeOrganization(raw: String?): LibraryOrganization =
    raw?.let { runCatching { json.decodeFromString(LibraryOrganization.serializer(), it) }.getOrNull() }
        ?: LibraryOrganization()

fun Context.libraryOrganizationFlow(): Flow<LibraryOrganization> =
    dataStore.data
        .map { decodeOrganization(it[PlusLibraryOrganizationKey]) }
        .distinctUntilChanged()

suspend fun Context.updateLibraryOrganization(transform: (LibraryOrganization) -> LibraryOrganization) {
    safeDataStoreEdit { prefs ->
        val updated = transform(decodeOrganization(prefs[PlusLibraryOrganizationKey]))
        prefs[PlusLibraryOrganizationKey] = json.encodeToString(LibraryOrganization.serializer(), updated)
    }
}

/** Idempotent on purpose: menu actions may fire more than once per tap. */
fun LibraryOrganization.setPinned(key: String, pinned: Boolean) =
    copy(pinnedKeys = if (pinned) listOf(key) + (pinnedKeys - key) else pinnedKeys - key)

fun LibraryOrganization.removeFromFolders(playlistKey: String) =
    copy(folders = folders.map { it.copy(playlistKeys = it.playlistKeys - playlistKey) })

fun LibraryOrganization.moveToFolder(playlistKey: String, folderId: String): LibraryOrganization {
    val cleaned = removeFromFolders(playlistKey)
    return cleaned.copy(
        folders = cleaned.folders.map {
            if (it.id == folderId) it.copy(playlistKeys = it.playlistKeys + playlistKey) else it
        },
    )
}

fun LibraryOrganization.createFolder(name: String, playlistKey: String? = null): LibraryOrganization {
    val folder = PlaylistFolder(id = UUID.randomUUID().toString(), name = name.trim())
    val withFolder = copy(folders = folders + folder)
    return if (playlistKey != null) withFolder.moveToFolder(playlistKey, folder.id) else withFolder
}

fun LibraryOrganization.renameFolder(folderId: String, name: String) =
    copy(folders = folders.map { if (it.id == folderId) it.copy(name = name.trim()) else it })

/** Only the folder goes away; its playlists return to the library root. */
fun LibraryOrganization.deleteFolder(folderId: String) =
    copy(
        folders = folders.filterNot { it.id == folderId },
        pinnedKeys = pinnedKeys - (FOLDER_KEY_PREFIX + folderId),
    )

sealed interface OrganizedRow {
    data class PlaylistRow(val playlist: Playlist, val isPinned: Boolean) : OrganizedRow

    data class FolderRow(
        val folder: PlaylistFolder,
        val playlists: List<Playlist>,
        val isPinned: Boolean,
    ) : OrganizedRow
}

data class OrganizedLibrary(
    val pinned: List<OrganizedRow>,
    val rest: List<OrganizedRow>,
)

/**
 * Root of the library: pinned items (in pin order), then folders by name, then every playlist
 * that is neither pinned nor inside a folder, keeping the order chosen by the user's sort.
 */
fun LibraryOrganization.organizeRoot(playlists: List<Playlist>): OrganizedLibrary {
    val byKey = playlists.associateBy { it.organizationKey }
    val inFolders = folders.flatMap { it.playlistKeys }.toSet()
    val folderRows = folders.associate { folder ->
        folder.key to OrganizedRow.FolderRow(
            folder = folder,
            playlists = folder.playlistKeys.mapNotNull(byKey::get),
            isPinned = isPinned(folder.key),
        )
    }

    val pinned: List<OrganizedRow> = pinnedKeys.mapNotNull { key ->
        folderRows[key] ?: byKey[key]?.let { OrganizedRow.PlaylistRow(it, isPinned = true) }
    }
    val rest: List<OrganizedRow> =
        folderRows.values.filterNot { it.isPinned }.sortedBy { it.folder.name.lowercase() } +
            playlists
                .filter { it.organizationKey !in inFolders && !isPinned(it.organizationKey) }
                .map { OrganizedRow.PlaylistRow(it, isPinned = false) }

    return OrganizedLibrary(pinned = pinned, rest = rest)
}

fun LibraryOrganization.folderContents(folderId: String, playlists: List<Playlist>): List<OrganizedRow.PlaylistRow> {
    val keys = folders.firstOrNull { it.id == folderId }?.playlistKeys?.toSet() ?: return emptyList()
    return playlists
        .filter { it.organizationKey in keys }
        .map { OrganizedRow.PlaylistRow(it, isPinned = isPinned(it.organizationKey)) }
}
