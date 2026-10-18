/**
 * Metrolist Plus — controles del reproductor al estilo Spotify.
 *
 * Reemplaza solo el bloque de controles del reproductor original (título, barra de progreso y botones);
 * la portada, el fondo y la barra de la cola siguen siendo los del proyecto original.
 */

package com.metrolist.music.plus.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.PlayerHorizontalPadding
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberPreference

val PlusSpotifyPlayerKey = booleanPreferencesKey("plusSpotifyPlayer")

val SpotifyGreen = Color(0xFF1ED760)

@Composable
fun rememberPlusSpotifyPlayerEnabled(): Boolean {
    val enabled by rememberPreference(PlusSpotifyPlayerKey, defaultValue = true)
    return enabled
}

@Composable
fun plusSpotifyPlayerSettingsItem(): Material3SettingsItem {
    val (enabled, onEnabledChange) = rememberPreference(PlusSpotifyPlayerKey, defaultValue = true)
    return Material3SettingsItem(
        icon = painterResource(R.drawable.plus_check_circle),
        title = { Text(stringResource(R.string.plus_spotify_player)) },
        description = { Text(stringResource(R.string.plus_spotify_player_description)) },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                thumbContent = {
                    Icon(
                        painter = painterResource(if (enabled) R.drawable.check else R.drawable.close),
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        },
        onClick = { onEnabledChange(!enabled) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColumnScope.PlusSpotifyControls(
    mediaMetadata: MediaMetadata,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    isEnded: Boolean,
    isListenTogetherGuest: Boolean,
    sleepTimerActive: Boolean,
    contentColor: Color,
    playButtonColor: Color,
    playIconColor: Color,
    onSeekChange: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    onPlayPause: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val shuffleEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()
    val isMuted by playerConnection.isMuted.collectAsStateWithLifecycle()

    val isEpisode = currentSong?.song?.isEpisode == true
    val isFavorite = if (isEpisode) currentSong?.song?.inLibrary != null else currentSong?.song?.liked == true
    val secondaryColor = contentColor.copy(alpha = 0.7f)
    val hasDuration = duration > 0

    // Title, artists and like button
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PlayerHorizontalPadding),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = mediaMetadata.title,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "plusTitle",
            ) { title ->
                Text(
                    text = title,
                    color = contentColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenAlbum,
                        ),
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                mediaMetadata.artists.forEachIndexed { index, artist ->
                    Text(
                        text = artist.name + if (index != mediaMetadata.artists.lastIndex) ", " else "",
                        color = secondaryColor,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(
                            enabled = !artist.id.isNullOrBlank(),
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { artist.id?.let(onOpenArtist) },
                    )
                }
            }
        }

        Spacer(Modifier.width(16.dp))

        IconButton(onClick = playerConnection::toggleLike) {
            Icon(
                painter = painterResource(if (isFavorite) R.drawable.plus_check_circle else R.drawable.plus_add_circle),
                contentDescription = null,
                tint = if (isFavorite) Color.Unspecified else contentColor,
                modifier = Modifier.size(30.dp),
            )
        }
    }

    Spacer(Modifier.height(20.dp))

    SpotifySeekBar(
        fraction = if (hasDuration) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
        color = contentColor,
        enabled = hasDuration && !isListenTogetherGuest,
        onSeek = { fraction -> onSeekChange((fraction * duration).toLong()) },
        onSeekFinished = onSeekFinished,
        modifier = Modifier.padding(horizontal = PlayerHorizontalPadding),
    )

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PlayerHorizontalPadding),
    ) {
        Text(text = makeTimeString(position), color = secondaryColor, fontSize = 12.sp)
        Text(text = if (hasDuration) makeTimeString(duration) else "", color = secondaryColor, fontSize = 12.sp)
    }

    Spacer(Modifier.height(16.dp))

    // Shuffle · previous · play/pause · next · sleep timer
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PlayerHorizontalPadding - 8.dp),
    ) {
        ToggleIcon(
            icon = R.drawable.shuffle,
            active = shuffleEnabled,
            contentColor = contentColor,
            enabled = !isListenTogetherGuest,
            onClick = { playerConnection.player.shuffleModeEnabled = !shuffleEnabled },
        )

        IconButton(
            onClick = playerConnection::seekToPrevious,
            enabled = canSkipPrevious && !isListenTogetherGuest,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.skip_previous),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .size(40.dp)
                    .alpha(if (canSkipPrevious && !isListenTogetherGuest) 1f else 0.4f),
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(playButtonColor)
                .clickable(onClick = onPlayPause),
        ) {
            Icon(
                painter = painterResource(
                    when {
                        isListenTogetherGuest -> if (isMuted) R.drawable.volume_off else R.drawable.volume_up
                        isEnded -> R.drawable.replay
                        isPlaying -> R.drawable.pause
                        else -> R.drawable.play
                    },
                ),
                contentDescription = null,
                tint = playIconColor,
                modifier = Modifier.size(36.dp),
            )
        }

        IconButton(
            onClick = playerConnection::seekToNext,
            enabled = canSkipNext && !isListenTogetherGuest,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.skip_next),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .size(40.dp)
                    .alpha(if (canSkipNext && !isListenTogetherGuest) 1f else 0.4f),
            )
        }

        ToggleIcon(
            icon = R.drawable.plus_timer,
            active = sleepTimerActive,
            contentColor = contentColor,
            enabled = true,
            onClick = onSleepTimerClick,
        )
    }

    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ToggleIcon(
    icon: Int,
    active: Boolean,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (active) SpotifyGreen else contentColor,
                modifier = Modifier
                    .size(26.dp)
                    .alpha(if (enabled) 1f else 0.4f),
            )
        }
        // Spotify marks active toggles with a small dot under the icon
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (active) SpotifyGreen else Color.Transparent),
        )
    }
}

@Composable
private fun SpotifySeekBar(
    fraction: Float,
    color: Color,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnSeekFinished by rememberUpdatedState(onSeekFinished)

    BoxWithConstraints(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    currentOnSeek((offset.x / size.width).coerceIn(0f, 1f))
                    currentOnSeekFinished()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        currentOnSeek((offset.x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = {
                        dragging = false
                        currentOnSeekFinished()
                    },
                    onDragCancel = {
                        dragging = false
                        currentOnSeekFinished()
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        currentOnSeek((change.position.x / size.width).coerceIn(0f, 1f))
                    },
                )
            },
    ) {
        val trackHeight = if (dragging) 6.dp else 4.dp
        val thumbSize = if (dragging) 16.dp else 12.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.3f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - thumbSize) * fraction)
                .size(thumbSize)
                .clip(CircleShape)
                .background(color),
        )
    }
}
