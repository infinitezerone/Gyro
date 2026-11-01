package dev.gyro.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.player.R
import dev.gyro.player.data.repository.PlaybackHistoryRepository
import dev.gyro.player.data.repository.UserSettings
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
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
    historyRepository: PlaybackHistoryRepository? = null,
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val componentActivity = remember(context) { context.findComponentActivity() }
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()

    // Handle system back gesture / back button to return to Home Screen
    BackHandler {
        onBack()
    }

    // 1. Engine & Playback States
    val playbackState by engine.playbackState.collectAsState()
    val isPlaying by engine.isPlaying.collectAsState()
    val timePosMs by engine.timePosMs.collectAsState()
    val durationMs by engine.durationMs.collectAsState()
    val bufferedPosMs by engine.bufferedPosMs.collectAsState()
    val hwdecCurrent by engine.hwdecCurrent.collectAsState()
    val speed by engine.speed.collectAsState()
    val tracks by engine.tracks.collectAsState()
    val resizeMode by engine.resizeMode.collectAsState()
    val fps by engine.fps.collectAsState()
    val droppedFrames by engine.droppedFrames.collectAsState()
    val subDelaySec by engine.subDelay.collectAsState()
    val audioDelaySec by engine.audioDelay.collectAsState()
    val subScale by engine.subScale.collectAsState()
    val subPos by engine.subPos.collectAsState()
    val videoDimensions by engine.videoDimensions.collectAsState()
    val mediaTitle by engine.mediaTitle.collectAsState()

    // Clear any residual MPV native video-zoom
    LaunchedEffect(Unit) {
        engine.resetVideoZoomAndPan()
    }

    // 2. Picture-in-Picture (PiP) Architecture (Aligned with MiniBgm)
    PipBroadcastReceiver(
        onTogglePlayPause = { engine.togglePlayPause() }
    )

    val targetAspectRatio = remember(videoDimensions) {
        if (videoDimensions.width > 0 && videoDimensions.height > 0) {
            val ratio = (videoDimensions.width.toFloat() / videoDimensions.height.toFloat()).coerceIn(0.42f, 2.38f)
            val num = (ratio * 1000).toInt()
            Rational(num, 1000)
        } else {
            Rational(16, 9)
        }
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val videoSourceRect = remember(containerSize, targetAspectRatio, resizeMode) {
        if (containerSize.width > 0 && containerSize.height > 0) {
            calculateVideoSourceRect(
                Rect(0, 0, containerSize.width, containerSize.height),
                targetAspectRatio,
                resizeMode
            )
        } else null
    }

    val isInPipMode = rememberIsInPipMode()
    val isPipWindow = rememberIsPipWindow(componentActivity)
    var pipEntering by remember { mutableStateOf(false) }
    val pipSettling = remember { mutableStateOf(isInPipMode) }

    LaunchedEffect(isInPipMode) {
        if (isInPipMode) {
            pipSettling.value = true
            pipEntering = false
        } else if (pipSettling.value) {
            delay(PIP_EXIT_SETTLE_MS)
            pipSettling.value = false
        }
    }

    val isPipLayout = isPipWindow || isInPipMode

    // Sync PiP params dynamically for Android 12+ (API 31+) swipe-up auto-enter
    LaunchedEffect(isPlaying, targetAspectRatio, videoSourceRect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && componentActivity != null) {
            val params = buildPipParams(
                context = context,
                isPlaying = isPlaying,
                aspectRatio = targetAspectRatio,
                sourceRect = videoSourceRect,
                autoEnter = isPlaying
            )
            componentActivity.setPictureInPictureParams(params)
        }
    }

    // 3. UI Interactive States
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var activeSheetTab by remember { mutableStateOf<PlayerSheetTab?>(null) }
    var gestureHudState by remember { mutableStateOf<GestureHudState>(GestureHudState.Idle) }
    val scrubState = remember { PlayerScrubState() }

    // 4. Anchored Pinch-to-Zoom & Pan State (Compose Style API Math)
    val zoomState = rememberPlayerZoomState()
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

    var lastInteractionTrigger by remember { mutableLongStateOf(0L) }
    fun resetControlsTimeout() {
        lastInteractionTrigger = SystemClock.uptimeMillis()
    }

    // 6. Sticky Immersive System Bars (Transient Swipe)
    LaunchedEffect(showControls, isPipLayout, isLandscape) {
        val window = activity?.window ?: return@LaunchedEffect
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isLandscape) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            if (showControls && !isPipLayout) {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 7. Auto-hide controls timer (3.5s of inactivity while playing)
    LaunchedEffect(showControls, isPlaying, scrubState.isActive, isLocked, isPipLayout, activeSheetTab, lastInteractionTrigger) {
        if (showControls && isPlaying && !scrubState.isActive && !isLocked && !isPipLayout && activeSheetTab == null) {
            delay(3500L)
            showControls = false
        }
    }

    // 8. Pickers for video file & external subtitles
    val subtitlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val path = it.path ?: it.toString()
            engine.addSubtitle(path)
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            engine.loadUri(it)
        }
    }

    val composeVideoBounds = remember(videoSourceRect) {
        videoSourceRect?.let {
            androidx.compose.ui.geometry.Rect(
                left = it.left.toFloat(),
                top = it.top.toFloat(),
                right = it.right.toFloat(),
                bottom = it.bottom.toFloat()
            )
        }
    }

    val mediaSubtitle = remember(videoDimensions, hwdecCurrent, fps) {
        val parts = mutableListOf<String>()
        if (videoDimensions.width > 0 && videoDimensions.height > 0) {
            parts.add("${videoDimensions.width}×${videoDimensions.height}")
        }
        if (fps > 0.0) {
            parts.add("${String.format(Locale.US, "%.0f", fps)}fps")
        }
        if (hwdecCurrent.isNotBlank() && hwdecCurrent != "no") {
            parts.add("HW")
        }
        if (parts.isNotEmpty()) parts.joinToString(" · ") else null
    }

    val gestureConfig = remember(settings, isLocked) {
        PlayerGestureConfig(
            isLocked = isLocked,
            isVolumeEnabled = settings.gesturesEnabled,
            isBrightnessEnabled = settings.gesturesEnabled,
            isSeekEnabled = settings.gesturesEnabled,
            isDoubleTapSeekEnabled = settings.gesturesEnabled,
            isDoubleTapPlayPauseEnabled = settings.gesturesEnabled,
            isLongPressSpeedEnabled = settings.gesturesEnabled,
            doubleTapSeekSeconds = settings.seekStepSeconds
        )
    }

    // Periodic progress persistence (every 4s while playing)
    LaunchedEffect(isPlaying, currentUri, historyRepository) {
        if (isPlaying && currentUri != null && historyRepository != null) {
            while (true) {
                delay(4000L)
                val curPos = engine.timePosMs.value
                val curDur = engine.durationMs.value
                if (curDur > 0L) {
                    historyRepository.saveProgress(currentUri, curPos, curDur)
                }
            }
        }
    }

    // Save on disposal (when leaving player screen or switching videos)
    DisposableEffect(currentUri, historyRepository) {
        onDispose {
            if (currentUri != null && historyRepository != null) {
                val curPos = engine.timePosMs.value
                val curDur = engine.durationMs.value
                if (curDur > 0L) {
                    historyRepository.saveProgress(currentUri, curPos, curDur)
                }
            }
        }
    }

    // Show brief top notice if auto-resumed at a position > 2s
    var hasShownResumeNotice by remember(currentUri) { mutableStateOf(false) }
    LaunchedEffect(timePosMs, currentUri) {
        if (!hasShownResumeNotice && timePosMs > 2000L && playbackState == PlaybackState.READY) {
            hasShownResumeNotice = true
            gestureHudState = GestureHudState.Notice(
                context.getString(R.string.player_resumed_notice, formatTime(timePosMs))
            )
            delay(2500L)
            if (gestureHudState is GestureHudState.Notice) {
                gestureHudState = GestureHudState.Idle
            }
        }
    }

    val gestureModifier = if (!isPipLayout && !pipEntering) {
        Modifier.playerGestures(
            context = context,
            isLocked = isLocked,
            isPlaying = isPlaying,
            currentPosMs = { timePosMs },
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
                showControls = !showControls
                if (showControls) {
                    resetControlsTimeout()
                }
            },
            onDoubleTapSeek = { deltaMs ->
                engine.seekRelative(deltaMs)
            },
            onDoubleTapPlayPause = {
                engine.togglePlayPause()
            },
            onFastForwardStart = {
                engine.setSpeed(2.0f)
                gestureHudState = GestureHudState.Speed(2.0f)
            },
            onFastForwardEnd = {
                engine.setSpeed(1.0f)
            },
            onHudStateChange = { newState ->
                gestureHudState = newState
            },
            config = gestureConfig
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
    ) {
        // A. Direct Native EGL Video Surface with Hardware graphicsLayer Transform
        GyroPlayerSurface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoomState.scale
                    scaleY = zoomState.scale
                    translationX = zoomState.translationX
                    translationY = zoomState.translationY
                    transformOrigin = zoomState.transformOrigin
                },
            engine = engine
        )

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
        if (!isPipLayout && !pipEntering) {
            PlayerHudOverlay(
                hudState = gestureHudState
            )
        }

        // F. Top & Bottom Streamline Controls Overlay (hidden in PiP)
        if (!isPipLayout && !pipEntering) {
            PlayerControlsOverlay(
                isVisible = showControls,
                isLocked = isLocked,
                title = mediaTitle.ifBlank { "Gyro Player" },
                mediaSubtitle = mediaSubtitle,
                isPlaying = isPlaying,
                timePosMs = timePosMs,
                durationMs = durationMs,
                bufferedPosMs = bufferedPosMs,
                hwdec = hwdecCurrent.ifBlank { "mediacodec" },
                fps = fps,
                droppedFrames = droppedFrames,
                powerMetrics = powerMetrics,
                resizeMode = resizeMode,
                speed = speed,
                scrubState = scrubState,
                onTogglePlayPause = {
                    resetControlsTimeout()
                    engine.togglePlayPause()
                },
                onToggleLock = {
                    resetControlsTimeout()
                    isLocked = !isLocked
                    showControls = !isLocked
                },
                onSeekToFraction = { fraction ->
                    resetControlsTimeout()
                    val target = (fraction * durationMs).toLong()
                    engine.seekTo(target)
                },
                onRewind10 = {
                    resetControlsTimeout()
                    engine.seekRelative(-settings.seekStepSeconds * 1000L)
                },
                onForward10 = {
                    resetControlsTimeout()
                    engine.seekRelative(settings.seekStepSeconds * 1000L)
                },
                onOpenSheetTab = { tab ->
                    resetControlsTimeout()
                    activeSheetTab = tab
                },
                onEnterPiP = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && componentActivity != null) {
                        if (pipEntering) return@PlayerControlsOverlay
                        pipEntering = true
                        showControls = false
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
                                pipEntering = false
                                showControls = true
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
                onBack = onBack
            )
        }

        // G. Modal Tabbed Settings & Calibrator Bottom Sheet
        activeSheetTab?.let { tab ->
            if (!isPipLayout && !pipEntering) {
                PlayerTrackSelectionSheet(
                    initialTab = tab,
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
                    onDismiss = { activeSheetTab = null },
                    onSelectTrack = { type, id -> engine.selectTrack(type, id) },
                    onSelectResizeMode = { mode -> engine.setResizeMode(mode) },
                    onSelectSpeed = { sp -> engine.setSpeed(sp) },
                    onSetSubDelay = { delay -> engine.setSubtitleDelay(delay) },
                    onSetAudioDelay = { delay -> engine.setAudioDelay(delay) },
                    onSetSubScale = { scale -> engine.setSubtitleScale(scale) },
                    onSetSubPos = { pos -> engine.setSubtitlePosition(pos) },
                    onToggleHwdec = { engine.toggleHwdec() },
                    onAddExternalSubtitle = {
                        subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/*"))
                    },
                    onOpenFullSettings = {
                        activeSheetTab = null
                        onOpenSettings()
                    }
                )
            }
        }
    }
}
