package dev.gyro.feature.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.gyro.engine.media.PlaylistManager
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.feature.player.component.PlayerControlsOverlay
import dev.gyro.feature.player.component.PlayerHudOverlay
import dev.gyro.feature.player.component.PlayerTrackSelectionSheet
import dev.gyro.feature.player.gesture.*
import dev.gyro.core.designsystem.R
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.trace.SwitchTrace
import dev.gyro.engine.ui.GyroPlayerSurface
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun PlayerScreen(
    engine: GyroPlayerEngine,
    currentUri: Uri? = null,
    settings: UserSettings = UserSettings(),
    playlistManager: PlaylistManager? = null,
    viewModel: PlayerViewModel,
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
    BackHandler {
        onBack()
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

    LaunchedEffect(currentUri) {
        if (currentUri != null && viewModel.activeUri.value != currentUri) {
            viewModel.openUri(currentUri)
        }
    }

    val playlistMgr = playlistManager ?: engine.playlist
    val playlist by playlistMgr.items.collectAsState()
    val currentPlaylistIndex by playlistMgr.currentIndex.collectAsState()
    val hasPrevious = playlistMgr.hasPrevious
    val hasNext = playlistMgr.hasNext

    fun openUri(uri: Uri) {
        viewModel.openUri(uri)
    }

    fun playMediaItem(item: VideoMediaItem) {
        viewModel.playMediaItem(item)
    }

    // Auto-advance, companion subtitle loading and progress persistence all live in
    // PlayerViewModel; this screen only renders and forwards intents.

    // Clear any residual MPV native video-zoom
    LaunchedEffect(Unit) {
        engine.geometry.resetZoomAndPan()
    }

    // 2. Picture-in-Picture (PiP) Architecture (Aligned with MiniBgm)
    PipBroadcastReceiver(
        onTogglePlayPause = { engine.togglePlayPause() }
    )

    val targetAspectRatio = remember(videoDimensions, videoAspect) {
        val ratio = when {
            videoAspect > 0f -> videoAspect
            videoDimensions.height > 0 -> videoDimensions.width.toFloat() / videoDimensions.height
            else -> 16f / 9f
        }.coerceIn(0.42f, 2.38f)
        Rational((ratio * 1000).toInt(), 1000)
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // The PiP source rect hint describes where the video sits in the *fullscreen* window and only
    // matters for the enter transition. Sizing it from the container while already in picture in
    // picture would rebuild the params for the shrunk window, and every re-applied params set makes
    // the system run the resize again - that second pass is the flash seen on entering PiP.
    var fullscreenContainerSize by remember { mutableStateOf(IntSize.Zero) }

    val videoSourceRect = remember(fullscreenContainerSize, targetAspectRatio, resizeMode) {
        if (fullscreenContainerSize.width > 0 && fullscreenContainerSize.height > 0) {
            calculateVideoSourceRect(
                Rect(0, 0, fullscreenContainerSize.width, fullscreenContainerSize.height),
                targetAspectRatio,
                resizeMode
            )
        } else null
    }

    val isInPipMode = rememberIsInPipMode()
    val isPipWindow = rememberIsPipWindow(componentActivity)

    val leavingForeground = rememberUserLeavingHint()
    var pipRequested by remember { mutableStateOf(false) }
    val hideChrome = leavingForeground || pipRequested

    LaunchedEffect(isInPipMode) {
        pipRequested = false
    }
    LaunchedEffect(pipRequested) {
        if (!pipRequested) return@LaunchedEffect
        delay(PIP_ENTER_TIMEOUT_MS)
        pipRequested = false
    }

    val isPipLayout = isPipWindow || isInPipMode
    val autoPipEnabled = settings.backgroundPlayMode == "pip"

    // Sync PiP params dynamically for Android 12+ (API 31+) swipe-up auto-enter
    LaunchedEffect(isPlaying, autoPipEnabled, targetAspectRatio, videoSourceRect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && componentActivity != null) {
            val params = buildPipParams(
                context = context,
                isPlaying = isPlaying,
                aspectRatio = targetAspectRatio,
                sourceRect = videoSourceRect,
                autoEnter = isPlaying && autoPipEnabled
            )
            componentActivity.setPictureInPictureParams(params)
        }
    }

    // Auto-enter PiP on Android 8.0 - 11 (API 26-30) via UserLeaveHintListener.
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestAutoPipEnabled by rememberUpdatedState(autoPipEnabled)
    val latestAspectRatio by rememberUpdatedState(targetAspectRatio)
    val latestSourceRect by rememberUpdatedState(videoSourceRect)
    DisposableEffect(componentActivity) {
        val act = componentActivity ?: return@DisposableEffect onDispose { }
        val hintListener = Runnable {
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.R &&
                latestIsPlaying && latestAutoPipEnabled
            ) {
                val params = buildPipParams(
                    context = act,
                    isPlaying = latestIsPlaying,
                    aspectRatio = latestAspectRatio,
                    sourceRect = latestSourceRect,
                    autoEnter = true
                )
                act.enterPictureInPictureMode(params)
            }
        }
        act.addOnUserLeaveHintListener(hintListener)
        onDispose {
            act.removeOnUserLeaveHintListener(hintListener)
        }
    }

    // 3. UI Interactive States via PlayerState
    val screenEntryEpoch by viewModel.screenEntryEpoch.collectAsState()
    LaunchedEffect(screenEntryEpoch) {
        playerState.showControls = true
    }
    var wasInPipLayout by remember { mutableStateOf(false) }
    LaunchedEffect(isPipLayout) {
        if (wasInPipLayout && !isPipLayout) playerState.showControls = false
        wasInPipLayout = isPipLayout
    }

    val scrubState = playerState.scrubState
    val zoomState = playerState.zoomState
    var resetJob by remember { mutableStateOf<Job?>(null) }

    // 5. Power & Thermal Monitor
    val powerMonitor = remember { PowerMonitor(context) }
    val powerMetrics by powerMonitor.powerMetrics.collectAsState()
    LaunchedEffect(Unit) {
        powerMonitor.startMonitoring(this)
    }

    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Reset zoom state on orientation change so portrait pan offsets don't displace video in landscape
    LaunchedEffect(isLandscape) {
        resetJob?.cancel()
        resetJob = null
        zoomState.reset()
    }

    // A file switch must start from the identity transform
    LaunchedEffect(activeUri) {
        resetJob?.cancel()
        resetJob = null
        zoomState.reset()
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
        if (playerState.showControls && isPlaying && !scrubState.isActive &&
            !playerState.isLocked && !isPipLayout && playerState.activeSheetTab == null
        ) {
            delay(3500L)
            playerState.showControls = false
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
            engine.trackManager.addSubtitle(target)
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
            engine.shaders.importAndSetCustomShader(it)
        }
    }

    var videoContainerOrigin by remember { mutableStateOf(Offset.Zero) }
    var videoContainerSize by remember { mutableStateOf(IntSize.Zero) }

    val composeVideoBounds = remember(videoContainerOrigin, videoContainerSize, videoSourceRect) {
        if (videoContainerSize.width > 0 && videoContainerSize.height > 0) {
            androidx.compose.ui.geometry.Rect(
                left = videoContainerOrigin.x,
                top = videoContainerOrigin.y,
                right = videoContainerOrigin.x + videoContainerSize.width.toFloat(),
                bottom = videoContainerOrigin.y + videoContainerSize.height.toFloat()
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

    val mediaSubtitle = remember(videoDimensions, videoFrameRate, hwdecCurrent) {
        val parts = mutableListOf<String>()
        if (videoDimensions.width > 0 && videoDimensions.height > 0) {
            parts.add("${videoDimensions.width}x${videoDimensions.height}")
        }
        if (videoFrameRate > 0f) {
            parts.add("${String.format(Locale.US, "%.0f", videoFrameRate)}fps")
        }
        if (hwdecCurrent.isNotBlank() && hwdecCurrent != "no") {
            parts.add("HW")
        }
        if (parts.isNotEmpty()) parts.joinToString(" · ") else null
    }

    val dynamicConfig = settings.gestureConfig
    val gestureConfig = remember(dynamicConfig, settings.gesturesEnabled, playerState.isLocked) {
        if (!settings.gesturesEnabled) {
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
            engine.audio.resetSoftwareGain()
        }
    }

    var priorSpeedBeforeBoost by remember { mutableStateOf<Float?>(null) }

    val gestureModifier = if (!isPipLayout) {
        Modifier.playerGestures(
            context = context,
            isLocked = playerState.isLocked,
            isPlaying = isPlaying,
            currentPosMs = { engine.timePosMs.value },
            durationMs = { durationMs },
            scrubState = scrubState,
            zoomScale = { zoomState.scale },
            onZoomChange = { zoom, pan, centroid, isPinch ->
                resetJob?.cancel()
                resetJob = null
                val vpSize = containerSize.toSize()
                zoomState.onGesture(
                    zoomChange = zoom,
                    panDelta = pan,
                    centroid = centroid,
                    viewportSize = vpSize,
                    contentBounds = composeVideoBounds,
                    isPinch = isPinch
                )
            },
            onResetZoom = {
                resetJob?.cancel()
                resetJob = scope.launch {
                    val vpSize = containerSize.toSize()
                    zoomState.animateReset(vpSize, composeVideoBounds)
                }
            },
            onSettleZoom = {
                resetJob?.cancel()
                resetJob = scope.launch {
                    val vpSize = containerSize.toSize()
                    zoomState.animateSettle(vpSize, composeVideoBounds)
                }
            },
            onSingleTap = {
                playerState.showControls = !playerState.showControls
                if (playerState.showControls) {
                    playerState.resetControlsTimeout()
                }
            },
            onDoubleTapSeek = { deltaMs ->
                engine.seekRelative(deltaMs)
            },
            onDoubleTapPlayPause = {
                engine.togglePlayPause()
            },
            onFastForwardStart = { targetSpeed ->
                priorSpeedBeforeBoost = speed
                engine.setSpeed(targetSpeed)
                playerState.gestureHudState = GestureHudState.Speed(targetSpeed)
            },
            onFastForwardEnd = {
                val restoreSpeed = priorSpeedBeforeBoost ?: 1.0f
                engine.setSpeed(restoreSpeed)
                priorSpeedBeforeBoost = null
                playerState.gestureHudState = GestureHudState.Idle
            },
            onHudStateChange = { newState ->
                playerState.gestureHudState = newState
            },
            onSoftwareVolumeChange = { gain ->
                engine.audio.setSoftwareVolume(gain)
            },
            getSoftwareVolume = {
                engine.state.value.softwareVolume
            },
            config = gestureConfig
        )
    } else {
        Modifier
    }

    val firstFrameRendered = snapshot.firstFrameRendered
    val geometryReady = snapshot.geometryReady
    var isShutterReady by remember(activeUri) { mutableStateOf(false) }
    var shutterTimedOut by remember(activeUri) { mutableStateOf(false) }
    LaunchedEffect(activeUri, firstFrameRendered, geometryReady) {
        SwitchTrace.log("SHUTTER_EVALUATE", "activeUri=$activeUri, firstFrame=$firstFrameRendered, geometryReady=$geometryReady")
        if (firstFrameRendered && geometryReady) {
            withFrameNanos { }
            SwitchTrace.end("SHUTTER_REVEALED", "aspect=$videoAspect, containerSize=$videoContainerSize")
            isShutterReady = true
            shutterTimedOut = false
            return@LaunchedEffect
        }
        isShutterReady = false
        delay(4_000L)
        if (!engine.state.value.firstFrameRendered || !engine.state.value.geometryReady) {
            SwitchTrace.log("SHUTTER_TIMEOUT_EXPIRED", "firstFrame=${engine.state.value.firstFrameRendered}, geometry=${engine.state.value.geometryReady}")
            shutterTimedOut = true
            isShutterReady = true
        }
    }
    val showShutter = !isPipLayout && playbackState != PlaybackState.IDLE &&
        playbackState != PlaybackState.ERROR &&
        !isShutterReady && !shutterTimedOut

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged {
                containerSize = it
                if (!isPipLayout) fullscreenContainerSize = it
            }
    ) {
        val videoCoverSize = remember(containerSize, videoAspect) {
            if (containerSize == IntSize.Zero || videoAspect <= 0f) null
            else if (containerSize.width.toFloat() / containerSize.height > videoAspect) {
                IntSize(containerSize.width, (containerSize.width / videoAspect).toInt())
            } else {
                IntSize((containerSize.height * videoAspect).toInt(), containerSize.height)
            }
        }
        val density = LocalDensity.current
        val videoContainerModifier = when (resizeMode) {
            ResizeMode.FIT -> if (videoAspect > 0f) Modifier.aspectRatio(videoAspect) else Modifier.fillMaxSize()
            ResizeMode.ASPECT_16_9 -> Modifier.aspectRatio(16f / 9f)
            ResizeMode.ASPECT_21_9 -> Modifier.aspectRatio(21f / 9f)
            ResizeMode.CROP -> videoCoverSize?.let { sz ->
                with(density) { Modifier.size(sz.width.toDp(), sz.height.toDp()) }
            } ?: Modifier.fillMaxSize()
            ResizeMode.STRETCH -> Modifier.fillMaxSize()
        }
        GyroPlayerSurface(
            modifier = videoContainerModifier
                .align(Alignment.Center)
                .onGloballyPositioned { coords ->
                    val oldSize = videoContainerSize
                    videoContainerOrigin = coords.positionInParent()
                    videoContainerSize = coords.size
                    if (coords.size != oldSize) {
                        SwitchTrace.log(
                            "CONTAINER_LAYOUT_CHANGED",
                            "${coords.size.width}x${coords.size.height} aspect=${if (coords.size.height > 0) coords.size.width.toFloat() / coords.size.height else 0f}"
                        )
                    }
                }
                .graphicsLayer {
                    scaleX = zoomState.scale
                    scaleY = zoomState.scale
                    translationX = (videoContainerOrigin.x - zoomState.contentOffset.x) * zoomState.scale - videoContainerOrigin.x
                    translationY = (videoContainerOrigin.y - zoomState.contentOffset.y) * zoomState.scale - videoContainerOrigin.y
                    transformOrigin = zoomState.transformOrigin
                },
            engine = engine,
            videoDimensions = videoDimensions,
            videoFrameRate = videoFrameRate
        )

        // A2. Shutter over the file-switch window.
        if (showShutter) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }

        // A3. Playback error card - a failed load must be visible, not a black screen that
        // looks like a paused video. Drawn above the shutter, which reveals immediately on
        // ERROR (see showShutter).
        val playbackError = if (playbackState == PlaybackState.ERROR) snapshot.error else null
        if (playbackError != null && !isPipLayout) {
            Surface(
                color = Color.Black.copy(alpha = 0.82f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFE57373).copy(alpha = 0.35f)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFE57373),
                        modifier = Modifier.size(54.dp)
                    )
                    Text(
                        text = stringResource(R.string.player_error_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.player_error_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Button(
                        onClick = {
                            activeUri?.let { viewModel.openUri(it, forceReload = true) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }

        // B. Gesture Interaction Layer (Under HUD and Controls Overlay)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(gestureModifier)
        )

        // C. Buffering Spinner
        if (playbackState == PlaybackState.BUFFERING && !isPipLayout) {
            CircularProgressIndicator(
                color = Color(0xFF64B5F6),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // D. Empty State / No Media Selected Card
        if (playbackState == PlaybackState.IDLE && durationMs == 0L && !isPipLayout) {
            Surface(
                color = Color.Black.copy(alpha = 0.82f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = Color(0xFF64B5F6),
                        modifier = Modifier.size(54.dp)
                    )
                    Text(
                        text = stringResource(R.string.player_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.player_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Button(
                        onClick = {
                            videoPicker.launch(arrayOf("video/*", "audio/*", "*/*"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_open_file))
                    }
                }
            }
        }

        // E. Clean Top Floating Capsule HUD (Volume/Brightness/Speed/Double-Tap)
        if (!isPipLayout && !hideChrome) {
            PlayerHudOverlay(
                hudState = playerState.gestureHudState
            )
        }

        // F. Top & Bottom Streamline Controls Overlay (hidden in PiP)
        if (!isPipLayout && !hideChrome) {
            PlayerControlsOverlay(
                isVisible = playerState.showControls,
                isLocked = playerState.isLocked,
                title = mediaTitle.ifBlank { "Gyro Player" },
                mediaSubtitle = mediaSubtitle,
                isPlaying = isPlaying,
                timePosMs = { timePosState.value },
                durationMs = { durationMs },
                bufferedPosMs = { bufferedPosState.value },
                resizeMode = resizeMode,
                speed = speed,
                scrubState = scrubState,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onTogglePlayPause = {
                    playerState.resetControlsTimeout()
                    engine.togglePlayPause()
                },
                onPlayPrevious = {
                    playerState.resetControlsTimeout()
                    viewModel.playPrevious()
                },
                onPlayNext = {
                    playerState.resetControlsTimeout()
                    viewModel.playNext()
                },
                onToggleLock = {
                    playerState.resetControlsTimeout()
                    playerState.isLocked = !playerState.isLocked
                    playerState.showControls = !playerState.isLocked
                },
                onSeekToFraction = { fraction ->
                    playerState.resetControlsTimeout()
                    val target = (fraction * durationMs).toLong()
                    engine.seekTo(target)
                },
                onRewind10 = {
                    playerState.resetControlsTimeout()
                    engine.seekRelative(-settings.seekStepSeconds * 1000L)
                },
                onForward10 = {
                    playerState.resetControlsTimeout()
                    engine.seekRelative(settings.seekStepSeconds * 1000L)
                },
                onOpenSheetTab = { tab ->
                    playerState.resetControlsTimeout()
                    playerState.activeSheetTab = tab
                },
                onEnterPiP = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && componentActivity != null) {
                        if (pipRequested) return@PlayerControlsOverlay
                        pipRequested = true
                        playerState.showControls = false
                        scope.launch {
                            withFrameNanos { }
                            withFrameNanos { }
                            val params = buildPipParams(
                                context = context,
                                isPlaying = isPlaying,
                                aspectRatio = targetAspectRatio,
                                sourceRect = videoSourceRect,
                                autoEnter = true
                            )
                            val entered = componentActivity.enterPictureInPictureMode(params)
                            if (!entered) {
                                pipRequested = false
                                playerState.showControls = true
                            }
                        }
                    }
                },
                onToggleOrientation = {
                    activity?.let { act ->
                        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        act.requestedOrientation = if (isLandscape) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    }
                },
                onBack = onBack,
                chapters = chapters
            )
        }

        // G. Modal Tabbed Settings & Calibrator Bottom Sheet
        playerState.activeSheetTab?.let { tab ->
            if (!isPipLayout && !hideChrome) {
                val timePosMs by engine.timePosMs.collectAsState()
                val fps = snapshot.fps
                val droppedFrames = snapshot.droppedFrames
                val currentMediaInfo = remember(playerState.activeSheetTab, activeUri) {
                    engine.queryMediaInfo(
                        fallbackTitle = mediaTitle,
                        fallbackUri = activeUri?.toString() ?: ""
                    )
                }
                PlayerTrackSelectionSheet(
                    initialTab = tab,
                    playlist = playlist,
                    currentPlaylistIndex = currentPlaylistIndex,
                    chapters = chapters,
                    currentChapterIndex = currentChapter,
                    currentTimePosMs = timePosMs,
                    tracks = tracks,
                    currentResizeMode = resizeMode,
                    currentSpeed = speed,
                    subDelaySec = subDelaySec,
                    audioDelaySec = audioDelaySec,
                    subScale = subScale,
                    subPos = subPos,
                    hwdec = hwdecCurrent,
                    fps = fps,
                    droppedFrames = droppedFrames,
                    videoWidth = videoDimensions.width,
                    videoHeight = videoDimensions.height,
                    powerMetrics = powerMetrics,
                    mediaInfo = currentMediaInfo,
                    shaderState = shaderState,
                    customShaders = remember(shaderState) { engine.shaders.getCustomShaders() },
                    deband = deband,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    nightMode = nightMode,
                    stereoDownmix = stereoDownmix,
                    onDismiss = { playerState.activeSheetTab = null },
                    onSelectPlaylistItem = { idx ->
                        playlistMgr.playAtIndex(idx)?.let { playMediaItem(it) }
                        playerState.activeSheetTab = null
                    },
                    onSelectChapter = { idx ->
                        engine.seekToChapter(idx)
                        playerState.activeSheetTab = null
                    },
                    onSelectShader = { shader -> engine.shaders.setShader(shader) },
                    onClearShader = { engine.shaders.clearShader() },
                    onSelectCustomShaderFile = { file ->
                        engine.shaders.setShader(GlslShader.CUSTOM, file.absolutePath, file.nameWithoutExtension)
                    },
                    onImportCustomShader = {
                        shaderPicker.launch(arrayOf("*/*"))
                    },
                    onSetDeband = { enabled -> engine.video.setDeband(enabled) },
                    onSetBrightness = { value -> engine.video.setBrightness(value) },
                    onSetContrast = { value -> engine.video.setContrast(value) },
                    onSetSaturation = { value -> engine.video.setSaturation(value) },
                    onResetColors = { engine.video.resetColors() },
                    onSetNightMode = { enabled -> engine.audio.setNightMode(enabled) },
                    onSetStereoDownmix = { enabled -> engine.audio.setStereoDownmix(enabled) },
                    onSelectTrack = { type, id -> engine.trackManager.selectTrack(type, id) },
                    onSelectResizeMode = { mode -> engine.geometry.setResizeMode(mode) },
                    onSelectSpeed = { sp -> engine.setSpeed(sp) },
                    onSetSubDelay = { delay -> engine.tuning.setSubtitleDelay(delay) },
                    onSetAudioDelay = { delay -> engine.tuning.setAudioDelay(delay) },
                    onSetSubScale = { scale -> engine.tuning.setSubtitleScale(scale) },
                    onSetSubPos = { pos -> engine.tuning.setSubtitlePosition(pos) },
                    onToggleHwdec = { engine.toggleHwdec() },
                    onAddExternalSubtitle = {
                        subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/*"))
                    },
                    onOpenFullSettings = {
                        playerState.activeSheetTab = null
                        onOpenSettings()
                    }
                )
            }
        }
    }
}
