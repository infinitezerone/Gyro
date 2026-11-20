package dev.gyro.feature.player.component

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.VerticalSwipeMode
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.controller.RenderController
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.trace.SwitchTrace
import dev.gyro.feature.player.MAX_POSTER_CACHE_SIZE
import dev.gyro.feature.player.PlayerState
import dev.gyro.feature.player.PlayerViewportState
import dev.gyro.feature.player.event.PlaybackFailureReason
import dev.gyro.feature.player.gesture.PlayerZoomState
import dev.gyro.feature.player.rememberPlayerShutterState
import dev.gyro.feature.player.sheetFitTransform
import dev.gyro.feature.player.surface.GyroPlayerSurface
import dev.gyro.feature.player.surface.GyroSurfaceView
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
    renderController: RenderController,
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
    freezeViewportFullscreen: Boolean = false,
    failureReason: PlaybackFailureReason? = null,
    onLoadPoster: suspend (String) -> Bitmap?,
    onSurfaceViewAvailable: (GyroSurfaceView?) -> Unit,
    onRetryPlayback: () -> Unit,
    onSkipNext: (() -> Unit)? = null,
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
        ResizeMode.ASPECT_4_3 -> Modifier.aspectRatio(4f / 3f)
        ResizeMode.CROP -> videoCoverSize?.let { sz ->
            with(density) { Modifier.size(sz.width.toDp(), sz.height.toDp()) }
        } ?: Modifier.fillMaxSize()
        ResizeMode.STRETCH -> Modifier.fillMaxSize()
    }

    // Portrait bottom sheet: the picture is fitted into the space the sheet leaves instead of
    // being nudged by a fixed offset. Reading sheetState.visibleHeightPx inside graphicsLayer
    // keeps the video frame-locked with the sheet's AnchoredDraggableState without recomposition.
    val screenW = viewport.containerSize.width.toFloat()
    val screenH = viewport.containerSize.height.toFloat()
    val videoDisplayHeightPx = when (effectiveResizeMode) {
        ResizeMode.CROP -> videoCoverSize?.height?.toFloat() ?: screenH
        ResizeMode.STRETCH -> screenH
        ResizeMode.ASPECT_16_9 -> minOf(screenH, screenW / (16f / 9f))
        ResizeMode.ASPECT_4_3 -> minOf(screenH, screenW / (4f / 3f))
        ResizeMode.FIT -> if (videoAspect > 0f) minOf(screenH, screenW / videoAspect) else screenH
    }

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
                val windowRect = if (!freezeViewportFullscreen && windowBounds.width > 0 && windowBounds.height > 0) {
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
                translationY = offsetY +
                    ((viewport.videoContainerOrigin.y - zoomState.contentOffset.y) * zoomState.scale - viewport.videoContainerOrigin.y)
                transformOrigin = zoomState.transformOrigin
            }
            // Sheet fit sits outside the zoom transform on purpose: it scales the already-zoomed
            // picture about the container centre and lifts it into the gap above the sheet, without
            // re-deriving the zoom/origin arithmetic above.
            .graphicsLayer {
                val visibleSheetPx = if (!isLandscape && !isPipLayout) {
                    playerState.currentSheetVisibleHeightPx()
                } else {
                    0f
                }
                val fit = sheetFitTransform(
                    videoHeightPx = videoDisplayHeightPx,
                    screenHeightPx = screenH,
                    sheetHeightPx = visibleSheetPx,
                    active = visibleSheetPx > 0f,
                )
                scaleX = fit.scale
                scaleY = fit.scale
                translationY = fit.shiftY
            },
        renderController = renderController,
        isHdr = snapshot.isHdr,
        videoDimensions = snapshot.videoDimensions,
        videoFrameRate = snapshot.videoFrameRate,
        pipTransitionActive = pipTransitionActive,
        onSurfaceViewAvailable = onSurfaceViewAvailable,
        onAppliedBufferSizeChanged = { viewport.onAppliedBufferSizeChanged(it) }
    )

    val activePlaylistItem = playlist.getOrNull(currentPlaylistIndex)
    val activeUriString = activeUri?.toString()
    val activeIsInPlaylist = activeUriString != null && playlist.any { it.uriString == activeUriString }
    val shutterState = rememberPlayerShutterState(
        activeUri = activeUri,
        isSwitching = snapshot.session.isSwitching,
        firstFrameRendered = snapshot.firstFrameRendered,
        geometryReady = snapshot.geometryReady,
        videoAspect = videoAspect,
        videoWidth = snapshot.videoDimensions.width,
        videoHeight = snapshot.videoDimensions.height,
        resizeMode = effectiveResizeMode,
        containerSize = viewport.videoContainerSize,
        bufferSize = viewport.appliedBufferSize,
    )
    val shutterCovers = shutterState.covers(
        activeUri = activeUri,
        playbackState = snapshot.playbackState,
        isPipLayout = isPipLayout,
        firstFrameRendered = snapshot.firstFrameRendered,
        isSwitching = snapshot.session.isSwitching,
    )
    val isCurrentVideoReady = !shutterCovers &&
        (!activeIsInPlaylist || activePlaylistItem?.uriString == activeUriString)

    // A2. VerticalPager for Douyin-style feed
    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        // A vertical drag must belong to exactly one handler. With the sheet open its content is
        // what the user is scrolling, so the feed stops claiming vertical drags - otherwise
        // dragging the sheet down also drags the pager, and releasing leaves it parked part-way
        // between two pages with nothing to snap it back.
        userScrollEnabled = playerState.activeSheetTab == null &&
            !playerState.isLocked &&
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
                    onLoadPoster = onLoadPoster,
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
            failureReason = failureReason,
            onSkipNext = onSkipNext,
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
    onLoadPoster: suspend (String) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    val onLoadPosterState = rememberUpdatedState(onLoadPoster)
    val uriStr = item?.uriString
    val cachedBitmap = uriStr?.let { thumbnailCache[it] }
    var bitmap by remember(uriStr) { mutableStateOf(cachedBitmap) }

    LaunchedEffect(uriStr, cachedBitmap) {
        if (cachedBitmap != null) {
            bitmap = cachedBitmap
            return@LaunchedEffect
        }
        val loaded = uriStr?.let { onLoadPosterState.value(it) } ?: return@LaunchedEffect
        bitmap = loaded
        (thumbnailCache as? MutableMap<String, Bitmap>)?.let { cache ->
            if (cache.size >= MAX_POSTER_CACHE_SIZE) {
                cache.keys.firstOrNull()?.let { cache.remove(it) }
            }
            cache[uriStr] = loaded
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            val imageBitmap = remember(currentBitmap) { currentBitmap.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}
