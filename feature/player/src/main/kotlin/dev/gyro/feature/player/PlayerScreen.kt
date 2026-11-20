package dev.gyro.feature.player

import android.app.Activity
import android.graphics.Rect
import android.net.Uri
import android.util.Rational
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.gyro.core.common.toUri
import dev.gyro.core.domain.session.PlaylistRepeatMode
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.TrackType
import dev.gyro.feature.player.surface.GyroSurfaceView
import dev.gyro.feature.player.component.PlayerBufferingSpinner
import dev.gyro.feature.player.component.PlayerControlsOverlay
import dev.gyro.feature.player.component.PlayerEmptyStateCard
import dev.gyro.feature.player.component.PlayerHudOverlay
import dev.gyro.feature.player.component.PlayerPipLifecycleEffects
import dev.gyro.feature.player.component.PlayerStatsOverlay
import dev.gyro.feature.player.component.PlayerSystemEffects
import dev.gyro.feature.player.component.PlayerVideoFeedLayer
import dev.gyro.feature.player.component.rememberLandscapeHorizontalInset
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import dev.gyro.feature.player.event.PlaybackTriggerSource
import dev.gyro.feature.player.event.PlayerEvent

/**
 * Top-level Player destination Composable.
 * Orchestrates player states and delegates visual and lifecycle concerns into modular layers:
 * - [PlayerSystemEffects]: Window flags, immersive bars, color gamut, power monitor
 * - [PlayerPipLifecycleEffects]: Picture-in-Picture negotiation, broadcast receiver, orientation memory
 * - [PlayerVideoFeedLayer]: Video surface, aspect ratio, vertical feed pager, poster thumbnails
 * - Direct overlays: Control chrome, gesture HUD, diagnostic telemetry, bottom sheets
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    currentUri: Uri? = null,
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val componentActivity = remember(context) { context.findComponentActivity() }
    val activity: Activity? = componentActivity
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val playerState = rememberPlayerState(activity = activity)

    // 1. Session & Playback States
    val snapshot by viewModel.state.collectAsStateWithLifecycle()
    val playbackState = snapshot.playbackState
    val isPlaying = snapshot.isPlaying
    val durationMs = snapshot.durationMs

    // Sticky for the lifetime of this screen instance: a session teardown must never be able to
    // make an already-playing screen look like it was opened empty.
    var hasEverHadContent by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(playbackState, durationMs) {
        if (durationMs > 0L || playbackState != PlaybackState.IDLE) {
            hasEverHadContent = true
        }
    }
    val timePosState = viewModel.timePosMs.collectAsStateWithLifecycle()
    val bufferedPosState = viewModel.bufferedPosMs.collectAsStateWithLifecycle()
    val hwdecMode = snapshot.hwdecMode
    val speed = snapshot.speed
    val tracks = snapshot.tracks
    val resizeMode = snapshot.resizeMode
    val videoDimensions = snapshot.videoDimensions
    val videoAspect = snapshot.videoAspect
    val mediaTitle = snapshot.mediaTitle
    val chapters = snapshot.chapters
    val hdrFormat = snapshot.hdrFormat

    val liveSettings by viewModel.settings.collectAsStateWithLifecycle()
    val failureReason by viewModel.failureReason.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel, context) {
        viewModel.events.collect { event ->
            if (event is PlayerEvent.HwdecAutoFallback) {
                Toast.makeText(
                    context,
                    context.getString(dev.gyro.core.designsystem.R.string.player_hwdec_fallback_toast),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Subtitle quick-toggle state
    val subtitleEnabled = tracks.any { it.type == TrackType.SUBTITLE && it.isSelected }
    var lastSubTrackId by remember { mutableIntStateOf(-1) }
    LaunchedEffect(tracks) {
        tracks.firstOrNull { it.type == TrackType.SUBTITLE && it.isSelected }?.let { lastSubTrackId = it.id }
    }

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isLifecycleStarted = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    // Telemetry & power sampling states (active strictly when stats UI is open and screen is started)
    val isPowerSheetActive =
        isLifecycleStarted && playerState.sheetNav?.current == PlayerSheetRoute.STATS_INFO
    val isStatsActive = isLifecycleStarted && (playerState.statsOverlayEnabled || isPowerSheetActive)
    val telemetryState = rememberPlayerTelemetryState(
        diagnosticsFlow = viewModel.diagnostics,
        isActive = isPlaying && isStatsActive,
    )
    val powerState = rememberPlayerPowerState(
        isActive = isPowerSheetActive,
        samplePower = viewModel::samplePowerMetrics,
    )

    var surfaceViewRef by remember { mutableStateOf<GyroSurfaceView?>(null) }
    val screenshotController = rememberPlayerScreenshotController(
        onSaveScreenshot = viewModel::saveScreenshot,
    )

    val vmActiveUri by viewModel.activeUri.collectAsStateWithLifecycle()
    val engineCurrentUri = snapshot.currentUri
    val activeUri = vmActiveUri ?: engineCurrentUri ?: currentUri

    val playlistMgr = viewModel.playlistManager
    val playlist by playlistMgr.items.collectAsStateWithLifecycle()
    val currentPlaylistIndex by playlistMgr.currentIndex.collectAsStateWithLifecycle()
    val playlistRepeatMode by viewModel.playlistRepeatMode.collectAsStateWithLifecycle()
    val hasPrevious = playlistMgr.hasPrevious
    // 列表循环时最后一集也有“下一个”（回到第一集），否则按钮会在循环开启后莫名消失。
    val hasNext = playlistMgr.hasNext ||
        (playlistRepeatMode == PlaylistRepeatMode.LOOP_ALL && playlist.size > 1)

    val loadPosterForUriString: suspend (String) -> android.graphics.Bitmap? = remember(viewModel) {
        { uriStr ->
            val parsed = runCatching { uriStr.toUri() }.getOrNull()
            if (parsed != null) viewModel.loadPosterBitmap(parsed) else null
        }
    }

    // Thumbnail cache & dynamic palette (bounded sliding-window preloader around active item)
    val thumbnailCache = rememberThumbnailCache()
    LaunchedEffect(playlist, currentPlaylistIndex, activeUri) {
        val activeStr = activeUri?.toString()
        val candidateUris = buildList {
            if (activeStr != null) add(activeStr)
            val center = currentPlaylistIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
            for (offset in listOf(0, 1, -1, 2, -2)) {
                val idx = center + offset
                if (idx in playlist.indices) {
                    add(playlist[idx].uriString)
                }
            }
        }.distinct()

        for (uriStr in candidateUris) {
            if (!thumbnailCache.containsKey(uriStr)) {
                loadPosterForUriString(uriStr)?.let { bitmap ->
                    if (thumbnailCache.size >= MAX_POSTER_CACHE_SIZE) {
                        val evictKey = thumbnailCache.keys.firstOrNull { it !in candidateUris }
                        if (evictKey != null) {
                            thumbnailCache.remove(evictKey)
                        }
                    }
                    thumbnailCache[uriStr] = bitmap
                }
            }
        }
    }
    val currentPosterBitmap = activeUri?.let { thumbnailCache[it.toString()] }

    fun openUri(uri: Uri) {
        val item = playlist.firstOrNull { it.uriString == uri.toString() }
        viewModel.openUri(
            uri,
            initialWidth = item?.width ?: 0,
            initialHeight = item?.height ?: 0
        )
    }

    // Aspect ratio & Viewport calculations
    val targetAspectRatio = remember(videoDimensions, videoAspect) {
        val ratio = when {
            videoAspect > 0f -> videoAspect
            videoDimensions.height > 0 -> videoDimensions.width.toFloat() / videoDimensions.height
            else -> 16f / 9f
        }.coerceIn(0.42f, 2.38f)
        Rational((ratio * 1000).toInt(), 1000)
    }

    val viewport = rememberPlayerViewportState()
    val videoSourceRect = remember(viewport.videoBoundsInWindow, viewport.fullscreenContainerSize, targetAspectRatio, resizeMode) {
        viewport.videoBoundsInWindow ?: if (viewport.fullscreenContainerSize.width > 0 && viewport.fullscreenContainerSize.height > 0) {
            calculateVideoSourceRect(
                Rect(
                    0,
                    0,
                    viewport.fullscreenContainerSize.width,
                    viewport.fullscreenContainerSize.height
                ),
                targetAspectRatio,
                resizeMode
            )
        } else null
    }

    // Picture-in-Picture State
    var showControlsAfterPipDismiss by remember { mutableStateOf(false) }
    val pipState = rememberPlayerPipState(
        activity = componentActivity,
        isPlaying = isPlaying,
        autoPipEnabled = liveSettings.autoPip,
        targetAspectRatio = targetAspectRatio,
        videoSourceRect = videoSourceRect,
        onControlsVisibilityChange = { playerState.showControls = it },
        onPipDismissed = {
            viewModel.onPipDismissed()
            showControlsAfterPipDismiss = true
        }
    )
    val isPipLayout = pipState.isPipLayout
    val hideChrome = pipState.shouldHideChrome
    val freezeViewportFullscreen = hideChrome || pipState.isTransitionActive

    LaunchedEffect(isPipLayout) {
        if (isPipLayout) {
            playerState.activeSheetTab = null
        }
    }

    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val landscapeHorizontalInset = if (isLandscape) rememberLandscapeHorizontalInset() else 0.dp
    val overlayActions = rememberPlayerScreenActions(
        viewModel = viewModel,
        playerState = playerState,
        screenshotController = screenshotController,
        activity = componentActivity,
        configuration = configuration,
        onBack = onBack,
        durationMsProvider = { durationMs },
        timePosMsProvider = { timePosState.value },
        surfaceViewProvider = { surfaceViewRef },
        mediaTitleProvider = { snapshot.mediaTitle },
        subtitleEnabled = subtitleEnabled,
        lastSubTrackId = lastSubTrackId,
        seekStepSeconds = liveSettings.seekStepSeconds,
        onEnterPiP = { pipState.enterPip() }
    )

    val screenEntryEpoch by viewModel.screenEntryEpoch.collectAsStateWithLifecycle()
    LaunchedEffect(screenEntryEpoch) {
        playerState.showControls = true
    }

    val scrubState = playerState.scrubState
    val zoomState = playerState.zoomState

    // File pickers
    val subtitlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let(viewModel::addExternalSubtitle)
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.setSingleAndPlay(it) }
    }

    val shaderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.setCustomShader(it) }
    }

    val density = LocalDensity.current
    val videoBottomDp = remember(viewport.videoContainerOrigin, viewport.videoContainerSize, density) {
        if (viewport.videoContainerSize.height > 0) {
            with(density) { (viewport.videoContainerOrigin.y + viewport.videoContainerSize.height).toDp() }
        } else null
    }

    val composeVideoBounds = remember(viewport.videoContainerOrigin, viewport.videoContainerSize, videoSourceRect) {
        if (viewport.videoContainerSize.width > 0 && viewport.videoContainerSize.height > 0) {
            androidx.compose.ui.geometry.Rect(
                left = viewport.videoContainerOrigin.x,
                top = viewport.videoContainerOrigin.y,
                right = viewport.videoContainerOrigin.x + viewport.videoContainerSize.width.toFloat(),
                bottom = viewport.videoContainerOrigin.y + viewport.videoContainerSize.height.toFloat()
            )
        } else {
            videoSourceRect?.let {
                androidx.compose.ui.geometry.Rect(
                    left = it.left.toFloat(),
                    top = it.top.toFloat(),
                    right = it.right.toFloat(),
                    bottom = it.bottom.toFloat()
                )
            }
        }
    }

    val dynamicConfig = liveSettings.gestureConfig
    val gestureConfig = remember(dynamicConfig, liveSettings.gesturesEnabled, playerState.isLocked) {
        if (!liveSettings.gesturesEnabled) {
            dynamicConfig.copy(
                isLocked = playerState.isLocked,
                isVolumeEnabled = false,
                isBrightnessEnabled = false,
                isSeekEnabled = false,
                isDoubleTapSeekEnabled = false,
                isDoubleTapPlayPauseEnabled = false,
                isLongPressSpeedEnabled = false,
                isZoomEnabled = false
            )
        } else {
            dynamicConfig.copy(isLocked = playerState.isLocked)
        }
    }

    val rawGestureModifier = rememberPlayerGestureModifier(
        context = context,
        isLandscape = isLandscape,
        isPipActive = isPipLayout || hideChrome,
        activeUri = activeUri,
        playerState = playerState,
        isPlaying = isPlaying,
        currentPosMsProvider = { timePosState.value },
        durationMs = durationMs,
        speed = speed,
        scope = scope,
        zoomState = zoomState,
        scrubState = scrubState,
        containerSize = viewport.containerSize,
        composeVideoBounds = composeVideoBounds,
        viewModel = viewModel,
        gestureConfig = gestureConfig
    )
    val gestureModifier = if (!isPipLayout) rawGestureModifier else Modifier

    // Vertical Pager sync
    val pageCount = playlist.size.coerceAtLeast(1)
    val initialPage = currentPlaylistIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pageCount }
    )
    var isProgrammaticScroll by remember { mutableStateOf(false) }
    var userInitiatedScroll by remember { mutableStateOf(false) }
    val latestPlaylist = rememberUpdatedState(playlist)
    val latestCurrentPlaylistIndex = rememberUpdatedState(currentPlaylistIndex)
    LaunchedEffect(currentPlaylistIndex, pageCount) {
        if (currentPlaylistIndex in 0 until pageCount && pagerState.currentPage != currentPlaylistIndex && !pagerState.isScrollInProgress) {
            isProgrammaticScroll = true
            userInitiatedScroll = false
            try {
                pagerState.scrollToPage(currentPlaylistIndex)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { Triple(pagerState.isScrollInProgress, pagerState.settledPage, pagerState.currentPage) }
            .collect { (isScrollInProgress, settledPage, _) ->
                if (isScrollInProgress && !isProgrammaticScroll) {
                    userInitiatedScroll = true
                } else if (!isScrollInProgress && userInitiatedScroll) {
                    userInitiatedScroll = false
                    val activePlaylist = latestPlaylist.value
                    val activeIndex = latestCurrentPlaylistIndex.value
                    if (settledPage in activePlaylist.indices && settledPage != activeIndex) {
                        viewModel.playAtIndex(settledPage, source = PlaybackTriggerSource.SHORT_VIDEO_SWIPE)
                    }
                }
            }
    }
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress && !isProgrammaticScroll) {
            userInitiatedScroll = true
            playerState.showControls = false
            scrubState.exitPreciseSeeking()
        }
    }
    LaunchedEffect(currentPlaylistIndex, activeUri) {
        scrubState.exitPreciseSeeking()
    }

    // 2. Delegate System & Window Effects
    PlayerSystemEffects(
        activity = activity,
        componentActivity = componentActivity,
        playerState = playerState,
        isPlaying = isPlaying,
        isPipLayout = isPipLayout,
        isLandscape = isLandscape,
        isHdr = snapshot.isHdr,
        scrubState = scrubState,
        onBack = onBack,
        onResetAudioGain = { viewModel.resetAudioSoftwareGain() }
    )

    // 3. Delegate PiP Lifecycle Effects
    PlayerPipLifecycleEffects(
        activity = activity,
        playerState = playerState,
        zoomState = zoomState,
        viewport = viewport,
        composeVideoBounds = composeVideoBounds,
        isPipLayout = isPipLayout,
        hideChrome = hideChrome,
        isLandscape = isLandscape,
        onTogglePlayPause = { overlayActions.onTogglePlayPause() },
        showControlsAfterPipDismiss = showControlsAfterPipDismiss,
        onConsumeShowControlsAfterPipDismiss = { showControlsAfterPipDismiss = false },
    )

    // 4. Main Player Canvas Box
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer {
                val progress = playerState.backProgress
                if (progress > 0f) {
                    val scale = 1f - (progress * 0.1f)
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - (progress * 0.2f)
                }
            }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_SPACE,
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            overlayActions.onTogglePlayPause()
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .clipToBounds()
            .onSizeChanged { viewport.onContainerSized(it, freezeViewportFullscreen) }
    ) {
        // 1. Video Canvas & Vertical Pager Feed
        PlayerVideoFeedLayer(
            renderController = viewModel.renderController,
            snapshot = snapshot,
            playlist = playlist,
            currentPlaylistIndex = currentPlaylistIndex,
            activeUri = activeUri,
            playerState = playerState,
            viewport = viewport,
            zoomState = zoomState,
            pagerState = pagerState,
            thumbnailCache = thumbnailCache,
            gestureConfig = gestureConfig,
            gestureModifier = gestureModifier,
            isPipLayout = isPipLayout,
            isLandscape = isLandscape,
            pipTransitionActive = pipState.isTransitionActive,
            freezeViewportFullscreen = freezeViewportFullscreen,
            failureReason = failureReason,
            onLoadPoster = loadPosterForUriString,
            onSurfaceViewAvailable = { surfaceViewRef = it },
            onRetryPlayback = { activeUri?.let { openUri(it) } },
            onSkipNext = if (hasNext) ({ viewModel.playNext() }) else null,
        )

        // 2. Buffering Spinner
        if (playbackState == PlaybackState.BUFFERING && !isPipLayout) {
            PlayerBufferingSpinner(modifier = Modifier.align(Alignment.Center))
        }

        // 3. Empty State Card
        //
        // Only for a player that was opened with nothing to play. Once a file has been shown,
        // leaving the screen tears the session down, and the still-composed player would repaint
        // itself as "nothing to play" for the length of the exit transition.
        if (!hasEverHadContent && playbackState == PlaybackState.IDLE && durationMs == 0L && !isPipLayout) {
            PlayerEmptyStateCard(
                onOpenFile = { videoPicker.launch(arrayOf("video/*", "audio/*", "*/*")) },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }

        // 4. Touch HUD (Volume / Brightness / Scrub / Speed / Double-Tap)
        if (!isPipLayout && !hideChrome) {
            PlayerHudOverlay(
                hudState = playerState.gestureHudState,
                scrubState = scrubState,
                durationMs = { durationMs },
            )
        }

        // 5. Diagnostics Telemetry Stats (when controls are hidden)
        if (!isPipLayout && !hideChrome && playerState.statsOverlayEnabled && !playerState.showControls) {
            PlayerStatsOverlay(
                diagnosticsFlow = viewModel.diagnostics,
                videoDimensions = videoDimensions,
                videoFrameRate = snapshot.videoFrameRate,
                hwdecMode = hwdecMode,
                bitrate = telemetryState.liveVideoBitrate,
                hdrLabel = if (snapshot.isHdr) hdrFormat else null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .then(
                        if (isLandscape) {
                            Modifier
                                .statusBarsPadding()
                                .padding(start = landscapeHorizontalInset + 8.dp, top = 12.dp)
                        } else {
                            Modifier
                                .displayCutoutPadding()
                                .statusBarsPadding()
                                .padding(start = 16.dp, top = 8.dp)
                        }
                    )
            )
        }

        // 6. Top & Bottom Streamline Controls Overlay (hidden in PiP)
        if (!isPipLayout && !hideChrome) {
            PlayerControlsOverlay(
                isVisible = playerState.showControls,
                isLocked = playerState.isLocked,
                title = mediaTitle.ifBlank { "Gyro Player" },
                isPlaying = isPlaying,
                timePosMs = { timePosState.value },
                durationMs = { durationMs },
                bufferedPosMs = { bufferedPosState.value },
                resizeMode = resizeMode,
                speed = speed,
                scrubState = scrubState,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                actions = overlayActions,
                subtitleEnabled = subtitleEnabled,
                abLoopASeconds = snapshot.abLoopASeconds,
                abLoopBSeconds = snapshot.abLoopBSeconds,
                chapters = chapters,
                videoBottomDp = videoBottomDp,
                hdrFormat = hdrFormat,
                activeUri = activeUri,
                posterBitmap = currentPosterBitmap,
                loadFilmstripThumbnail = { timeMs -> viewModel.getFilmstripThumbnail(timeMs) },
                videoAspectRatio = if (videoDimensions.height > 0) videoDimensions.width.toFloat() / videoDimensions.height.toFloat() else 16f / 9f
            )
        }

        // 7. Modal Tabbed Settings & Playlist Bottom Sheet
        // Keep the sheet mounted until AnchoredSheetScaffold finishes sliding to Hidden so exit
        // springs and drag-dismiss settle cleanly without a second wrapper animation fighting them.
        val openSheetTab = playerState.activeSheetTab
        val isSheetVisible = openSheetTab != null && !isPipLayout && !hideChrome
        var mountedSheetTab by remember { mutableStateOf<PlayerSheetRoute?>(null) }
        if (isSheetVisible) {
            mountedSheetTab = openSheetTab
        } else if (isPipLayout || hideChrome) {
            mountedSheetTab = null
        }

        mountedSheetTab?.let { tab ->
            PlayerSheetsHost(
                tab = tab,
                visible = isSheetVisible,
                isLandscape = isLandscape,
                viewModel = viewModel,
                playerState = playerState,
                snapshot = snapshot,
                playlist = playlist,
                currentPlaylistIndex = currentPlaylistIndex,
                mediaTitle = mediaTitle,
                activeUri = activeUri,
                telemetryState = telemetryState,
                powerMetrics = powerState.metrics,
                onImportCustomShader = { shaderPicker.launch(arrayOf("*/*")) },
                onAddExternalSubtitle = {
                    subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/*"))
                },
                onOpenFullSettings = onOpenSettings,
                onEnterPip = {
                    playerState.activeSheetTab = null
                    mountedSheetTab = null
                    pipState.enterPip()
                },
                onHidden = {
                    if (!isSheetVisible) {
                        mountedSheetTab = null
                    }
                },
            )
        }

        // 8. Instant camera shutter white flash feedback
        PlayerShutterFlashOverlay(controller = screenshotController)
    }
}
