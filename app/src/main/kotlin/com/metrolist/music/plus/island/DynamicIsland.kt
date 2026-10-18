/**
 * Metrolist Plus — "Dynamic Island": píldora flotante alrededor de la cámara mientras la música suena con la app en
 * segundo plano. Toque → tarjeta con controles; toque en la tarjeta → abre la app.
 *
 * Se dibuja con el servicio de accesibilidad [PlusIslandAccessibilityService] (ventana encima de la barra de estado).
 * Si ese servicio está apagado pero hay permiso "Mostrar sobre otras apps", se muestra debajo de la barra de estado,
 * porque una ventana normal queda tapada por los íconos del sistema y no recibe toques ahí.
 */

package com.metrolist.music.plus.island

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    val accessibilityOn = PlusIslandAccessibilityService.connected.collectAsState().value != null
    // Re-read on every recomposition: the user comes back from the system permission screen.
    val overlayGranted = context.canShowDynamicIsland()
    val active = enabled && (accessibilityOn || overlayGranted)
    val setActive = { on: Boolean ->
        onEnabledChange(on)
        if (on && !accessibilityOn) context.openIslandAccessibilitySettings()
    }
    return Material3SettingsItem(
        icon = painterResource(R.drawable.plus_island),
        title = { Text(stringResource(R.string.plus_island)) },
        description = {
            Text(
                stringResource(
                    if (enabled && !accessibilityOn) R.string.plus_island_accessibility_needed else R.string.plus_island_description,
                ),
            )
        },
        trailingContent = {
            Switch(
                checked = active,
                onCheckedChange = { setActive(it) },
                thumbContent = {
                    Icon(
                        painter = painterResource(if (active) R.drawable.check else R.drawable.close),
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        },
        onClick = {
            if (enabled && !accessibilityOn) context.openIslandAccessibilitySettings() else setActive(!active)
        },
    )
}

/** Where and how big the island is, in pixels. [x] is the offset from the horizontal center of the screen. */
private data class IslandGeometry(
    val aroundCamera: Boolean,
    val pillWidth: Int,
    val pillHeight: Int,
    val x: Int,
    val y: Int,
    val cardWidth: Int,
    val cardX: Int,
)

/** Created by MusicService on the main thread; [release] must be called from onDestroy. */
class PlusDynamicIsland(
    private val context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
) : Player.Listener, DefaultLifecycleObserver {
    private var enabled = false
    private var appInForeground = true
    private var pausedTooLong = false
    private var hiddenForMediaId: String? = null

    private var root: IslandRoot? = null
    private var rootWindowManager: WindowManager? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var geometry: IslandGeometry? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var prefJob: Job? = null
    private var hostJob: Job? = null
    private var pausedJob: Job? = null

    // Compose state read by the overlay.
    private var title by mutableStateOf("")
    private var artist by mutableStateOf("")
    private var artwork by mutableStateOf<String?>(null)
    private var isPlaying by mutableStateOf(false)
    private var expanded by mutableStateOf(false)
    private var pillWidth by mutableStateOf(116.dp)
    private var pillHeight by mutableStateOf(32.dp)
    private var cardWidth by mutableStateOf(340.dp)
    private var cardTopInset by mutableStateOf(0.dp)

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
        // The accessibility service can be turned on or off at any time: move the window to the new host.
        hostJob = scope.launch {
            PlusIslandAccessibilityService.connected.collect {
                hide()
                update()
            }
        }
    }

    fun release() {
        prefJob?.cancel()
        hostJob?.cancel()
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

    /** The accessibility service when enabled (above the status bar), otherwise the app itself if it may draw overlays. */
    private fun hostContext(): Context? =
        PlusIslandAccessibilityService.connected.value
            ?: context.takeIf { it.canShowDynamicIsland() }

    private fun update() {
        val item = player.currentMediaItem
        val shouldShow = enabled &&
            !appInForeground &&
            item != null &&
            item.mediaId != hiddenForMediaId &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED &&
            (isPlaying || !pausedTooLong || expanded)
        val host = if (shouldShow) hostContext() else null
        if (host != null) show(host) else hide()
    }

    private fun show(host: Context) {
        if (root != null) return
        runCatching {
            val aroundCamera = host is PlusIslandAccessibilityService
            val windowManager = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val geo = computeGeometry(host, windowManager, aroundCamera)
            val density = host.resources.displayMetrics.density
            pillWidth = (geo.pillWidth / density).dp
            pillHeight = (geo.pillHeight / density).dp
            cardWidth = (geo.cardWidth / density).dp
            cardTopInset = if (aroundCamera) pillHeight else 0.dp

            val owner = OverlayLifecycleOwner().also { it.create() }
            val view = IslandRoot(host).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                addView(
                    ComposeView(host).apply {
                        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                        setContent { IslandContent() }
                    },
                )
            }
            val params = layoutParams(geo, aroundCamera)
            windowManager.addView(view, params)
            root = view
            rootWindowManager = windowManager
            rootParams = params
            geometry = geo
            lifecycleOwner = owner
        }.onFailure { Timber.w(it, "Could not show the Dynamic Island") }
    }

    private fun hide() {
        expanded = false
        val view = root ?: return
        runCatching { rootWindowManager?.removeView(view) }
        lifecycleOwner?.destroy()
        root = null
        rootWindowManager = null
        rootParams = null
        geometry = null
        lifecycleOwner = null
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        val view = root ?: return
        val params = rootParams ?: return
        val geo = geometry ?: return
        // The card is wider than the pill: keep it on screen even when the camera is off-center.
        params.x = if (value) geo.cardX else geo.x
        runCatching { rootWindowManager?.updateViewLayout(view, params) }
    }

    private fun computeGeometry(host: Context, windowManager: WindowManager, aroundCamera: Boolean): IslandGeometry {
        val density = host.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val screenWidth: Int
        val statusBar: Int
        var cutout: Rect? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            statusBar = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
            cutout = metrics.windowInsets.displayCutout?.boundingRectTop?.takeUnless { it.isEmpty }
        } else {
            screenWidth = host.resources.displayMetrics.widthPixels
            statusBar = dp(24)
        }

        val pillHeight: Int
        val pillWidth: Int
        val x: Int
        val y: Int
        if (aroundCamera) {
            val camera = cutout
            if (camera != null) {
                pillHeight = maxOf(camera.height() + dp(8), dp(28))
                // Artwork on the left of the camera, equalizer on the right.
                pillWidth = maxOf(dp(116), camera.width() + 2 * pillHeight + dp(8))
                x = camera.centerX() - screenWidth / 2
                y = maxOf(0, camera.centerY() - pillHeight / 2)
            } else {
                pillHeight = maxOf(statusBar - dp(4), dp(24))
                pillWidth = dp(116)
                x = 0
                y = maxOf(0, (statusBar - pillHeight) / 2)
            }
        } else {
            pillHeight = dp(34)
            pillWidth = dp(150)
            x = 0
            y = statusBar + dp(4)
        }
        val cardWidth = minOf(dp(360), screenWidth - dp(16))
        val maxCardOffset = (screenWidth - cardWidth) / 2
        return IslandGeometry(
            aroundCamera = aroundCamera,
            pillWidth = pillWidth,
            pillHeight = pillHeight,
            x = x,
            y = y,
            cardWidth = cardWidth,
            cardX = x.coerceIn(-maxCardOffset, maxCardOffset),
        )
    }

    private fun layoutParams(geo: IslandGeometry, aroundCamera: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (aroundCamera) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = geo.x
        y = geo.y
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun openApp() {
        // The window goes away in onStart() when the app comes to the front.
        setExpanded(false)
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
                setExpanded(false)
                update()
            }
        }
        // The window is WRAP_CONTENT: its size changes once (snap) and only the content animates. Animating the size
        // would resize the system window on every frame, which stutters.
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                (fadeIn(tween(160)) + scaleIn(tween(240), initialScale = 0.85f, transformOrigin = TransformOrigin(0.5f, 0f)))
                    .togetherWith(fadeOut(tween(100)))
                    .using(SizeTransform(clip = false) { _, _ -> snap() })
            },
            label = "island",
        ) { isExpanded ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(if (isExpanded) 32.dp else 50.dp))
                    .background(Color.Black)
                    .combinedClickable(
                        onClick = { if (isExpanded) openApp() else setExpanded(true) },
                        onLongClick = {
                            // Hide until the next song.
                            hiddenForMediaId = player.currentMediaItem?.mediaId
                            root?.post { update() }
                        },
                    ),
            ) {
                if (isExpanded) ExpandedCard() else CollapsedPill()
            }
        }
    }

    @Composable
    private fun CollapsedPill() {
        val artSize = pillHeight - 8.dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .width(pillWidth)
                .height(pillHeight)
                .padding(horizontal = 4.dp),
        ) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(artSize).clip(CircleShape).background(Color.DarkGray),
            )
            Spacer(Modifier.weight(1f))
            Equalizer(playing = isPlaying, height = minOf(16.dp, artSize), modifier = Modifier.padding(end = 6.dp))
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
                .width(cardWidth)
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 12.dp + cardTopInset),
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
    private fun IslandButton(icon: Int, size: Dp, onClick: () -> Unit) {
        IconButton(onClick = onClick, modifier = Modifier.size(size + 16.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(size))
        }
    }

    @Composable
    private fun Equalizer(playing: Boolean, height: Dp = 16.dp, modifier: Modifier = Modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.height(height),
        ) {
            if (playing) AnimatedBars(height) else repeat(4) { Bar(height, 0.3f) }
        }
    }

    /** Scales the bars in the draw phase (graphicsLayer), so the smooth animation never recomposes. */
    @Composable
    private fun AnimatedBars(height: Dp) {
        val transition = rememberInfiniteTransition(label = "equalizer")
        listOf(420, 300, 520, 360).forEachIndexed { index, duration ->
            val scale by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(duration, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar$index",
            )
            Box(
                Modifier
                    .width(3.dp)
                    .height(height)
                    .graphicsLayer { scaleY = scale }
                    .clip(CircleShape)
                    .background(Color(0xFF1ED760)),
            )
        }
    }

    @Composable
    private fun Bar(height: Dp, fraction: Float) {
        Box(
            Modifier
                .width(3.dp)
                .height(height * fraction)
                .clip(CircleShape)
                .background(Color(0xFF1ED760)),
        )
    }

    /** Root view of the overlay window: a tap outside collapses the card. */
    private inner class IslandRoot(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                if (expanded) post { setExpanded(false) }
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
