/**
 * Metrolist Plus — "Dynamic Island": píldora flotante arriba de la pantalla mientras la música
 * suena con la app en segundo plano. Toque → tarjeta con controles; toque en la tarjeta → abre la app.
 * Usa una ventana superpuesta (permiso "Mostrar sobre otras apps").
 */

package com.metrolist.music.plus.island

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil3.compose.AsyncImage
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.random.Random

val PlusDynamicIslandKey = booleanPreferencesKey("plusDynamicIsland")

private const val PAUSED_HIDE_DELAY_MS = 30_000L
private const val AUTO_COLLAPSE_MS = 6_000L

fun Context.canShowDynamicIsland() = Settings.canDrawOverlays(this)

fun Context.requestDynamicIslandPermission() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
fun plusDynamicIslandSettingsItem(): Material3SettingsItem {
    val context = LocalContext.current
    val (enabled, onEnabledChange) = rememberPreference(PlusDynamicIslandKey, defaultValue = true)
    // Re-read on every recomposition: the user comes back from the system permission screen.
    val granted = context.canShowDynamicIsland()
    val active = enabled && granted
    val toggle = {
        if (!active && !granted) {
            onEnabledChange(true)
            context.requestDynamicIslandPermission()
        } else {
            onEnabledChange(!active)
        }
    }
    return Material3SettingsItem(
        icon = painterResource(R.drawable.plus_island),
        title = { Text(stringResource(R.string.plus_island)) },
        description = {
            Text(stringResource(if (enabled && !granted) R.string.plus_island_permission else R.string.plus_island_description))
        },
        trailingContent = {
            Switch(
                checked = active,
                onCheckedChange = { toggle() },
                thumbContent = {
                    Icon(
                        painter = painterResource(if (active) R.drawable.check else R.drawable.close),
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        },
        onClick = toggle,
    )
}

/** Created by MusicService on the main thread; [release] must be called from onDestroy. */
class PlusDynamicIsland(
    private val context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
) : Player.Listener, DefaultLifecycleObserver {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var enabled = false
    private var appInForeground = true
    private var pausedTooLong = false
    private var hiddenForMediaId: String? = null

    private var root: IslandRoot? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var prefJob: Job? = null
    private var pausedJob: Job? = null

    // Compose state read by the overlay.
    private var title by mutableStateOf("")
    private var artist by mutableStateOf("")
    private var artwork by mutableStateOf<String?>(null)
    private var isPlaying by mutableStateOf(false)
    private var expanded by mutableStateOf(false)

    fun start() {
        player.addListener(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        readPlayer()
        prefJob = scope.launch {
            context.dataStore.data
                .map { it[PlusDynamicIslandKey] ?: true }
                .distinctUntilChanged()
                .collect {
                    enabled = it
                    update()
                }
        }
    }

    fun release() {
        prefJob?.cancel()
        pausedJob?.cancel()
        player.removeListener(this)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        hide()
    }

    override fun onStart(owner: LifecycleOwner) {
        appInForeground = true
        update()
    }

    override fun onStop(owner: LifecycleOwner) {
        appInForeground = false
        update()
    }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        readPlayer()
    }

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        hiddenForMediaId = null
        readPlayer()
        update()
    }

    override fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
        pausedJob?.cancel()
        if (playing) {
            pausedTooLong = false
        } else {
            pausedJob = scope.launch {
                delay(PAUSED_HIDE_DELAY_MS)
                pausedTooLong = true
                update()
            }
        }
        update()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        update()
    }

    private fun readPlayer() {
        val metadata = player.currentMediaItem?.mediaMetadata
        title = metadata?.title?.toString().orEmpty()
        artist = (metadata?.artist ?: metadata?.subtitle)?.toString().orEmpty()
        artwork = metadata?.artworkUri?.toString()
        isPlaying = player.isPlaying
    }

    private fun update() {
        val item = player.currentMediaItem
        val shouldShow = enabled &&
            !appInForeground &&
            item != null &&
            item.mediaId != hiddenForMediaId &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED &&
            (isPlaying || !pausedTooLong || expanded) &&
            context.canShowDynamicIsland()
        if (shouldShow) show() else hide()
    }

    private fun show() {
        if (root != null) return
        runCatching {
            val owner = OverlayLifecycleOwner().also { it.create() }
            val view = IslandRoot(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                addView(
                    ComposeView(context).apply {
                        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                        setContent { IslandContent() }
                    },
                )
            }
            windowManager.addView(view, layoutParams())
            root = view
            lifecycleOwner = owner
        }.onFailure { Timber.w(it, "Could not show the Dynamic Island") }
    }

    private fun hide() {
        expanded = false
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
        lifecycleOwner?.destroy()
        root = null
        lifecycleOwner = null
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = (6 * context.resources.displayMetrics.density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun openApp() {
        // The window goes away in onStart() when the app comes to the front.
        expanded = false
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
        }.onFailure { Timber.w(it, "Could not open the app from the Dynamic Island") }
    }

    private fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun IslandContent() {
        LaunchedEffect(expanded, isPlaying) {
            if (expanded) {
                delay(AUTO_COLLAPSE_MS)
                expanded = false
                update()
            }
        }
        val shape = RoundedCornerShape(if (expanded) 28.dp else 50.dp)
        Box(
            modifier = Modifier
                .animateContentSize(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
                .clip(shape)
                .background(Color.Black)
                .combinedClickable(
                    onClick = { if (expanded) openApp() else expanded = true },
                    onLongClick = {
                        // Hide until the next song.
                        hiddenForMediaId = player.currentMediaItem?.mediaId
                        root?.post { update() }
                    },
                ),
        ) {
            if (expanded) ExpandedCard() else CollapsedPill()
        }
    }

    @Composable
    private fun CollapsedPill() {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .width(180.dp)
                .height(34.dp)
                .padding(horizontal = 6.dp),
        ) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(24.dp).clip(CircleShape).background(Color.DarkGray),
            )
            Spacer(Modifier.weight(1f))
            Equalizer(playing = isPlaying, modifier = Modifier.padding(end = 6.dp))
        }
    }

    @Composable
    private fun ExpandedCard() {
        var position by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            while (true) {
                val duration = player.duration
                position = if (duration > 0) (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
                delay(500)
            }
        }
        Column(
            modifier = Modifier
                .width(340.dp)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = artwork,
                    contentDescription = stringResource(R.string.plus_island_open),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color.DarkGray),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artist,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Equalizer(playing = isPlaying)
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { position },
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IslandButton(R.drawable.skip_previous, 32.dp) { player.seekToPrevious() }
                IslandButton(if (isPlaying) R.drawable.pause else R.drawable.play, 40.dp) { togglePlay() }
                IslandButton(R.drawable.skip_next, 32.dp) { player.seekToNext() }
            }
        }
    }

    @Composable
    private fun IslandButton(icon: Int, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
        IconButton(onClick = onClick, modifier = Modifier.size(size + 16.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(size))
        }
    }

    /** Steps a few times per second instead of animating every frame, to save battery. */
    @Composable
    private fun Equalizer(playing: Boolean, modifier: Modifier = Modifier) {
        var heights by remember { mutableStateOf(listOf(0.4f, 0.8f, 0.6f, 0.9f)) }
        LaunchedEffect(playing) {
            if (!playing) {
                heights = listOf(0.25f, 0.25f, 0.25f, 0.25f)
                return@LaunchedEffect
            }
            while (true) {
                heights = List(4) { 0.3f + Random.nextFloat() * 0.7f }
                delay(350)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.height(16.dp),
        ) {
            heights.forEach { h ->
                Box(
                    Modifier
                        .width(3.dp)
                        .height(16.dp * h)
                        .clip(CircleShape)
                        .background(Color(0xFF1ED760)),
                )
            }
        }
    }

    /** Root view of the overlay window: a tap outside collapses the card. */
    private inner class IslandRoot(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                if (expanded) {
                    expanded = false
                    post { update() }
                }
                return false
            }
            return super.dispatchTouchEvent(event)
        }
    }

    private class OverlayLifecycleOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun create() {
            savedStateController.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
