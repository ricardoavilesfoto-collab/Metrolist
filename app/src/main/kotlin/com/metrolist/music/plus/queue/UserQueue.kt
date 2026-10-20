/**
 * Metrolist Plus — cola del usuario al estilo Spotify ("Siguiente en la cola").
 *
 * Las canciones que el usuario agrega se marcan con un extra en `MediaItem.mediaMetadata`. La cola del usuario
 * es el bloque de canciones marcadas que siguen a la actual en el orden de reproducción: "Agregar a la cola"
 * inserta al final de ese bloque (antes del resto de la playlist) y "Reproducir a continuación" al inicio.
 */

package com.metrolist.music.plus.queue

import android.os.Bundle
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.metrolist.music.R
import com.metrolist.music.playback.MusicService

private const val USER_QUEUE_EXTRA = "plus_user_queue"

val MediaItem.isUserQueued: Boolean
    get() = mediaMetadata.extras?.getBoolean(USER_QUEUE_EXTRA) == true

fun MediaItem.markedAsUserQueued(): MediaItem {
    if (isUserQueued) return this
    val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply { putBoolean(USER_QUEUE_EXTRA, true) }
    return buildUpon()
        .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
        .build()
}

/**
 * The player adds the first "similar content" song by itself when the last song starts. It is part of the playlist,
 * not something the user queued: marking it would show it under "Next in queue" and carry it into the next playlist.
 */
fun MusicService.plusAppendFirstAutomix() {
    val item = automixItems.value.firstOrNull() ?: return
    automixItems.value = automixItems.value.drop(1)
    player.addMediaItem(item)
    player.prepare()
}

/** Indices (in play order) of the user-queued songs right after the current one. */
private fun Player.upcomingUserQueueIndices(): List<Int> {
    val timeline = currentTimeline
    if (timeline.isEmpty) return emptyList()
    val indices = mutableListOf<Int>()
    var index = timeline.getNextWindowIndex(currentMediaItemIndex, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
    while (index != C.INDEX_UNSET && getMediaItemAt(index).isUserQueued) {
        indices.add(index)
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
    }
    return indices
}

fun Player.upcomingUserQueue(): List<MediaItem> = upcomingUserQueueIndices().map { getMediaItemAt(it) }

fun Player.clearUpcomingUserQueue() {
    upcomingUserQueueIndices().sortedDescending().forEach { removeMediaItem(it) }
}

/**
 * Adds [items] to the user queue: after the songs already queued ([atFront] = false, "Add to queue")
 * or right after the current song ([atFront] = true, "Play next"). The rest of the playlist keeps its order.
 */
fun ExoPlayer.addToUserQueue(items: List<MediaItem>, atFront: Boolean = false) {
    if (items.isEmpty()) return
    val marked = items.map { it.markedAsUserQueued() }

    if (!shuffleModeEnabled) {
        var insertIndex = currentMediaItemIndex + 1
        if (!atFront) {
            while (insertIndex < mediaItemCount && getMediaItemAt(insertIndex).isUserQueued) insertIndex++
        }
        addMediaItems(insertIndex, marked)
        return
    }

    // With shuffle the physical position does not matter: append, then rebuild the play order.
    val firstNewIndex = mediaItemCount
    addMediaItems(marked)
    val timeline = currentTimeline
    if (timeline.isEmpty) return
    val size = timeline.windowCount
    val newIndices = (firstNewIndex until firstNewIndex + marked.size).toList()

    val order = mutableListOf<Int>()
    var index = timeline.getFirstWindowIndex(true)
    while (index != C.INDEX_UNSET && order.size < size) {
        if (index !in newIndices) order.add(index)
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
    }

    var insertPos = order.indexOf(currentMediaItemIndex) + 1
    if (!atFront) {
        while (insertPos < order.size && getMediaItemAt(order[insertPos]).isUserQueued) insertPos++
    }
    order.addAll(insertPos, newIndices)
    // Safety net: the shuffle order must be a full permutation of the playlist.
    (0 until size).filterNot { it in order }.forEach { order.add(it) }
    if (order.size != size) return

    setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), System.currentTimeMillis()))
}

/**
 * Spotify-like section labels inside the queue list: "Now playing", "Next in queue" (with "Clear queue")
 * and "Next from: <playlist>". [windows] are in play order, like `Player.getQueueWindows()`.
 */
@Composable
fun PlusQueueSectionHeader(
    index: Int,
    currentIndex: Int,
    windows: List<Timeline.Window>,
    queueTitle: String?,
    canEdit: Boolean,
    onClearQueue: () -> Unit,
) {
    if (currentIndex < 0 || index !in windows.indices) return
    val isQueued = windows[index].mediaItem.isUserQueued
    val previousIsQueued = index > 0 && windows[index - 1].mediaItem.isUserQueued

    when {
        index == currentIndex -> SectionTitle(stringResource(R.string.now_playing))

        index == currentIndex + 1 && isQueued -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
        ) {
            SectionTitle(stringResource(R.string.plus_next_in_queue), Modifier.weight(1f))
            if (canEdit) {
                TextButton(onClick = onClearQueue) {
                    Text(stringResource(R.string.plus_clear_queue))
                }
            }
        }

        index > currentIndex && !isQueued && (index == currentIndex + 1 || previousIsQueued) -> SectionTitle(
            if (queueTitle.isNullOrBlank()) {
                stringResource(R.string.plus_up_next)
            } else {
                stringResource(R.string.plus_next_from, queueTitle)
            },
        )
    }
}

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
