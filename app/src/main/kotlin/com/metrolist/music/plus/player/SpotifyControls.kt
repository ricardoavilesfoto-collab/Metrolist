/**
 * Metrolist Plus — controles del reproductor: diseño híbrido Metrolist + Spotify.
 *
 * Del original se conservan el título/artistas, la barra de progreso con onda (según el ajuste del usuario),
 * la portada, el fondo y la barra de la cola. De Spotify vienen la fila compacta
 * aleatorio · anterior · play · siguiente · cola, los botones minimalistas de compartir y agregar a playlist,
 * y el selector de dispositivo de salida.
 */

package com.metrolist.music.plus.player

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.SliderColors
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.PlayerHorizontalPadding
import com.metrolist.music.constants.SliderStyle
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.SquigglySlider
import com.metrolist.music.ui.component.WavySlider
import com.metrolist.music.ui.menu.AddToPlaylistDialog
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.ui.component.Icon as MIcon

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
    sliderStyle: SliderStyle,
    squigglySlider: Boolean,
    sliderColors: SliderColors,
    contentColor: Color,
    playButtonColor: Color,
    playIconColor: Color,
    onSeekChange: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    onPlayPause: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val shuffleEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()
    val isMuted by playerConnection.isMuted.collectAsStateWithLifecycle()
    val hasDuration = duration > 0
    val controlsEnabled = !isListenTogetherGuest

    var showAddToPlaylist by rememberSaveable { mutableStateOf(false) }
    AddToPlaylistDialog(
        isVisible = showAddToPlaylist,
        onGetSong = {
            database.withTransaction { insert(mediaMetadata) }
            listOf(mediaMetadata.id)
        },
        onGetSongIds = { listOf(mediaMetadata.id) },
        onDismiss = { showAddToPlaylist = false },
    )

    // Title and artists (original Metrolist look) + minimal share / add-to-playlist (Spotify look)
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
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenAlbum,
                        ),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp),
            ) {
                if (mediaMetadata.explicit) MIcon.Explicit()
                mediaMetadata.artists.forEachIndexed { index, artist ->
                    Text(
                        text = artist.name + if (index != mediaMetadata.artists.lastIndex) ", " else "",
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                        maxLines = 1,
                        modifier = Modifier.clickable(
                            enabled = !artist.id.isNullOrBlank(),
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { artist.id?.let(onOpenArtist) },
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        IconButton(
            onClick = {
                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${mediaMetadata.id}")
                }
                context.startActivity(Intent.createChooser(intent, null))
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.plus_share),
                contentDescription = stringResource(R.string.plus_share),
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
        }

        IconButton(onClick = { showAddToPlaylist = true }) {
            Icon(
                painter = painterResource(R.drawable.playlist_add),
                contentDescription = stringResource(R.string.plus_add_to_playlist),
                tint = contentColor,
                modifier = Modifier.size(28.dp),
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    // Progress bar: keeps the user's slider style (the wave), thin Spotify bar otherwise
    val sliderRange = 0f..(if (hasDuration) duration.toFloat() else 0f)
    val sliderModifier = Modifier.padding(horizontal = PlayerHorizontalPadding)
    when {
        sliderStyle == SliderStyle.WAVY && squigglySlider -> SquigglySlider(
            value = position.toFloat(),
            valueRange = sliderRange,
            onValueChange = { onSeekChange(it.toLong()) },
            onValueChangeFinished = onSeekFinished,
            enabled = controlsEnabled,
            colors = sliderColors,
            isPlaying = isPlaying,
            modifier = sliderModifier,
        )

        sliderStyle == SliderStyle.WAVY -> WavySlider(
            value = position.toFloat(),
            valueRange = sliderRange,
            onValueChange = { onSeekChange(it.toLong()) },
            onValueChangeFinished = onSeekFinished,
            enabled = controlsEnabled,
            colors = sliderColors,
            isPlaying = isPlaying,
            modifier = sliderModifier,
        )

        else -> SpotifySeekBar(
            fraction = if (hasDuration) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            color = contentColor,
            enabled = hasDuration && controlsEnabled,
            onSeek = { fraction -> onSeekChange((fraction * duration).toLong()) },
            onSeekFinished = onSeekFinished,
            modifier = sliderModifier,
        )
    }

    Spacer(Modifier.height(4.dp))

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PlayerHorizontalPadding + 4.dp),
    ) {
        Text(
            text = makeTimeString(position),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
        Text(
            text = if (hasDuration) makeTimeString(duration) else "",
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }

    Spacer(Modifier.height(12.dp))

    // Shuffle · previous · play/pause · next · queue
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
            enabled = controlsEnabled,
            onClick = { playerConnection.player.shuffleModeEnabled = !shuffleEnabled },
        )

        IconButton(
            onClick = playerConnection::seekToPrevious,
            enabled = canSkipPrevious && controlsEnabled,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.skip_previous),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .size(34.dp)
                    .alpha(if (canSkipPrevious && controlsEnabled) 1f else 0.4f),
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
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
                modifier = Modifier.size(32.dp),
            )
        }

        IconButton(
            onClick = playerConnection::seekToNext,
            enabled = canSkipNext && controlsEnabled,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.skip_next),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .size(34.dp)
                    .alpha(if (canSkipNext && controlsEnabled) 1f else 0.4f),
            )
        }

        ToggleIcon(
            icon = R.drawable.queue_music,
            active = false,
            contentColor = contentColor,
            enabled = true,
            onClick = onOpenQueue,
        )
    }

    Spacer(Modifier.height(8.dp))

    // Output device (Spotify's "devices" button)
    val output = rememberAudioOutput()
    val isExternal = output.kind != AudioOutputKind.PHONE
    val outputColor = if (isExternal) SpotifyGreen else contentColor.copy(alpha = 0.8f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.Start)
            .padding(horizontal = PlayerHorizontalPadding - 8.dp)
            .clip(RoundedCornerShape(50))
            .combinedClickable(
                onClick = { context.openAudioOutputPicker() },
                onLongClick = { context.openBluetoothSettings() },
                onClickLabel = stringResource(R.string.plus_output_choose),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(
            painter = painterResource(
                when (output.kind) {
                    AudioOutputKind.BLUETOOTH -> R.drawable.bluetooth
                    AudioOutputKind.HEADPHONES -> R.drawable.plus_headphones
                    AudioOutputKind.PHONE -> R.drawable.plus_smartphone
                },
            ),
            contentDescription = null,
            tint = outputColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = output.name ?: stringResource(
                if (output.kind == AudioOutputKind.HEADPHONES) R.string.plus_output_headphones else R.string.plus_output_this_phone,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = outputColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    Spacer(Modifier.height(4.dp))
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
