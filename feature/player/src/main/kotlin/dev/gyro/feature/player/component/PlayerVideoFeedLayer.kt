package dev.gyro.feature.player.component

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.VerticalSwipeMode
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.api.session.PlaybackSessionState
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.trace.SwitchTrace
import dev.gyro.engine.ui.GyroPlayerSurface
import dev.gyro.engine.ui.GyroSurfaceView
import dev.gyro.feature.player.PlayerState
import dev.gyro.feature.player.PlayerViewportState
import dev.gyro.feature.player.gesture.PlayerZoomState
import dev.gyro.feature.player.loadVideoThumbnail
import dev.gyro.feature.player.videoSurfacePageOffset
import kotlin.math.roundToInt

/**
 * Renders the primary video canvas and Douyin-style vertical swipe feed:
 * - Persistent [GyroPlayerSurface] translating in lockstep with pager drag
 * - Aspect ratio, cover size, and bottom-sheet upward shift calculation
 * - [VerticalPager] with cached video poster thumbnails
 * - Playback error card presentation
 */
@Composable
internal fun BoxScope.PlayerVideoFeedLayer(
    engine: PlayerEngine,
    snapshot: PlaybackSnapshot,
    playlist: List<VideoMediaItem>,
    currentPlaylistIndex: Int,
    activeUri: Uri?,
    playerState: PlayerState,
    viewport: PlayerViewportState,
    zoomState: PlayerZoomState,
    pagerState: PagerState,
    thumbnailCache: Map<String, Bitmap>,
    gestureConfig: PlayerGestureConfig,
    gestureModifier: Modifier,
    isPipLayout: Boolean,
    isLandscape: Boolean,
    pipTransitionActive: Boolean,
    onSurfaceViewAvailable: (GyroSurfaceView?) -> Unit,
    onRetryPlayback: () -> Unit,
) {
    val density = LocalDensity.current

    val videoAspect = snapshot.videoAspect
    val videoCoverSize = remember(viewport.containerSize, videoAspect) {
        if (viewport.containerSize == IntSize.Zero || videoAspect <= 0f) null
        else if (viewport.containerSize.width.toFloat() / viewport.containerSize.height > videoAspect) {
            IntSize(viewport.containerSize.width, (viewport.containerSize.width / videoAspect).toInt())
        } else {
            IntSize((viewport.containerSize.height * videoAspect).toInt(), viewport.containerSize.height)
        }
    }

    // In PiP mode, always fit to the native video aspect ratio to perfectly fill the PiP floating window
    val effectiveResizeMode = if (isPipLayout) ResizeMode.FIT else snapshot.resizeMode
    val videoContainerModifier = when (effectiveResizeMode) {
        ResizeMode.FIT -> if (videoAspect > 0f) Modifier.aspectRatio(videoAspect) else Modifier.fillMaxSize()
        ResizeMode.ASPECT_16_9 -> Modifier.aspectRatio(16f / 9f)
        ResizeMode.ASPECT_21_9 -> Modifier.aspectRatio(21f / 9f)
        ResizeMode.CROP -> videoCoverSize?.let { sz ->
            with(density) { Modifier.size(sz.width.toDp(), sz.height.toDp()) }
        } ?: Modifier.fillMaxSize()
        ResizeMode.STRETCH -> Modifier.fillMaxSize()
    }

    val animatedSheetShiftY by animateFloatAsState(
        targetValue = if (playerState.activeSheetTab != null && !isLandscape && !isPipLayout) {
            -with(density) { 72.dp.toPx() }
        } else {
            0f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sheetSurfaceShiftY"
    )

    // How far the persistent surface is dragged away from the page it belongs to, in pages.
    // Negative = scrolled up = the surface follows the finger off the top of the screen.
    // The owner comes from the pager, not from currentPlaylistIndex: that index round-trips
    // through the ViewModel, and while it lags the surface parks a full page off-screen (black
    // video while the engine keeps presenting frames).
    val pageOffset by remember {
        derivedStateOf {
            videoSurfacePageOffset(
                currentPage = pagerState.currentPage,
                pageOffsetFraction = pagerState.currentPageOffsetFraction,
                settledPage = pagerState.settledPage,
            )
        }
    }

    // A1. Persistent GyroPlayerSurface
    GyroPlayerSurface(
        modifier = videoContainerModifier
            .align(Alignment.Center)
            .onGloballyPositioned { coords ->
                val windowBounds = coords.boundsInWindow()
                val windowRect = if (windowBounds.width > 0 && windowBounds.height > 0) {
                    Rect(
                        windowBounds.left.roundToInt(),
                        windowBounds.top.roundToInt(),
                        windowBounds.right.roundToInt(),
                        windowBounds.bottom.roundToInt()
                    )
                } else null
                val sizeChanged = viewport.onVideoContainerPlaced(
                    coords.positionInParent(),
                    coords.size,
                    windowRect
                )
                if (sizeChanged) {
                    SwitchTrace.log(
                        "CONTAINER_LAYOUT_CHANGED",
                        "${coords.size.width}x${coords.size.height} aspect=${if (coords.size.height > 0) coords.size.width.toFloat() / coords.size.height else 0f}"
                    )
                }
            }
            .graphicsLayer {
                val containerH = viewport.containerSize.height.toFloat()
                val offsetY = if (containerH > 0f) pageOffset * containerH else 0f
                scaleX = zoomState.scale
                scaleY = zoomState.scale
                translationX = (viewport.videoContainerOrigin.x - zoomState.contentOffset.x) * zoomState.scale - viewport.videoContainerOrigin.x
                translationY = offsetY + animatedSheetShiftY +
                    ((viewport.videoContainerOrigin.y - zoomState.contentOffset.y) * zoomState.scale - viewport.videoContainerOrigin.y)
                transformOrigin = zoomState.transformOrigin
            },
        engine = engine,
        videoDimensions = snapshot.videoDimensions,
        videoFrameRate = snapshot.videoFrameRate,
        pipTransitionActive = pipTransitionActive,
        onSurfaceViewAvailable = onSurfaceViewAvailable,
        onAppliedBufferSizeChanged = { viewport.onAppliedBufferSizeChanged(it) }
    )

    val activePlaylistItem = playlist.getOrNull(currentPlaylistIndex)
    val activeUriString = activeUri?.toString()
    val activeIsInPlaylist = activeUriString != null && playlist.any { it.uriString == activeUriString }
    val isCurrentVideoReady = snapshot.firstFrameRendered &&
        snapshot.sessionState !is PlaybackSessionState.Switching &&
        (!activeIsInPlaylist || activePlaylistItem?.uriString == activeUriString)

    // A2. VerticalPager for Douyin-style feed
    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = !playerState.isLocked &&
            !isPipLayout &&
            gestureConfig.verticalSwipeMode == VerticalSwipeMode.SWITCH_VIDEO &&
            zoomState.scale <= 1.05f,
        key = { page -> playlist.getOrNull(page)?.uriString ?: page }
    ) { page ->
        val item = playlist.getOrNull(page)
        val isActive = when {
            currentPlaylistIndex in playlist.indices -> page == currentPlaylistIndex
            playlist.isEmpty() -> page == pagerState.currentPage
            else -> playlist.getOrNull(page)?.uriString == activeUriString
        }
        val showPoster = !isActive || !isCurrentVideoReady

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isActive) gestureModifier else Modifier),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = showPoster,
                enter = fadeIn(tween(100)),
                exit = fadeOut(tween(150))
            ) {
                VideoPagePoster(
                    item = item,
                    thumbnailCache = thumbnailCache,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // A3. Playback error card
    val error = if (snapshot.playbackState == PlaybackState.ERROR) snapshot.error else null
    if (error != null && !isPipLayout) {
        PlayerPlaybackErrorCard(
            error = error,
            onRetry = onRetryPlayback,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
        )
    }
}

@Composable
private fun VideoPagePoster(
    item: VideoMediaItem?,
    thumbnailCache: Map<String, Bitmap> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uriStr = item?.uriString
    val cachedBitmap = uriStr?.let { thumbnailCache[it] }
    var bitmap by remember(uriStr) { mutableStateOf(cachedBitmap) }

    LaunchedEffect(uriStr, cachedBitmap) {
        if (cachedBitmap != null) {
            bitmap = cachedBitmap
            return@LaunchedEffect
        }
        val loaded = uriStr?.let { loadVideoThumbnail(context, it) } ?: return@LaunchedEffect
        bitmap = loaded
        (thumbnailCache as? MutableMap<String, Bitmap>)?.put(uriStr, loaded)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}
