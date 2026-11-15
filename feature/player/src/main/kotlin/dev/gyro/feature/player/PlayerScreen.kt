package dev.gyro.feature.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.snapshotFlow
import dev.gyro.core.model.VerticalSwipeMode
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.feature.player.component.PlayerBufferingSpinner
import dev.gyro.feature.player.component.PlayerControlsOverlay
import dev.gyro.feature.player.component.PlayerOverlayActions
import dev.gyro.feature.player.component.PlayerEmptyStateCard
import dev.gyro.feature.player.component.PlayerPlaybackErrorCard
import dev.gyro.feature.player.component.PlayerHudOverlay
import dev.gyro.feature.player.component.StatsDiagnosticOverlay
import dev.gyro.feature.player.component.sheet.PlayerSheetTab
import dev.gyro.feature.player.gesture.*
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.trace.SwitchTrace
import dev.gyro.engine.ui.GyroPlayerSurface
import dev.gyro.engine.ui.GyroSurfaceView
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    engine: PlayerEngine = viewModel.engine,
    currentUri: Uri? = null,
    settings: UserSettings = UserSettings(),
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val componentActivity = remember(context) { context.findComponentActivity() }
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val playerState = rememberPlayerState(activity = activity, scope = scope)

    // Handle system back gesture / back button to return to Home Screen
    PredictiveBackHandler { progress ->
        try {
            progress.collect { backEvent ->
                playerState.backProgress = backEvent.progress
            }
            viewModel.onUserExit()
            onBack()
        } catch (_: Throwable) {
            playerState.backProgress = 0f
        }
    }

    // Dynamic color space: Enable Display P3 Wide Color Gamut while playing video, restore sRGB on exit
    DisposableEffect(componentActivity) {
        val window = componentActivity?.window ?: return@DisposableEffect onDispose {}
        val previousColorMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode
        } else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            val isWide = componentActivity.windowManager.defaultDisplay?.isWideColorGamut == true
            if (isWide) {
                window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                window.colorMode = previousColorMode
            }
        }
    }

    // 1. Engine & Playback States
    // One snapshot instead of a subscription per property: every slow-moving fact arrives as a
    // single consistent object, so `playbackState` and `isPlaying` cannot be read from two
    // different events, and a change recomposes the screen once rather than once per flow.
    val snapshot by engine.state.collectAsState()
    val playbackState = snapshot.playbackState
    val isPlaying = snapshot.isPlaying
    val durationMs = snapshot.durationMs
    // Collected as State<Long> (without `by` delegation) so PlayerScreen itself does not read
    // `.value` and never recomposes on 10Hz time-pos ticks; only PlayerBottomBar reads `.value`
    // via the deferred lambda while the bottom bar is visible.
    val timePosState = engine.timePosMs.collectAsState()
    val bufferedPosState = engine.bufferedPosMs.collectAsState()
    val hwdecCurrent = snapshot.hwdecCurrent
    val videoFrameRate = snapshot.videoFrameRate
    val speed = snapshot.speed
    val tracks = snapshot.tracks

    // 字幕快开状态：当前字幕轨是否启用，以及最后一次选中的字幕轨 id（便于快速恢复）
    val subtitleEnabled = tracks.any { it.type == "sub" && it.isSelected }
    var lastSubTrackId by remember { mutableIntStateOf(-1) }
    LaunchedEffect(tracks) {
        tracks.firstOrNull { it.type == "sub" && it.isSelected }?.let { lastSubTrackId = it.id }
    }

    // Live bitrate sampling is owned by a state holder: the 500ms loop and its 50-sample cache
    // are bookkeeping, not screen layout. It only runs while a stats surface is visible.
    val isStatsActive = playerState.statsOverlayEnabled ||
        playerState.activeSheetTab == dev.gyro.feature.player.component.sheet.PlayerSheetTab.STATS ||
        playerState.activeSheetTab == dev.gyro.feature.player.component.sheet.PlayerSheetTab.SETTINGS_HUB
    val telemetryState = rememberPlayerTelemetryState(
        engine = engine,
        isActive = isPlaying && isStatsActive,
    )

    var surfaceViewRef by remember { mutableStateOf<GyroSurfaceView?>(null) }
    val screenshotController = rememberPlayerScreenshotController()
    val resizeMode = snapshot.resizeMode
    val subDelaySec = snapshot.subtitleDelaySec
    val audioDelaySec = snapshot.audioDelaySec
    val subScale = snapshot.subtitleScale
    val subPos = snapshot.subtitlePositionPercent
    val videoDimensions = snapshot.videoDimensions
    // Rotation-aware: `videoDimensions` is the coded size, which is landscape for a portrait clip
    // that carries a 90 degree rotation flag. The PiP window and the letterbox maths need the shape
    // the viewer actually sees, otherwise a portrait video gets a landscape window.
    val videoAspect = snapshot.videoAspect
    val mediaTitle = snapshot.mediaTitle
    val liveSettings by viewModel.settings.collectAsState()
    val chapters = snapshot.chapters
    val currentChapter = snapshot.currentChapter
    val shaderState = snapshot.shaderState
    val deband = snapshot.deband
    val brightness = snapshot.brightness
    val contrast = snapshot.contrast
    val saturation = snapshot.saturation
    val nightMode = snapshot.nightMode
    val stereoDownmix = snapshot.stereoDownmix

    val vmActiveUri by viewModel.activeUri.collectAsState()
    val engineCurrentUri = snapshot.currentUri
    val activeUri = vmActiveUri ?: engineCurrentUri ?: currentUri

    val hdrFormat = snapshot.hdrFormat
    LaunchedEffect(activeUri, hdrFormat) {
        if (hdrFormat.isNotBlank()) {
            playerState.revealFormatBadge()
        } else {
            playerState.hideFormatBadge()
        }
    }

    val playlistMgr = viewModel.playlistManager
    val playlist by playlistMgr.items.collectAsState()
    val currentPlaylistIndex by playlistMgr.currentIndex.collectAsState()
    val hasPrevious = playlistMgr.hasPrevious
    val hasNext = playlistMgr.hasNext

    // In-memory poster bitmap cache to eliminate blank/black flashes during swipe transitions
    val thumbnailCache = rememberThumbnailCache()
    LaunchedEffect(playlist) {
        playlist.forEach { item ->
            val uriStr = item.uriString
            if (!thumbnailCache.containsKey(uriStr)) {
                loadVideoThumbnail(context, uriStr)?.let { thumbnailCache[uriStr] = it }
            }
        }
    }

    val currentPosterBitmap = activeUri?.let { thumbnailCache[it.toString()] }
    val dynamicPalette = dev.gyro.feature.player.util.rememberPlayerPalette(currentPosterBitmap)

    fun openUri(uri: Uri) {
        val item = playlist.firstOrNull { it.uriString == uri.toString() }
        viewModel.openUri(
            uri,
            initialWidth = item?.width ?: 0,
            initialHeight = item?.height ?: 0
        )
    }

    fun playMediaItem(item: VideoMediaItem) {
        viewModel.playMediaItem(item)
    }

    // Auto-advance, companion subtitle loading and progress persistence all live in
    // PlayerViewModel; this screen only renders and forwards intents.

    // Clear any residual MPV native video-zoom
    LaunchedEffect(Unit) {
        viewModel.resetZoomAndPan()
    }

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

    // 统一管控画中画生命周期、转场防残影与动态参数协商
    val pipState = rememberPlayerPipState(
        activity = componentActivity,
        isPlaying = isPlaying,
        autoPipEnabled = liveSettings.autoPip,
        targetAspectRatio = targetAspectRatio,
        videoSourceRect = videoSourceRect,
        onControlsVisibilityChange = { playerState.showControls = it },
        onPipDismissed = {
            viewModel.onUserExit()
        }
    )
    val isPipLayout = pipState.isPipLayout
    val hideChrome = pipState.shouldHideChrome

    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val overlayActions = rememberPlayerScreenActions(
        engine = engine,
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

    // Picture-in-Picture (PiP) Architecture (Aligned with MiniBgm)
    PipBroadcastReceiver(
        onTogglePlayPause = { overlayActions.onTogglePlayPause() }
    )
    var orientationBeforePip by rememberSaveable {
        mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    // 在普通全屏播放时（非 PiP 也非正在转入 PiP），持续记录实际屏幕朝向
    if (!isPipLayout && !hideChrome) {
        orientationBeforePip = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    // 3. UI Interactive States via PlayerState
    val screenEntryEpoch by viewModel.screenEntryEpoch.collectAsState()
    LaunchedEffect(screenEntryEpoch) {
        playerState.showControls = true
    }
    var wasInPipLayout by remember { mutableStateOf(false) }
    LaunchedEffect(isPipLayout) {
        if (isPipLayout) {
            // 进入 PiP 小窗后解除屏幕锁定，避免小窗内残留锁定阻碍交互
            playerState.isLocked = false
        } else if (wasInPipLayout && !isPipLayout) {
            // 从 PiP 返回全屏：严格恢复进入前的屏幕朝向（横屏进回横屏，竖屏进回竖屏）
            if (orientationBeforePip != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity?.requestedOrientation = orientationBeforePip
            }
            // 返回后不自动弹出控制栏，保持纯净视频画面；用户点击屏幕即可呼出控制栏
            playerState.showControls = false
        }
        wasInPipLayout = isPipLayout
    }

    val scrubState = playerState.scrubState
    val zoomState = playerState.zoomState

    // 5. Power & Thermal Monitor
    val powerMonitor = remember { PowerMonitor(context) }
    LaunchedEffect(Unit) {
        powerMonitor.startMonitoring(this)
    }

    // 6. Sticky Immersive System Bars (Transient Swipe)
    LaunchedEffect(playerState.showControls, isPipLayout, isLandscape) {
        playerState.applySystemBars(isPipLayout = isPipLayout, isLandscape = isLandscape)
    }

    // 7. Auto-hide controls timer (3.5s of inactivity while playing)
    LaunchedEffect(
        playerState.showControls,
        isPlaying,
        scrubState.isActive,
        playerState.isLocked,
        isPipLayout,
        playerState.activeSheetTab,
        playerState.lastInteractionTrigger
    ) {
        if (playerState.canAutoHide(
                isPlaying = isPlaying,
                isScrubbing = scrubState.isActive,
                isPipLayout = isPipLayout,
            )
        ) {
            playerState.autoHideAfter()
        }
    }

    // 8. Pickers for video file & external subtitles
    val subtitlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { pickedUri ->
            val target = if (pickedUri.scheme == "content") {
                val fd = runCatching {
                    context.contentResolver.openFileDescriptor(pickedUri, "r")?.detachFd()
                }.getOrNull() ?: -1
                if (fd >= 0) "fdclose://$fd" else (pickedUri.path ?: pickedUri.toString())
            } else {
                pickedUri.path ?: pickedUri.toString()
            }
            viewModel.addSubtitle(target)
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            openUri(it)
        }
    }

    val shaderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.setCustomShader(it)
        }
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

    // 退出播放器界面时平滑恢复系统栏状态，避免返回 HomeScreen 时状态栏撕裂跳变
    DisposableEffect(Unit) {
        onDispose {
            playerState.restoreSystemBars()
        }
    }

    // 播放中保持屏幕常亮，暂停或退出播放器界面时释放常亮锁
    DisposableEffect(activity, isPlaying) {
        val window = activity?.window
        if (isPlaying) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Progress is persisted by PlayerViewModel.onCleared; the screen only has to drop the
    // software gain so the next screen does not inherit an overdriven volume.
    DisposableEffect(activeUri) {
        onDispose {
            viewModel.resetAudioSoftwareGain()
        }
    }

    // Built unconditionally: the zoom-reset effects inside must keep running in PiP too.
    // Only the returned Modifier's application is gated on PiP.
    val rawGestureModifier = rememberPlayerGestureModifier(
        context = context,
        isLandscape = isLandscape,
        activeUri = activeUri,
        playerState = playerState,
        isPlaying = isPlaying,
        engine = engine,
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

    val firstFrameRendered = snapshot.firstFrameRendered
    val geometryReady = snapshot.geometryReady
    val sessionState = snapshot.sessionState
    val isSwitching = sessionState is dev.gyro.engine.api.session.PlaybackSessionState.Switching
    val shutter = rememberPlayerShutterState(
        activeUri = activeUri,
        sessionState = sessionState,
        firstFrameRendered = firstFrameRendered,
        geometryReady = geometryReady,
        videoAspect = videoAspect,
        videoWidth = videoDimensions.width,
        videoHeight = videoDimensions.height,
        resizeMode = resizeMode,
        containerSize = viewport.videoContainerSize,
        bufferSize = viewport.appliedBufferSize,
    )
    val showShutter = shutter.covers(
        activeUri = activeUri,
        playbackState = playbackState,
        isPipLayout = isPipLayout,
        firstFrameRendered = firstFrameRendered,
        isSwitching = isSwitching,
    )

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
            .onSizeChanged {
                // shouldHideChrome turns true the instant the PiP button is clicked (pipRequested)
                // and stays true through the exit settle, so the fullscreen rect is frozen across
                // the whole in-flight window where the system mode callback still lags.
                viewport.onContainerSized(it, isPipLayout || pipState.shouldHideChrome)
            }
    ) {
        val videoCoverSize = remember(viewport.containerSize, videoAspect) {
            if (viewport.containerSize == IntSize.Zero || videoAspect <= 0f) null
            else if (viewport.containerSize.width.toFloat() / viewport.containerSize.height > videoAspect) {
                IntSize(viewport.containerSize.width, (viewport.containerSize.width / videoAspect).toInt())
            } else {
                IntSize((viewport.containerSize.height * videoAspect).toInt(), viewport.containerSize.height)
            }
        }
        val videoContainerModifier = when (resizeMode) {
            ResizeMode.FIT -> if (videoAspect > 0f) Modifier.aspectRatio(videoAspect) else Modifier.fillMaxSize()
            ResizeMode.ASPECT_16_9 -> Modifier.aspectRatio(16f / 9f)
            ResizeMode.ASPECT_21_9 -> Modifier.aspectRatio(21f / 9f)
            ResizeMode.CROP -> videoCoverSize?.let { sz ->
                with(density) { Modifier.size(sz.width.toDp(), sz.height.toDp()) }
            } ?: Modifier.fillMaxSize()
            ResizeMode.STRETCH -> Modifier.fillMaxSize()
        }
        val pageCount = playlist.size.coerceAtLeast(1)
        val initialPage = currentPlaylistIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val pagerState = rememberPagerState(
            initialPage = initialPage,
            pageCount = { pageCount }
        )

        LaunchedEffect(currentPlaylistIndex, pageCount) {
            if (currentPlaylistIndex in 0 until pageCount && pagerState.currentPage != currentPlaylistIndex && !pagerState.isScrollInProgress) {
                pagerState.scrollToPage(currentPlaylistIndex)
            }
        }

        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }
                .collect { settledPage ->
                    if (settledPage in playlist.indices && settledPage != currentPlaylistIndex) {
                        viewModel.playAtIndex(settledPage)
                    }
                }
        }

        LaunchedEffect(pagerState.isScrollInProgress) {
            if (pagerState.isScrollInProgress) {
                playerState.showControls = false
            }
        }

        val pageOffset by remember {
            derivedStateOf {
                val scrollPos = pagerState.currentPage + pagerState.currentPageOffsetFraction
                // No playlist selection (-1) is what a single-file VIEW intent leaves behind. Using it
                // raw translates the persistent surface a full page off-screen, which reads as a black
                // video while audio and the progress bar keep running.
                val selected = if (currentPlaylistIndex in playlist.indices) {
                    currentPlaylistIndex
                } else {
                    pagerState.currentPage
                }
                selected.toFloat() - scrollPos
            }
        }

        val isSheetActive = !isLandscape && playerState.activeSheetTab != null

        // 竖屏下弹起抽屉时：横屏视频平滑上移至上半区中央，竖屏短视频保持原始全屏比例不缩放
        val density = LocalDensity.current
        val statusBarInsetPx = with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() }

        val targetShiftY = remember(isSheetActive, viewport.containerSize, videoAspect, statusBarInsetPx) {
            if (!isSheetActive || viewport.containerSize.height <= 0) {
                0f
            } else if (videoAspect >= 1.0f) {
                // 横屏视频 (16:9 / 21:9 / 4:3)：平滑上移至上半区视口正中央，完全脱离底部抽屉遮挡
                val totalH = viewport.containerSize.height.toFloat()
                val topWindowH = totalH * 0.48f // 底部抽屉占 52%，上半区视口占 48%
                val topPadding = statusBarInsetPx + with(density) { 8.dp.toPx() }
                val targetCenterY = (topPadding + topWindowH) / 2f
                val origCenterY = totalH / 2f
                targetCenterY - origCenterY
            } else {
                // 竖屏短视频 (9:16)：保持全屏正常比例播放（如抖音/TikTok 展开底栏时一样），绝不缩放变形，微幅上移保持核心视觉焦点
                -with(density) { 40.dp.toPx() }
            }
        }

        val animatedSheetShiftY by animateFloatAsState(
            targetValue = if (isSheetActive) targetShiftY else 0f,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
            label = "sheetVideoShiftY"
        )

        // A1. Persistent GyroPlayerSurface - stays attached to the engine and translates
        // in lockstep with the active page offset. Avoids SurfaceView destruction and
        // recreation across page swipes, eliminating BLASTBufferQueue locks and buffer drops.
        GyroPlayerSurface(
            modifier = videoContainerModifier
                .align(Alignment.Center)
                .onGloballyPositioned { coords ->
                    val windowBounds = coords.boundsInWindow()
                    val windowRect = if (windowBounds.width > 0f && windowBounds.height > 0f) {
                        android.graphics.Rect(
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
            videoDimensions = videoDimensions,
            videoFrameRate = videoFrameRate,
            pipTransitionActive = pipState.isTransitionActive,
            onSurfaceViewAvailable = { surfaceViewRef = it },
            onAppliedBufferSizeChanged = { viewport.onAppliedBufferSizeChanged(it) }
        )

        val activePlaylistItem = playlist.getOrNull(currentPlaylistIndex)
        val activeUriString = activeUri?.toString()
        // The playlist is only one entry point into the player: a VIEW intent (file manager, gallery,
        // "open with") goes straight to openUri() and can leave a stale playlist behind. Gate the
        // poster on the item match only when the playlist actually contains what the engine is
        // playing; otherwise the opaque poster would stay up and hide a video already on screen.
        val activeIsInPlaylist = activeUriString != null && playlist.any { it.uriString == activeUriString }
        val isCurrentVideoReady = firstFrameRendered &&
            !isSwitching &&
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
            // `currentPlaylistIndex` is -1 until something sets a playlist, which is exactly what a
            // single-file VIEW intent ("open with", file manager) does: openUri() plays a URI that was
            // never put in the playlist. Comparing the page to -1 left every page inactive, so the
            // opaque poster covered a video that was already rendering.
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
                // If not active, or if active but the new video's first frame is not ready yet:
                // keep the cached thumbnail poster visible, smoothly crossfading once the first frame renders.
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

        // A3. Playback error card - a failed load must be visible, not a black screen that
        // looks like a paused video. Drawn above the shutter, which reveals immediately on
        // ERROR (see showShutter).
        val playbackError = if (playbackState == PlaybackState.ERROR) snapshot.error else null
        if (playbackError != null && !isPipLayout) {
            PlayerPlaybackErrorCard(
                error = playbackError,
                onRetry = { activeUri?.let { viewModel.openUri(it, forceReload = true) } },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }

        // C. Buffering Spinner
        if (playbackState == PlaybackState.BUFFERING && !isPipLayout) {
            PlayerBufferingSpinner(modifier = Modifier.align(Alignment.Center))
        }

        // D. Empty State / No Media Selected Card
        if (playbackState == PlaybackState.IDLE && durationMs == 0L && !isPipLayout) {
            PlayerEmptyStateCard(
                onOpenFile = { videoPicker.launch(arrayOf("video/*", "audio/*", "*/*")) },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }

        // E. Clean Floating HUD (Scrub Preview / Volume / Brightness / Speed / Double-Tap)
        if (!isPipLayout && !hideChrome) {
            PlayerHudOverlay(
                hudState = playerState.gestureHudState,
                scrubState = scrubState,
                durationMs = { durationMs },
                swapVolumeAndBrightness = gestureConfig.swapVolumeAndBrightness
            )
        }

        // E1. 杜比视界 / HDR 格式入场标识 (进入播放界面弹出，3秒后自动淡出)
        if (!isPipLayout && !hideChrome) {
            androidx.compose.animation.AnimatedVisibility(
                visible = playerState.formatBadgeVisible,
                enter = fadeIn(tween(400)) + slideInVertically(initialOffsetY = { -it / 2 }),
                exit = fadeOut(tween(600)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .displayCutoutPadding()
                    .statusBarsPadding()
                    .padding(top = 64.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xD91E1E1E),
                    border = BorderStroke(
                        1.dp,
                        if (hdrFormat.contains("Dolby", ignoreCase = true)) Color(0x80FFC107) else Color(0x40FFFFFF)
                    ),
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (hdrFormat.contains("Dolby", ignoreCase = true)) "DOLBY VISION" else hdrFormat,
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (hdrFormat.contains("Dolby", ignoreCase = true)) Color(0xFFFFC107) else Color.White,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                }
            }
        }

        // E2. 常驻诊断浮层（控制层隐藏时显示，替代 mpv stats 页核心读数）
        if (!isPipLayout && !hideChrome && playerState.statsOverlayEnabled && !playerState.showControls) {
            PlayerStatsOverlay(
                engine = engine,
                hwdec = snapshot.hwdecCurrent,
                speed = snapshot.speed,
                bitrate = telemetryState.liveVideoBitrate,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .displayCutoutPadding()
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 8.dp)
            )
        }

        // F. Top & Bottom Streamline Controls Overlay (hidden in PiP)
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
                playlist = playlist,
                currentPlaylistIndex = currentPlaylistIndex,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                actions = overlayActions,
                subtitleEnabled = subtitleEnabled,
                abLoopASeconds = snapshot.abLoopASeconds,
                abLoopBSeconds = snapshot.abLoopBSeconds,
                chapters = chapters,
                videoBottomDp = videoBottomDp,
                ambientColor = dynamicPalette.darkMutedColor,
                hdrFormat = hdrFormat,
                hwdec = hwdecCurrent
            )
        }

        // G. Modal Tabbed Settings & Calibrator Bottom Sheet
        AnimatedVisibility(
            visible = playerState.activeSheetTab != null && !isPipLayout && !hideChrome,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(140))
        ) {
            playerState.activeSheetTab?.let { tab ->
                if (!isPipLayout && !hideChrome) {
                    PlayerSheetsHost(
                        tab = tab,
                        isLandscape = isLandscape,
                        engine = engine,
                        viewModel = viewModel,
                        playerState = playerState,
                        snapshot = snapshot,
                        playlist = playlist,
                        currentPlaylistIndex = currentPlaylistIndex,
                        mediaTitle = mediaTitle,
                        activeUri = activeUri,
                        telemetryState = telemetryState,
                        powerMetrics = powerMonitor.powerMetrics.collectAsState().value,
                        onImportCustomShader = { shaderPicker.launch(arrayOf("*/*")) },
                        onAddExternalSubtitle = {
                            subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/*"))
                        },
                        onOpenFullSettings = onOpenSettings,
                        animatedVisibilityScope = this
                    )
                }
            }
        }

        // H. 截屏快门白闪微动效（即时视效反馈）
        PlayerShutterFlashOverlay(controller = screenshotController)
    }
}

/**
 * 抖音风格短视频流上一集/下一集真实封面与标题预览 (VideoPagePoster)
 */
@Composable
private fun VideoPagePoster(
    item: VideoMediaItem?,
    thumbnailCache: Map<String, android.graphics.Bitmap> = emptyMap(),
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
            uriStr?.let { (thumbnailCache as? MutableMap<String, android.graphics.Bitmap>)?.put(it, loaded) }
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
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp)
                )
                if (item != null) {
                    Text(
                        text = item.displayName,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Diagnostic readout. Subscribes to telemetry here, not in the screen body: these values tick on
 * mpv's reporting cadence, and a dropped-frame counter must not invalidate the player layout.
 */
@Composable
private fun PlayerStatsOverlay(
    engine: PlayerEngine,
    hwdec: String,
    speed: Float,
    bitrate: Long,
    modifier: Modifier = Modifier,
) {
    val telemetry by engine.diagnostics.collectAsState()
    StatsDiagnosticOverlay(
        fps = telemetry.fps,
        droppedFrames = telemetry.droppedFrames,
        hwdec = hwdec,
        vsyncRatio = telemetry.vsyncRatio,
        speed = speed,
        bitrate = bitrate,
        modifier = modifier,
    )
}
