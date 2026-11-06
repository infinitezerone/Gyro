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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.gyro.core.data.repository.CompanionSubtitleScanner
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.PlaylistManager
import dev.gyro.core.data.repository.asAndroidUri
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.ui.GyroSurfaceView
import dev.gyro.core.designsystem.R
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.trace.SwitchTrace
import dev.gyro.engine.ui.GyroPlayerSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    viewModel: PlayerViewModel? = null,
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
    val durationMs by engine.durationMs.collectAsState()
    // Collected as State<Long> (without `by` delegation) so PlayerScreen itself does not read
    // `.value` and never recomposes on 10Hz time-pos ticks; only PlayerBottomBar reads `.value`
    // via the deferred lambda while the bottom bar is visible.
    val timePosState = engine.timePosMs.collectAsState()
    val bufferedPosState = engine.bufferedPosMs.collectAsState()
    val hwdecCurrent by engine.hwdecCurrent.collectAsState()
    val videoFrameRate by engine.videoFrameRate.collectAsState()
    val speed by engine.speed.collectAsState()
    val tracks by engine.tracks.collectAsState()
    val resizeMode by engine.resizeMode.collectAsState()
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

    val vmActiveUri by (viewModel?.activeUri ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()
    val engineCurrentUri by engine.currentUri.collectAsState()
    var activeUri by remember(currentUri) { mutableStateOf(currentUri ?: vmActiveUri ?: engineCurrentUri) }

    LaunchedEffect(vmActiveUri) {
        if (vmActiveUri != null && vmActiveUri != activeUri) {
            activeUri = vmActiveUri
        }
    }
    LaunchedEffect(engineCurrentUri) {
        if (engineCurrentUri != null && engineCurrentUri != activeUri) {
            activeUri = engineCurrentUri
        }
    }
    LaunchedEffect(currentUri) {
        if (currentUri != null) {
            if (viewModel != null && viewModel.activeUri.value != currentUri) {
                viewModel.openUri(currentUri)
            }
            activeUri = currentUri
        }
    }

    val playlist by (playlistManager?.items ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    val currentPlaylistIndex by (playlistManager?.currentIndex ?: remember { kotlinx.coroutines.flow.MutableStateFlow(-1) }).collectAsState()
    val hasPrevious = playlistManager?.hasPrevious ?: false
    val hasNext = playlistManager?.hasNext ?: false

    fun openUri(uri: Uri) {
        if (viewModel != null) {
            viewModel.openUri(uri)
            activeUri = uri
            return
        }
        activeUri?.let { oldUri ->
            val curPos = engine.timePosMs.value
            val curDur = engine.durationMs.value
            if (curDur > 0L) {
                historyRepository?.saveProgress(oldUri, curPos, curDur)
            }
        }

        activeUri = uri
        val startPos = if (settings.autoResume) {
            val p = historyRepository?.getProgress(uri)
            if (p != null && !p.isCompleted && p.positionMs > 2000L) p.positionMs else 0L
        } else 0L

        engine.loadUri(uri, startPos)
    }

    fun playMediaItem(item: VideoMediaItem) {
        if (viewModel != null) {
            viewModel.playMediaItem(item)
            activeUri = item.asAndroidUri
        } else {
            openUri(Uri.parse(item.uriString))
        }
    }

    // Auto-advance to next video when current video completes (fallback if viewModel absent)
    LaunchedEffect(playbackState) {
        if (viewModel == null && playbackState == PlaybackState.ENDED && playlistManager != null && playlistManager.hasNext) {
            delay(500L)
            val next = playlistManager.playNext()
            if (next != null) {
                playMediaItem(next)
            }
        }
    }

    // Auto-load companion subtitles asynchronously on IO dispatcher (fallback if viewModel absent)
    LaunchedEffect(activeUri) {
        if (viewModel == null) {
            activeUri?.let { uri ->
                if (settings.subAutoLoad) {
                    val knownDisplayName = playlist.firstOrNull { it.uriString == uri.toString() }?.displayName
                    withContext(Dispatchers.IO) {
                        val subs = CompanionSubtitleScanner.findCompanionSubtitles(context, uri, knownDisplayName)
                        subs.forEach { path -> engine.addSubtitle(path) }
                    }
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

    // Chrome visibility is a *derived* value, never a latch that some later event has to remember to
    // clear. Two signals feed it:
    // - the platform leave hint, delivered before the window starts shrinking (home, recents, another
    //   app). The PiP mode callback only arrives mid-animation, so `isPipLayout` alone cannot hide the
    //   controls in time - controls that shrink with the window are the exact artifact this prevents.
    //   The hint clears itself on ON_RESUME, so a trip to the background that never enters PiP (a
    //   paused player, for one) cannot leave the player without chrome on the way back.
    // - an explicit PiP request, the one path the hint does not cover: that transition starts while
    //   the activity is still in the foreground. Cleared as soon as the mode change lands.
    val leavingForeground = rememberUserLeavingHint()
    var pipRequested by remember { mutableStateOf(false) }
    val hideChrome = leavingForeground || pipRequested

    LaunchedEffect(isInPipMode) {
        // The request has served its purpose once the mode change lands in either direction; keeping
        // it set would leave the controls hidden after the return to full screen.
        pipRequested = false
    }
    // Safety net for a request the system drops without reporting a failure.
    LaunchedEffect(pipRequested) {
        if (!pipRequested) return@LaunchedEffect
        delay(PIP_ENTER_TIMEOUT_MS)
        pipRequested = false
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
    // Leaving PiP is not a user tap, so it must not summon the controls: the small window the user
    // just left was control-free, and landing back in full screen with every bar suddenly visible
    // reads as a glitch. Only an explicit tap brings them back from here.
    var wasInPipLayout by remember { mutableStateOf(false) }
    LaunchedEffect(isPipLayout) {
        if (wasInPipLayout && !isPipLayout) showControls = false
        wasInPipLayout = isPipLayout
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

    // A file switch must start from the identity transform: a leftover pinch scale or pan from the
    // previous movie re-anchors the new picture off-centre, and the settle-back animation then
    // reads as the video growing out of a corner.
    LaunchedEffect(activeUri) {
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
            openUri(it)
        }
    }

    val shaderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            engine.importAndSetCustomShader(it)
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

    // Periodic progress persistence (fallback if viewModel absent)
    LaunchedEffect(isPlaying, activeUri, historyRepository) {
        if (viewModel == null && isPlaying && activeUri != null && historyRepository != null) {
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
            if (viewModel == null && activeUri != null && historyRepository != null) {
                val curPos = engine.timePosMs.value
                val curDur = engine.durationMs.value
                if (curDur > 0L) {
                    historyRepository.saveProgress(activeUri!!, curPos, curDur)
                }
            }
        }
    }

    var priorSpeedBeforeBoost by remember { mutableStateOf<Float?>(null) }

    // Input depends on the layout, not on the transition: a gesture layer switched off by a lifecycle
    // hint is one more piece of state that can be left behind, and nothing can be tapped while the
    // window is animating away anyway.
    val gestureModifier = if (!isPipLayout) {
        Modifier.playerGestures(
            context = context,
            isLocked = isLocked,
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
    var isShutterReady by remember(activeUri) { mutableStateOf(false) }
    var shutterTimedOut by remember(activeUri) { mutableStateOf(false) }
    LaunchedEffect(activeUri, firstFrameRendered, geometryReady) {
        SwitchTrace.log("SHUTTER_EVALUATE", "activeUri=$activeUri, firstFrame=$firstFrameRendered, geometryReady=$geometryReady")
        if (firstFrameRendered && geometryReady) {
            // Wait for one VSYNC frame after geometry & first frame are ready so Compose
            // and SurfaceFlinger have committed the new layout bounds before revealing.
            withFrameNanos { }
            SwitchTrace.end("SHUTTER_REVEALED", "aspect=$videoAspect, containerSize=$videoContainerSize")
            isShutterReady = true
            shutterTimedOut = false
            return@LaunchedEffect
        }
        isShutterReady = false
        delay(4_000L)
        if (!engine.firstFrameRendered.value || !engine.geometryReady.value) {
            SwitchTrace.log("SHUTTER_TIMEOUT_EXPIRED", "firstFrame=${engine.firstFrameRendered.value}, geometry=${engine.geometryReady.value}")
            shutterTimedOut = true
            isShutterReady = true
        }
    }
    val showShutter = !isPipLayout && playbackState != PlaybackState.IDLE &&
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
        GyroPlayerSurface(
            modifier = videoContainerModifier
                // The outer Box centers nothing by default (its other children rely on top-start),
                // so the aspect-fitted container centers itself here; CROP's overflow is clipped
                // symmetrically for the same reason.
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
            engine = engine
        )

        // A2. Shutter over the file-switch window.
        if (showShutter) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
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
        if (!isPipLayout && !hideChrome) {
            PlayerHudOverlay(
                hudState = gestureHudState
            )
        }

        // F. Top & Bottom Streamline Controls Overlay (hidden in PiP)
        if (!isPipLayout && !hideChrome) {
            PlayerControlsOverlay(
                isVisible = showControls,
                isLocked = isLocked,
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
                        if (pipRequested) return@PlayerControlsOverlay
                        pipRequested = true
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
                                pipRequested = false
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
            if (!isPipLayout && !hideChrome) {
                val timePosMs by engine.timePosMs.collectAsState()
                val fps by engine.fps.collectAsState()
                val droppedFrames by engine.droppedFrames.collectAsState()
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
                        activeSheetTab = null
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
