/**
 * Metrolist Plus — "Dynamic Island": píldora flotante alrededor de la cámara mientras la música suena con la app en
 * segundo plano. Toque → tarjeta con controles; toque en la tarjeta → abre la app.
 *
 * Se dibuja con el servicio de accesibilidad [PlusIslandAccessibilityService] (ventana encima de la barra de estado).
 * Si ese servicio está apagado pero hay permiso "Mostrar sobre otras apps", se muestra debajo de la barra de estado,
 * porque una ventana normal queda tapada por los íconos del sistema y no recibe toques ahí.
 *
 * Animaciones: la píldora nace de la cámara al salir de la app y vuelve a ella al regresar; la tarjeta se estira
 * desde la píldora. Todo se anima recortando la forma en la fase de dibujo (graphicsLayer): la ventana del sistema
 * solo cambia de tamaño una vez por transición, porque redimensionarla en cada cuadro se ve a saltos.
 */

package com.metrolist.music.plus.island

import android.annotation.SuppressLint
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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

/**
 * Where and how big the island is, in pixels. The window ("frame") is the pill plus a small margin, so the spring
 * can overshoot a bit past the pill. [x] is the offset from the horizontal center of the screen; [frameY] the top.
 */
private data class IslandGeometry(
    val aroundCamera: Boolean,
    val pillWidth: Int,
    val pillHeight: Int,
    val marginX: Int,
    val marginY: Int,
    val x: Int,
    val frameY: Int,
    val cardWidth: Int,
    val cardX: Int,
    /** Camera hole inside the frame: the pill grows out of it and goes back into it. */
    val dotSize: Int,
    val dotCenterY: Int,
) {
    val frameWidth get() = pillWidth + 2 * marginX
    val frameHeight get() = pillHeight + 2 * marginY
}

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

/** Rounded rect that goes from [from] (t = 0) to the whole layer inset by [inset] (t = 1). Values past 1 overshoot. */
private fun morphShape(
    from: RoundRect,
    insetX: Float,
    insetY: Float,
    toRadius: Float,
    t: Float,
) = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = lerp(from.topLeftCornerRadius.x, toRadius, t.coerceIn(0f, 1f))
        return Outline.Rounded(
            RoundRect(
                left = lerp(from.left, insetX, t),
                top = lerp(from.top, insetY, t),
                right = lerp(from.right, size.width - insetX, t),
                bottom = lerp(from.bottom, size.height - insetY, t),
                cornerRadius = CornerRadius(radius),
            ),
        )
    }
}

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
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var prefJob: Job? = null
    private var hostJob: Job? = null
    private var pausedJob: Job? = null

    // Compose state read by the overlay.
    private var title by mutableStateOf("")
    private var artist by mutableStateOf("")
    private var artwork by mutableStateOf<String?>(null)
    private var isPlaying by mutableStateOf(false)
    private var geometry by mutableStateOf<IslandGeometry?>(null)

    /** What the user asked for: card (true) or pill (false). */
    private var expanded by mutableStateOf(false)

    /** Whether the window is currently laid out as the card. Lags [expanded] while the morph runs. */
    private var cardShown by mutableStateOf(false)

    /** Going back into the camera before the window is removed. */
    private var leaving by mutableStateOf(false)

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
                removeWindow()
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
        removeWindow()
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
        if (root != null) {
            leaving = false
            return
        }
        runCatching {
            val aroundCamera = host is PlusIslandAccessibilityService
            val windowManager = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val geo = computeGeometry(host, windowManager, aroundCamera)
            geometry = geo
            expanded = false
            cardShown = false
            leaving = false

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
            lifecycleOwner = owner
        }.onFailure { Timber.w(it, "Could not show the Dynamic Island") }
    }

    /** Animates back into the camera; the window is removed when the animation ends. */
    private fun hide() {
        if (root == null) return
        expanded = false
        leaving = true
    }

    private fun removeWindow() {
        expanded = false
        cardShown = false
        leaving = false
        val view = root ?: return
        runCatching { rootWindowManager?.removeView(view) }
        lifecycleOwner?.destroy()
        root = null
        rootWindowManager = null
        rootParams = null
        lifecycleOwner = null
    }

    private fun showCard(value: Boolean) {
        if (cardShown == value) return
        cardShown = value
        val view = root ?: return
        val params = rootParams ?: return
        val geo = geometry ?: return
        // The card is wider than the pill: keep it on screen even when the camera is off-center.
        params.x = if (value) geo.cardX else geo.x
        runCatching { rootWindowManager?.updateViewLayout(view, params) }
    }

    private fun hideForThisSong() {
        hiddenForMediaId = player.currentMediaItem?.mediaId
        root?.post { update() }
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun computeGeometry(host: Context, windowManager: WindowManager, aroundCamera: Boolean): IslandGeometry {
        val density = host.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val screenWidth: Int
        var statusBar = 0
        val cutout: Rect?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            statusBar = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
            cutout = metrics.windowInsets.displayCutout?.boundingRectTop?.takeUnless { it.isEmpty }
        } else {
            screenWidth = host.resources.displayMetrics.widthPixels
            cutout = null
        }
        if (statusBar <= 0) {
            val id = host.resources.getIdentifier("status_bar_height", "dimen", "android")
            statusBar = if (id > 0) host.resources.getDimensionPixelSize(id) else dp(24)
        }

        val marginX = dp(6)
        val marginY = dp(3)
        val pillHeight: Int
        val pillWidth: Int
        val x: Int
        val pillTop: Int
        val dotSize: Int
        val dotCenterY: Int
        if (aroundCamera) {
            // Leaves a few dp above and below the pill inside the status bar, like the iPhone.
            pillHeight = (statusBar - dp(10)).coerceIn(dp(22), dp(30))
            val centerY = cutout?.centerY() ?: (statusBar / 2)
            pillTop = (centerY - pillHeight / 2).coerceAtLeast(dp(4))
            x = if (cutout != null) cutout.centerX() - screenWidth / 2 else 0
            // Artwork on the left of the camera, equalizer on the right.
            pillWidth = ((cutout?.width() ?: 0) + 2 * pillHeight + dp(20)).coerceIn(dp(112), dp(150))
            val hole = cutout?.let { minOf(it.width(), it.height()) } ?: dp(12)
            dotSize = hole.coerceIn(dp(8), pillHeight - dp(4))
            dotCenterY = (centerY - pillTop).coerceIn(dotSize / 2, pillHeight - dotSize / 2) + marginY
        } else {
            pillHeight = dp(30)
            pillWidth = dp(140)
            x = 0
            pillTop = statusBar + dp(6)
            dotSize = dp(12)
            dotCenterY = pillHeight / 2 + marginY
        }
        val cardWidth = minOf(dp(360), screenWidth - dp(16))
        val maxCardOffset = (screenWidth - cardWidth) / 2
        return IslandGeometry(
            aroundCamera = aroundCamera,
            pillWidth = pillWidth,
            pillHeight = pillHeight,
            marginX = marginX,
            marginY = marginY,
            x = x,
            frameY = (pillTop - marginY).coerceAtLeast(0),
            cardWidth = cardWidth,
            cardX = x.coerceIn(-maxCardOffset, maxCardOffset),
            dotSize = dotSize,
            dotCenterY = dotCenterY,
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
        y = geo.frameY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun openApp() {
        // onStart() then sends the island back into the camera.
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

    @Composable
    private fun IslandContent() {
        val geo = geometry ?: return
        val appear = remember { Animatable(0f) }
        val morph = remember { Animatable(0f) }

        // A single driver: each phase starts from wherever the previous one was interrupted.
        val phase = when {
            leaving -> 0
            expanded -> 2
            else -> 1
        }
        LaunchedEffect(phase) {
            when (phase) {
                0 -> {
                    if (cardShown) {
                        morph.animateTo(0f, tween(160, easing = FastOutSlowInEasing))
                        showCard(false)
                    }
                    appear.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
                    root?.post { if (leaving) removeWindow() }
                }
                1 -> {
                    if (cardShown) {
                        morph.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
                        showCard(false)
                    }
                    appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
                }
                else -> {
                    appear.snapTo(1f)
                    showCard(true)
                    morph.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
                }
            }
        }
        LaunchedEffect(expanded, isPlaying) {
            if (expanded) {
                delay(AUTO_COLLAPSE_MS)
                expanded = false
            }
        }

        if (cardShown) ExpandedCard(geo) { morph.value } else CollapsedPill(geo) { appear.value }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun CollapsedPill(geo: IslandGeometry, appear: () -> Float) {
        val density = LocalDensity.current
        val pillHeight = with(density) { geo.pillHeight.toDp() }
        val art = pillHeight - 10.dp
        val dot = remember(geo) {
            val cx = geo.frameWidth / 2f
            val r = geo.dotSize / 2f
            RoundRect(cx - r, geo.dotCenterY - r, cx + r, geo.dotCenterY + r, cornerRadius = CornerRadius(r))
        }
        Box(
            modifier = Modifier
                .size(with(density) { geo.frameWidth.toDp() }, with(density) { geo.frameHeight.toDp() })
                .graphicsLayer {
                    clip = true
                    shape = morphShape(dot, geo.marginX.toFloat(), geo.marginY.toFloat(), geo.pillHeight / 2f, appear())
                }
                .background(Color.Black)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = true },
                    onLongClick = { hideForThisSong() },
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = with(density) { geo.marginX.toDp() } + 5.dp)
                    .graphicsLayer { alpha = ((appear() - 0.45f) / 0.55f).coerceIn(0f, 1f) },
            ) {
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(art).clip(RoundedCornerShape(30)).background(Color.DarkGray),
                )
                Spacer(Modifier.weight(1f))
                Equalizer(playing = isPlaying, height = art * 0.65f, modifier = Modifier.padding(end = 4.dp))
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ExpandedCard(geo: IslandGeometry, morph: () -> Float) {
        val density = LocalDensity.current
        var position by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            while (true) {
                val duration = player.duration
                position = if (duration > 0) (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
                delay(500)
            }
        }
        // The pill, as seen inside the (wider) card window.
        val pill = remember(geo) {
            val left = (geo.cardWidth - geo.pillWidth) / 2f + (geo.x - geo.cardX)
            val top = geo.marginY.toFloat()
            RoundRect(left, top, left + geo.pillWidth, top + geo.pillHeight, cornerRadius = CornerRadius(geo.pillHeight / 2f))
        }
        // Content starts below the camera line so the title never sits under the hole.
        val topInset = if (geo.aroundCamera) with(density) { (geo.marginY + geo.pillHeight).toDp() } + 6.dp else 14.dp
        Box(
            modifier = Modifier
                .width(with(density) { geo.cardWidth.toDp() })
                .graphicsLayer {
                    clip = true
                    shape = morphShape(pill, 0f, 0f, 36.dp.toPx(), morph())
                }
                .background(Color.Black)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { openApp() },
                    onLongClick = { hideForThisSong() },
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = ((morph() - 0.35f) / 0.65f).coerceIn(0f, 1f) }
                    .padding(start = 18.dp, end = 18.dp, top = topInset, bottom = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = stringResource(R.string.plus_island_open),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color.DarkGray),
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
                    Spacer(Modifier.width(8.dp))
                    Equalizer(playing = isPlaying)
                }
                Spacer(Modifier.height(14.dp))
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
                if (expanded) post { expanded = false }
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
