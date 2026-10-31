package dev.gyro.player.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.util.Rational
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.ui.GyroPlayerSurface
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    engine: GyroPlayerEngine,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
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

    // 2. UI Interactive States
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var showTracksSheet by remember { mutableStateOf(false) }
    var gestureHudState by remember { mutableStateOf<GestureHudState>(GestureHudState.Idle) }
    val scrubState = remember { PlayerScrubState() }

    // 3. Pinch-to-Zoom & Pan State (0.5x - 4.0x)
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // 4. Power & Thermal Monitor
    val powerMonitor = remember { PowerMonitor(context) }
    val powerMetrics by powerMonitor.powerMetrics.collectAsState()
    LaunchedEffect(Unit) {
        powerMonitor.startMonitoring(this)
    }

    // 5. Sticky Immersive System Bars (Transient Swipe)
    LaunchedEffect(showControls) {
        val window = activity?.window ?: return@LaunchedEffect
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showControls) {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 6. Auto-hide controls timer
    LaunchedEffect(showControls, isPlaying, scrubState.isActive, isLocked) {
        if (showControls && isPlaying && !scrubState.isActive && !isLocked) {
            delay(4000L)
            showControls = false
        }
    }

    // 7. SAF Pickers for video & external subtitles
    val subtitlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val path = it.path ?: it.toString()
            engine.addSubtitle(path)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .playerGestures(
                context = context,
                isLocked = isLocked,
                isPlaying = isPlaying,
                currentPosMs = { timePosMs },
                durationMs = { durationMs },
                scrubState = scrubState,
                zoomScale = zoomScale,
                onZoomChange = { zoom, pan ->
                    zoomScale = (zoomScale * zoom).coerceIn(0.5f, 4.0f)
                    panOffset += pan
                },
                onResetZoom = {
                    zoomScale = 1.0f
                    panOffset = Offset.Zero
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
    ) {
        // A. Direct Native EGL Video Surface with Pinch-to-Zoom Graphics Layer
        GyroPlayerSurface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panOffset.x
                    translationY = panOffset.y
                },
            engine = engine
        )

        // B. Buffering Spinner
        if (playbackState == PlaybackState.BUFFERING) {
            CircularProgressIndicator(
                color = Color(0xFF64B5F6),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // C. MX Player Style Vertical Sliders & Central Seek Card HUD
        PlayerHudOverlay(
            hudState = gestureHudState
        )

        // D. Top & Bottom Streamline Controls Overlay
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                    val aspectRatio = Rational(16, 9)
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(aspectRatio)
                        .build()
                    activity.enterPictureInPictureMode(params)
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

        // E. Modal Track, Audio/Sub Sync, and Settings Sheet
        if (showTracksSheet) {
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
