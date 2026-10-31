package dev.gyro.player.ui

import android.app.Activity
import android.app.PictureInPictureParams
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

    // 2. UI Interactive States
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var showTracksSheet by remember { mutableStateOf(false) }
    var gestureHudState by remember { mutableStateOf<GestureHudState>(GestureHudState.Idle) }
    val scrubState = remember { PlayerScrubState() }

    // 3. Power Monitoring
    val powerMonitor = remember { PowerMonitor(context) }
    val powerMetrics by powerMonitor.powerMetrics.collectAsState()
    LaunchedEffect(Unit) {
        powerMonitor.startMonitoring(this)
    }

    // 4. Auto-hide controls timer
    LaunchedEffect(showControls, isPlaying, scrubState.isActive, isLocked) {
        if (showControls && isPlaying && !scrubState.isActive && !isLocked) {
            delay(4000L)
            showControls = false
        }
    }

    // 5. SAF Pickers for video & external subtitles
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
        // A. Direct Native EGL Video Surface
        GyroPlayerSurface(
            modifier = Modifier.fillMaxSize(),
            engine = engine
        )

        // B. Buffering Spinner
        if (playbackState == PlaybackState.BUFFERING) {
            CircularProgressIndicator(
                color = Color(0xFF64B5F6),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // C. Gesture Feedback HUD Overlay (Tabular numbers, Anti-jitter)
        PlayerHudOverlay(
            hudState = gestureHudState
        )

        // D. Top & Bottom Modern Controls Overlay
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
            onBack = { activity?.finish() }
        )

        // E. Modal Track & Settings Selection Bottom Sheet
        if (showTracksSheet) {
            PlayerTrackSelectionSheet(
                tracks = tracks,
                currentResizeMode = resizeMode,
                currentSpeed = speed,
                onDismiss = { showTracksSheet = false },
                onSelectTrack = { type, id -> engine.selectTrack(type, id) },
                onSelectResizeMode = { mode -> engine.setResizeMode(mode) },
                onSelectSpeed = { sp -> engine.setSpeed(sp) },
                onAddExternalSubtitle = {
                    subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/*"))
                }
            )
        }
    }
}
