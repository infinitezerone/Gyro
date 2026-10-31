package dev.gyro.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.ui.GyroPlayerSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    engine: GyroPlayerEngine,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val componentActivity = remember(context) { context.findComponentActivity() }
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()

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
    val videoDimensions by engine.videoDimensions.collectAsState()

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
    var showTracksSheet by remember { mutableStateOf(false) }
    var gestureHudState by remember { mutableStateOf<GestureHudState>(GestureHudState.Idle) }
    val scrubState = remember { PlayerScrubState() }

    // 4. Anchored Pinch-to-Zoom & Pan State (Compose Style API Math)
    val zoomState = rememberPlayerZoomState()

    // 5. Power & Thermal Monitor
    val powerMonitor = remember { PowerMonitor(context) }
    val powerMetrics by powerMonitor.powerMetrics.collectAsState()
    LaunchedEffect(Unit) {
        powerMonitor.startMonitoring(this)
    }

    // 6. Sticky Immersive System Bars (Transient Swipe)
    LaunchedEffect(showControls, isPipLayout) {
        val window = activity?.window ?: return@LaunchedEffect
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showControls && !isPipLayout) {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 7. Auto-hide controls timer
    LaunchedEffect(showControls, isPlaying, scrubState.isActive, isLocked, isPipLayout) {
        if (showControls && isPlaying && !scrubState.isActive && !isLocked && !isPipLayout) {
            delay(4000L)
            showControls = false
        }
    }

    // 8. SAF Pickers for video & external subtitles
    val subtitlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val path = it.path ?: it.toString()
            engine.addSubtitle(path)
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
            onZoomChange = { zoom, pan, centroid ->
                val vpSize = containerSize.toSize()
                zoomState.onGesture(
                    zoomChange = zoom,
                    panDelta = pan,
                    centroid = centroid,
                    viewportSize = vpSize
                )
            },
            onResetZoom = {
                zoomState.reset()
            },
            onSingleTap = {
                showControls = !showControls
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
            }
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            .then(gestureModifier)
    ) {
        // A. Direct Native EGL Video Surface with Hardware graphicsLayer Transform.
        // Pure RenderNode transformation in draw phase without JNI IPC or MPV mutex locks.
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

        // B. Buffering Spinner
        if (playbackState == PlaybackState.BUFFERING && !isPipLayout) {
            CircularProgressIndicator(
                color = Color(0xFF64B5F6),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // C. MX Player Style Vertical Sliders & Central Seek Card HUD (hidden in PiP)
        if (!isPipLayout && !pipEntering) {
            PlayerHudOverlay(
                hudState = gestureHudState
            )
        }

        // D. Top & Bottom Streamline Controls Overlay (hidden in PiP)
        if (!isPipLayout && !pipEntering) {
            PlayerControlsOverlay(
                isVisible = showControls,
                isLocked = isLocked,
                title = "",
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
                onTogglePlayPause = { engine.togglePlayPause() },
                onToggleLock = {
                    isLocked = !isLocked
                    showControls = !isLocked
                },
                onSeekToFraction = { fraction ->
                    val target = (fraction * durationMs).toLong()
                    engine.seekTo(target)
                },
                onOpenTracksSheet = { showTracksSheet = true },
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
                onBack = { activity?.finish() }
            )
        }

        // E. Modal Track, Audio/Sub Sync, and Settings Sheet
        if (showTracksSheet && !isPipLayout && !pipEntering) {
            PlayerTrackSelectionSheet(
                tracks = tracks,
                currentResizeMode = resizeMode,
                currentSpeed = speed,
                subDelaySec = subDelaySec,
                audioDelaySec = audioDelaySec,
                onDismiss = { showTracksSheet = false },
                onSelectTrack = { type, id -> engine.selectTrack(type, id) },
                onSelectResizeMode = { mode -> engine.setResizeMode(mode) },
                onSelectSpeed = { sp -> engine.setSpeed(sp) },
                onSetSubDelay = { delay -> engine.setSubtitleDelay(delay) },
                onSetAudioDelay = { delay -> engine.setAudioDelay(delay) },
                onAddExternalSubtitle = {
                    subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/*"))
                }
            )
        }
    }
}
