package dev.gyro.feature.player

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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
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
import dev.gyro.core.data.repository.CompanionSubtitleScanner
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.PlaylistManager
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.ui.GyroSurfaceView
import dev.gyro.core.designsystem.R
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.ui.GyroPlayerSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun PlayerScreen(
    engine: GyroPlayerEngine,
    currentUri: Uri? = null,
    settings: UserSettings = UserSettings(),
    historyRepository: PlaybackHistoryRepository? = null,
    playlistManager: PlaylistManager? = null,
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
    // Rotation-aware: `videoDimensions` is the coded size, which is landscape for a portrait clip
    // that carries a 90 degree rotation flag. The PiP window and the letterbox maths need the shape
    // the viewer actually sees, otherwise a portrait video gets a landscape window.
    val videoAspect by engine.videoAspect.collectAsState()
    val mediaTitle by engine.mediaTitle.collectAsState()
    val chapters by engine.chapters.collectAsState()
    val currentChapter by engine.currentChapter.collectAsState()
    val shaderState by engine.shaderState.collectAsState()
    val deband by engine.deband.collectAsState()
    val brightness by engine.brightness.collectAsState()
    val contrast by engine.contrast.collectAsState()
    val saturation by engine.saturation.collectAsState()
    val nightMode by engine.nightMode.collectAsState()
    val stereoDownmix by engine.stereoDownmix.collectAsState()

    var activeUri by remember(currentUri) { mutableStateOf(currentUri) }

    val playlist by (playlistManager?.items ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    val currentPlaylistIndex by (playlistManager?.currentIndex ?: remember { kotlinx.coroutines.flow.MutableStateFlow(-1) }).collectAsState()
    val hasPrevious = playlistManager?.hasPrevious ?: false
    val hasNext = playlistManager?.hasNext ?: false

    fun playMediaItem(item: VideoMediaItem) {
        activeUri?.let { oldUri ->
            val curPos = engine.timePosMs.value
            val curDur = engine.durationMs.value
            if (curDur > 0L) {
                historyRepository?.saveProgress(oldUri, curPos, curDur)
            }
        }

        activeUri = item.uri
        val startPos = if (settings.autoResume) {
            val p = historyRepository?.getProgress(item.uri)
            if (p != null && !p.isCompleted && p.positionMs > 2000L) p.positionMs else 0L
        } else 0L

        engine.loadUri(item.uri, startPos)

        if (settings.subAutoLoad) {
            val subs = CompanionSubtitleScanner.findCompanionSubtitles(context, item.uri, item.displayName)
            subs.forEach { path -> engine.addSubtitle(path) }
        }
    }

    // Auto-advance to next video when current video completes
    LaunchedEffect(playbackState) {
        if (playbackState == PlaybackState.ENDED && playlistManager != null && playlistManager.hasNext) {
            delay(500L)
            val next = playlistManager.playNext()
            if (next != null) {
                playMediaItem(next)
            }
        }
    }

    // Auto-load companion subtitles asynchronously on IO dispatcher to prevent UI thread drops
    LaunchedEffect(activeUri) {
        activeUri?.let { uri ->
            if (settings.subAutoLoad) {
                withContext(Dispatchers.IO) {
                    val subs = CompanionSubtitleScanner.findCompanionSubtitles(context, uri)
                    subs.forEach { path -> engine.addSubtitle(path) }
                }
            }
        }
    }

    // Clear any residual MPV native video-zoom
    LaunchedEffect(Unit) {
        engine.resetVideoZoomAndPan()
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
    // Nav3 reuses a popped entry's composition when the same route re-enters, so `remember` state
    // survives the round trip: without this keyed reset, re-entering a video whose controls were
    // auto-hidden on the way out comes back with them still hidden.
    val screenEntryEpoch by engine.screenEntryEpoch.collectAsState()
    LaunchedEffect(screenEntryEpoch) {
        showControls = true
    }
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

    val shaderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            engine.importAndSetCustomShader(it)
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
            parts.add("${videoDimensions.width}x${videoDimensions.height}")
        }
        if (fps > 0.0) {
            parts.add("${String.format(Locale.US, "%.0f", fps)}fps")
        }
        if (hwdecCurrent.isNotBlank() && hwdecCurrent != "no") {
            parts.add("HW")
        }
        if (parts.isNotEmpty()) parts.joinToString(" · ") else null
    }

    val dynamicConfig = settings.gestureConfig
    val gestureConfig = remember(dynamicConfig, settings.gesturesEnabled, isLocked) {
        if (!settings.gesturesEnabled) {
            dynamicConfig.copy(
                isLocked = isLocked,
                isVolumeEnabled = false,
                isBrightnessEnabled = false,
                isSeekEnabled = false,
                isDoubleTapSeekEnabled = false,
                isDoubleTapPlayPauseEnabled = false,
                isLongPressSpeedEnabled = false,
                isZoomEnabled = false
            )
        } else {
            dynamicConfig.copy(isLocked = isLocked)
        }
    }

    // Periodic progress persistence (every 4s while playing)
    LaunchedEffect(isPlaying, activeUri, historyRepository) {
        if (isPlaying && activeUri != null && historyRepository != null) {
            while (true) {
                delay(4000L)
                val curPos = engine.timePosMs.value
                val curDur = engine.durationMs.value
                if (curDur > 0L) {
                    historyRepository.saveProgress(activeUri!!, curPos, curDur)
                }
            }
        }
    }

    // 退出播放器界面时平滑恢复系统栏状态，避免返回 HomeScreen 时状态栏撕裂跳变
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { window ->
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Save on disposal (when leaving player screen or switching videos)
    DisposableEffect(activeUri, historyRepository) {
        onDispose {
            engine.resetSoftwareGain()
            if (activeUri != null && historyRepository != null) {
                val curPos = engine.timePosMs.value
                val curDur = engine.durationMs.value
                if (curDur > 0L) {
                    historyRepository.saveProgress(activeUri!!, curPos, curDur)
                }
            }
        }
    }

    // Show brief top notice if auto-resumed at a position > 2s
    var hasShownResumeNotice by remember(activeUri) { mutableStateOf(false) }
    LaunchedEffect(playbackState, activeUri) {
        if (hasShownResumeNotice || playbackState != PlaybackState.READY) return@LaunchedEffect
        // time-pos ticks at video frame rate, so it cannot be an effect key: doing so relaunched
        // this effect on every frame and recomposed the whole screen with it. The clock is read
        // through a snapshot flow instead.
        //
        // The trigger is the FIRST decoded position, not a threshold: a resume load (start=+X)
        // decodes its first picture at the target timestamp, so a first frame past two minutes
        // means a resume happened. Waiting for "position > 2s" instead would also fire on
        // from-scratch playback the moment it reached two seconds.
        val firstFrameAtMs = snapshotFlow { engine.timePosMs.value }.first { it > 0L }
        if (firstFrameAtMs > 2000L) {
            hasShownResumeNotice = true
            gestureHudState = GestureHudState.Notice(
                context.getString(R.string.player_resumed_notice, formatTime(firstFrameAtMs))
            )
            delay(2500L)
            if (gestureHudState is GestureHudState.Notice) {
                gestureHudState = GestureHudState.Idle
            }
        }
    }

    var priorSpeedBeforeBoost by remember { mutableStateOf<Float?>(null) }

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
            onFastForwardStart = { targetSpeed ->
                priorSpeedBeforeBoost = speed
                engine.setSpeed(targetSpeed)
                gestureHudState = GestureHudState.Speed(targetSpeed)
            },
            onFastForwardEnd = {
                val restoreSpeed = priorSpeedBeforeBoost ?: 1.0f
                engine.setSpeed(restoreSpeed)
                priorSpeedBeforeBoost = null
                gestureHudState = GestureHudState.Idle
            },
            onHudStateChange = { newState ->
                gestureHudState = newState
            },
            onSoftwareVolumeChange = { gain ->
                engine.setSoftwareVolume(gain)
            },
            getSoftwareVolume = {
                engine.softwareVolume.value
            },
            config = gestureConfig
        )
    } else {
        Modifier
    }

    // The surface keeps the last buffer that was queued to it, so a freshly loaded file shows the
    // previous video until mpv presents its first frame - 290ms for a local 1080p file, seconds for
    // a remote one. Cover it while that is true. The surface is composited behind the window, so
    // an ordinary Composable sits on top of it; this is the same role as ExoPlayer's shutter.
    val firstFrameRendered by engine.firstFrameRendered.collectAsState()
    // Releasing the cover before the buffer and layout have taken the new file's shape lets the
    // surface geometry transition play in the open - on an aspect change the picture visibly
    // slides in from a corner. Both gates must be open.
    val geometryReady by engine.geometryReady.collectAsState()
    // A signal that never arrives must not leave a permanent black screen: the timeout covers a
    // slow open and recovers on its own if the native side ever stops reporting.
    var shutterTimedOut by remember(activeUri) { mutableStateOf(false) }
    LaunchedEffect(activeUri, firstFrameRendered, geometryReady) {
        if (firstFrameRendered && geometryReady) {
            shutterTimedOut = false
            return@LaunchedEffect
        }
        delay(4_000L)
        if (!engine.firstFrameRendered.value || !engine.geometryReady.value) shutterTimedOut = true
    }
    val shutterTarget = if (
        !isPipLayout && playbackState != PlaybackState.IDLE &&
        !(firstFrameRendered && geometryReady) && !shutterTimedOut
    ) 1f else 0f
    val shutterAlpha by animateFloatAsState(
        targetValue = shutterTarget,
        animationSpec = tween(durationMillis = 160),
        label = "shutter",
    )

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
        // A. Direct Native EGL Video Surface with Hardware graphicsLayer Transform
        // The buffer is pinned to the video's own aspect (see GyroSurfaceView), so the container
        // must keep that same aspect or the compositor stretches the picture: FIT letterboxes the
        // video inside the black Box above, CROP scales it past the edges for the parent to clip,
        // STRETCH is the user's explicit distortion, and the fixed ratios are the user's override.
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
        // The zoom model computes screen positions in fullscreen coordinates
        // ((content - offset) * scale), but this graphicsLayer lives on the aspect-ratio
        // container whose top-left is not the screen origin. Rewriting the translation into
        // container-local terms - (origin - offset) * scale - origin - keeps the finger
        // anchor exact wherever the container sits: identity stays identity, (c - 0)*1 - c = 0.
        var videoContainerOrigin by remember { mutableStateOf(Offset.Zero) }
        GyroPlayerSurface(
            modifier = videoContainerModifier
                // The outer Box centers nothing by default (its other children rely on top-start),
                // so the aspect-fitted container centers itself here; CROP's overflow is clipped
                // symmetrically for the same reason.
                .align(Alignment.Center)
                .onGloballyPositioned { videoContainerOrigin = it.positionInParent() }
                .graphicsLayer {
                    scaleX = zoomState.scale
                    scaleY = zoomState.scale
                    translationX = (videoContainerOrigin.x - zoomState.contentOffset.x) * zoomState.scale - videoContainerOrigin.x
                    translationY = (videoContainerOrigin.y - zoomState.contentOffset.y) * zoomState.scale - videoContainerOrigin.y
                    transformOrigin = zoomState.transformOrigin
                },
            engine = engine
        )

        // A2. Shutter over the file-switch window.
        if (shutterAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = shutterAlpha))
            )
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
                timePosMs = { timePosMs },
                durationMs = { durationMs },
                bufferedPosMs = { bufferedPosMs },
                resizeMode = resizeMode,
                speed = speed,
                scrubState = scrubState,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onTogglePlayPause = {
                    resetControlsTimeout()
                    engine.togglePlayPause()
                },
                onPlayPrevious = {
                    resetControlsTimeout()
                    playlistManager?.playPrevious()?.let { playMediaItem(it) }
                },
                onPlayNext = {
                    resetControlsTimeout()
                    playlistManager?.playNext()?.let { playMediaItem(it) }
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
                onBack = onBack,
                chapters = chapters
            )
        }

        // G. Modal Tabbed Settings & Calibrator Bottom Sheet
        activeSheetTab?.let { tab ->
            if (!isPipLayout && !pipEntering) {
                val currentMediaInfo = remember(activeSheetTab, activeUri) {
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
                    customShaders = remember(shaderState) { engine.getCustomShaders() },
                    deband = deband,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    nightMode = nightMode,
                    stereoDownmix = stereoDownmix,
                    onDismiss = { activeSheetTab = null },
                    onSelectPlaylistItem = { idx ->
                        playlistManager?.playAtIndex(idx)?.let { playMediaItem(it) }
                    },
                    onSelectChapter = { idx ->
                        engine.seekToChapter(idx)
                        activeSheetTab = null
                    },
                    onSelectShader = { shader -> engine.setShader(shader) },
                    onClearShader = { engine.clearShader() },
                    onSelectCustomShaderFile = { file ->
                        engine.setShader(GlslShader.CUSTOM, file.absolutePath, file.nameWithoutExtension)
                    },
                    onImportCustomShader = {
                        shaderPicker.launch(arrayOf("*/*"))
                    },
                    onSetDeband = { enabled -> engine.setDeband(enabled) },
                    onSetBrightness = { value -> engine.setBrightness(value) },
                    onSetContrast = { value -> engine.setContrast(value) },
                    onSetSaturation = { value -> engine.setSaturation(value) },
                    onResetColors = { engine.resetColors() },
                    onSetNightMode = { enabled -> engine.setNightMode(enabled) },
                    onSetStereoDownmix = { enabled -> engine.setStereoDownmix(enabled) },
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
