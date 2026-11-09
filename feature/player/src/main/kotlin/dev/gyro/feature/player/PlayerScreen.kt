package dev.gyro.feature.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.unit.toSize
import dev.gyro.engine.media.PlaylistManager
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.feature.player.component.PlayerControlsOverlay
import dev.gyro.feature.player.component.PlayerHudOverlay
import dev.gyro.feature.player.component.StatsDiagnosticOverlay
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSettingsSheet
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.gesture.*
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.trace.SwitchTrace
import dev.gyro.engine.ui.GyroPlayerSurface
import dev.gyro.engine.ui.GyroSurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // 字幕快开状态：当前字幕轨是否启用，以及最后一次选中的字幕轨 id（便于快速恢复）
    val subtitleEnabled = tracks.any { it.type == "sub" && it.isSelected }
    var lastSubTrackId by remember { mutableIntStateOf(-1) }
    LaunchedEffect(tracks) {
        tracks.firstOrNull { it.type == "sub" && it.isSelected }?.let { lastSubTrackId = it.id }
    }

    // 常驻诊断浮层开关（STATS sheet 中切换；会话级状态，跨播放切换保留由用户重新开关）
    var statsOverlayEnabled by remember { mutableStateOf(false) }

    // 实时码率波形采样序列（环形缓存，每 500ms 采样一次）
    // 性能优化：仅当用户开启常驻诊断浮层，或抽屉切至统计/设置中心时才启动采样协程；关闭时自动取消协程，零后台 CPU / 零 UI 重组开销
    val bitrateHistory = remember { androidx.compose.runtime.mutableStateListOf<Long>() }
    var liveVideoBitrate by remember { mutableLongStateOf(0L) }
    var liveAudioBitrate by remember { mutableLongStateOf(0L) }
    val isStatsActive = statsOverlayEnabled ||
        playerState.activeSheetTab == dev.gyro.feature.player.component.sheet.PlayerSheetTab.STATS ||
        playerState.activeSheetTab == dev.gyro.feature.player.component.sheet.PlayerSheetTab.SETTINGS_HUB

    LaunchedEffect(isPlaying, isStatsActive) {
        if (!isPlaying || !isStatsActive) return@LaunchedEffect
        while (true) {
            val (vLive, aLive) = engine.queryLiveBitrate()
            val currentV: Long = if (vLive > 0L) {
                vLive
            } else if (snapshot.videoBitrate > 0L) {
                snapshot.videoBitrate
            } else {
                0L
            }
            if (currentV > 0L) {
                liveVideoBitrate = currentV
                bitrateHistory.add(currentV)
                if (bitrateHistory.size > 50) {
                    bitrateHistory.removeAt(0)
                }
            }
            if (aLive > 0L) {
                liveAudioBitrate = aLive
            } else if (snapshot.audioBitrate > 0L) {
                liveAudioBitrate = snapshot.audioBitrate
            }
            kotlinx.coroutines.delay(500L)
        }
    }

    var surfaceViewRef by remember { mutableStateOf<GyroSurfaceView?>(null) }

    // 截图：优先通过 PixelCopy 从硬件合成缓冲无损抓取当前帧，并写入系统 MediaStore 相册
    fun takeScreenshot() {
        scope.launch(Dispatchers.IO) {
            val sv = surfaceViewRef
            var saved = false
            if (sv != null) {
                val bitmap = sv.captureBitmap()
                if (bitmap != null) {
                    val uri = runCatching {
                        val filename = "Gyro_${System.currentTimeMillis()}.jpg"
                        val contentValues = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gyro")
                                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                            }
                        }
                        val resolver = context.contentResolver
                        val insertUri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        if (insertUri != null) {
                            resolver.openOutputStream(insertUri)?.use { os ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, os)
                            }
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                contentValues.clear()
                                contentValues.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                                resolver.update(insertUri, contentValues, null, null)
                            }
                        }
                        insertUri
                    }.getOrNull()
                    bitmap.recycle()
                    saved = (uri != null)
                }
            }
            if (!saved) {
                // 回退到 mpv screenshot-to-file
                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                if (dir != null) {
                    val file = java.io.File(dir, "Gyro_${System.currentTimeMillis()}.jpg")
                    val ok = engine.screenshotToFile(file)
                    if (ok) {
                        android.media.MediaScannerConnection.scanFile(
                            context,
                            arrayOf(file.absolutePath),
                            arrayOf("image/jpeg"),
                            null
                        )
                        saved = true
                    }
                }
            }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(if (saved) R.string.screenshot_saved else R.string.screenshot_failed),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
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
    var showFormatBadgeToast by remember { mutableStateOf(false) }
    LaunchedEffect(activeUri, hdrFormat) {
        if (hdrFormat.isNotBlank()) {
            showFormatBadgeToast = true
            delay(3200L)
            showFormatBadgeToast = false
        } else {
            showFormatBadgeToast = false
        }
    }

    val playlistMgr = playlistManager ?: engine.playlist
    val playlist by playlistMgr.items.collectAsState()
    val currentPlaylistIndex by playlistMgr.currentIndex.collectAsState()
    val hasPrevious = playlistMgr.hasPrevious
    val hasNext = playlistMgr.hasNext

    // In-memory poster bitmap cache to eliminate blank/black flashes during swipe transitions
    val thumbnailCache = remember { mutableStateMapOf<String, android.graphics.Bitmap>() }
    LaunchedEffect(playlist) {
        withContext(Dispatchers.IO) {
            playlist.forEach { item ->
                val uriStr = item.uriString
                if (!thumbnailCache.containsKey(uriStr)) {
                    val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@forEach
                    val bmp = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
                            context.contentResolver.loadThumbnail(uri, Size(720, 1280), null)
                        } else {
                            val path = uri.path ?: uriStr
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                android.media.ThumbnailUtils.createVideoThumbnail(
                                    java.io.File(path),
                                    Size(720, 1280),
                                    null
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                android.media.ThumbnailUtils.createVideoThumbnail(
                                    path,
                                    android.provider.MediaStore.Images.Thumbnails.MINI_KIND
                                )
                            }
                        }
                    }.getOrNull()
                    if (bmp != null) {
                        thumbnailCache[uriStr] = bmp
                    }
                }
            }
        }
    }

    LaunchedEffect(currentUri) {
        if (currentUri != null && viewModel.activeUri.value != currentUri) {
            val item = playlist.firstOrNull { it.uriString == currentUri.toString() }
            viewModel.openUri(
                currentUri,
                initialWidth = item?.width ?: 0,
                initialHeight = item?.height ?: 0
            )
        }
    }

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

    // 统一管控画中画生命周期、转场防残影与动态参数协商
    val pipState = rememberPlayerPipState(
        activity = componentActivity,
        isPlaying = isPlaying,
        autoPipEnabled = settings.autoPip,
        targetAspectRatio = targetAspectRatio,
        videoSourceRect = videoSourceRect,
        onControlsVisibilityChange = { playerState.showControls = it }
    )
    val isPipLayout = pipState.isPipLayout
    val hideChrome = pipState.shouldHideChrome

    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
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
        if (wasInPipLayout && !isPipLayout) {
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
    var resetJob by remember { mutableStateOf<Job?>(null) }

    // 5. Power & Thermal Monitor
    val powerMonitor = remember { PowerMonitor(context) }
    val powerMetrics by powerMonitor.powerMetrics.collectAsState()
    LaunchedEffect(Unit) {
        powerMonitor.startMonitoring(this)
    }

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
    val density = LocalDensity.current
    val videoBottomDp = remember(videoContainerOrigin, videoContainerSize, density) {
        if (videoContainerSize.height > 0) {
            with(density) { (videoContainerOrigin.y + videoContainerSize.height).toDp() }
        } else null
    }

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
            onSeekTo = { targetMs ->
                engine.seekTo(targetMs)
            },
            onPlayPrevious = {
                viewModel.playPrevious()
            },
            onPlayNext = {
                viewModel.playNext()
            },
            videoBounds = { composeVideoBounds },
            config = gestureConfig
        )
    } else {
        Modifier
    }

    val firstFrameRendered = snapshot.firstFrameRendered
    val geometryReady = snapshot.geometryReady
    val sessionState = snapshot.sessionState
    val isSwitching = sessionState is dev.gyro.engine.session.PlaybackSessionState.Switching
    var isShutterReady by remember(activeUri, sessionState) { mutableStateOf(false) }
    var shutterTimedOut by remember(activeUri, sessionState) { mutableStateOf(false) }
    LaunchedEffect(activeUri, sessionState, firstFrameRendered, geometryReady, videoContainerSize, videoAspect) {
        SwitchTrace.log("SHUTTER_EVALUATE", "activeUri=$activeUri, firstFrame=$firstFrameRendered, geometryReady=$geometryReady, containerSize=$videoContainerSize, aspect=$videoAspect, isSwitching=$isSwitching")
        if (isSwitching || !firstFrameRendered) {
            isShutterReady = false
            return@LaunchedEffect
        }
        val decision = ShutterRevealPolicy.evaluate(
            firstFrameRendered = firstFrameRendered,
            geometryReady = geometryReady,
            videoAspect = videoAspect,
            videoWidth = videoDimensions.width,
            videoHeight = videoDimensions.height,
            resizeMode = resizeMode,
            containerWidth = videoContainerSize.width,
            containerHeight = videoContainerSize.height
        )
        if (decision == ShutterRevealPolicy.Decision.Reveal) {
            withFrameNanos { }
            withFrameNanos { }
            SwitchTrace.end("SHUTTER_REVEALED", "aspect=$videoAspect, containerSize=$videoContainerSize")
            isShutterReady = true
            shutterTimedOut = false
            return@LaunchedEffect
        }
        isShutterReady = false
        delay(1_500L)
        // Force reveal after the deadline no matter which gate is stuck - a permanent black
        // screen is worse than a transiently mis-shaped frame.
        SwitchTrace.log(
            "SHUTTER_TIMEOUT_EXPIRED",
            "firstFrame=${engine.state.value.firstFrameRendered}, geometry=${engine.state.value.geometryReady}"
        )
        shutterTimedOut = true
        isShutterReady = true
    }
    val showShutter = !isPipLayout && activeUri != null &&
        playbackState != PlaybackState.ERROR &&
        (!firstFrameRendered || !isShutterReady || isSwitching) &&
        !shutterTimedOut

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
                        playlistMgr.playAtIndex(settledPage)?.let { playMediaItem(it) }
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
                currentPlaylistIndex.toFloat() - scrollPos
            }
        }

        val isSheetActive = !isLandscape && playerState.activeSheetTab != null

        // 竖屏下弹起抽屉时：横屏视频平滑上移至上半区中央，竖屏短视频保持原始全屏比例不缩放
        val density = LocalDensity.current
        val statusBarInsetPx = with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() }

        val targetShiftY = remember(isSheetActive, containerSize, videoAspect, statusBarInsetPx) {
            if (!isSheetActive || containerSize.height <= 0) {
                0f
            } else if (videoAspect >= 1.0f) {
                // 横屏视频 (16:9 / 21:9 / 4:3)：平滑上移至上半区视口正中央，完全脱离底部抽屉遮挡
                val totalH = containerSize.height.toFloat()
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
                    val containerH = containerSize.height.toFloat()
                    val offsetY = if (containerH > 0f) pageOffset * containerH else 0f
                    scaleX = zoomState.scale
                    scaleY = zoomState.scale
                    translationX = (videoContainerOrigin.x - zoomState.contentOffset.x) * zoomState.scale - videoContainerOrigin.x
                    translationY = offsetY + animatedSheetShiftY +
                        ((videoContainerOrigin.y - zoomState.contentOffset.y) * zoomState.scale - videoContainerOrigin.y)
                    transformOrigin = zoomState.transformOrigin
                },
            engine = engine,
            videoDimensions = videoDimensions,
            videoFrameRate = videoFrameRate,
            pipTransitionActive = pipState.isTransitionActive,
            onSurfaceViewAvailable = { surfaceViewRef = it }
        )

        val activePlaylistItem = playlist.getOrNull(currentPlaylistIndex)
        val isCurrentVideoReady = activePlaylistItem != null &&
            activePlaylistItem.uriString == activeUri?.toString() &&
            firstFrameRendered &&
            !isSwitching

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
            val isActive = page == currentPlaylistIndex
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
                visible = showFormatBadgeToast,
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
        if (!isPipLayout && !hideChrome && statsOverlayEnabled && !playerState.showControls) {
            StatsDiagnosticOverlay(
                fps = snapshot.fps,
                droppedFrames = snapshot.droppedFrames,
                hwdec = snapshot.hwdecCurrent,
                vsyncRatio = snapshot.vsyncRatio,
                speed = snapshot.speed,
                bitrate = liveVideoBitrate,
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
                onSelectPlaylistItem = { idx ->
                    playerState.resetControlsTimeout()
                    playlistMgr.playAtIndex(idx)?.let { playMediaItem(it) }
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
                subtitleEnabled = subtitleEnabled,
                onToggleSubtitle = {
                    playerState.resetControlsTimeout()
                    if (subtitleEnabled) {
                        engine.trackManager.selectTrack("sub", 0)
                    } else {
                        engine.trackManager.selectTrack("sub", lastSubTrackId.coerceAtLeast(1))
                    }
                },
                abLoopASeconds = snapshot.abLoopASeconds,
                abLoopBSeconds = snapshot.abLoopBSeconds,
                onCycleAbLoop = {
                    playerState.resetControlsTimeout()
                    engine.cycleAbLoop()
                },
                onScreenshot = {
                    playerState.resetControlsTimeout()
                    takeScreenshot()
                },
                onOpenSheetTab = { tab ->
                    // 打开 sheet 时收起控制层（取消自动隐藏计时），避免顶/底栏透过 scrim 与 sheet 叠显
                    playerState.hideControls()
                    playerState.activeSheetTab = tab
                },
                onEnterPiP = {
                    pipState.enterPip()
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
                chapters = chapters,
                videoBottomDp = videoBottomDp,
                hdrFormat = hdrFormat
            )
        }

        // G. Modal Tabbed Settings & Calibrator Bottom Sheet
        AnimatedVisibility(
            visible = playerState.activeSheetTab != null && !isPipLayout && !hideChrome,
            enter = if (isLandscape) {
                slideInHorizontally(initialOffsetX = { it }) + fadeIn()
            } else {
                slideInVertically(initialOffsetY = { it }) + fadeIn()
            },
            exit = if (isLandscape) {
                slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
            } else {
                slideOutVertically(targetOffsetY = { it }) + fadeOut()
            }
        ) {
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
                PlayerSettingsSheet(
                    initialTab = tab,
                    isLandscape = isLandscape,
                    state = PlayerSheetUiState(
                        playlist = playlist,
                        currentPlaylistIndex = currentPlaylistIndex,
                        chapters = chapters,
                        currentChapterIndex = currentChapter,
                        currentTimePosMs = timePosMs,
                        tracks = tracks,
                        secondarySubId = snapshot.secondarySubId,
                        statsOverlayEnabled = statsOverlayEnabled,
                        currentResizeMode = resizeMode,
                        currentSpeed = speed,
                        subDelaySec = subDelaySec,
                        audioDelaySec = audioDelaySec,
                        subScale = subScale,
                        subPos = subPos,
                        hwdec = hwdecCurrent,
                        fps = fps,
                        droppedFrames = droppedFrames,
                        videoBitrate = liveVideoBitrate,
                        audioBitrate = liveAudioBitrate,
                        bitrateHistory = bitrateHistory.toList(),
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
                        isHdr = snapshot.isHdr,
                        hdrFormat = snapshot.hdrFormat
                    ),
                    callbacks = PlayerSheetCallbacks(
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
                        onToggleStatsOverlay = { statsOverlayEnabled = it },
                        onAddExternalSubtitle = {
                            subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/*"))
                        },
                        onOpenFullSettings = {
                            playerState.activeSheetTab = null
                            onOpenSettings()
                        }
                    ),
                    onDismiss = {
                        playerState.activeSheetTab = null
                        // 关闭 sheet 后短暂唤回控制层，随后按自动隐藏节奏淡出
                        playerState.showControls()
                    }
                )
            }
        }
    }
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
        val uri = runCatching { Uri.parse(uriStr ?: "") }.getOrNull() ?: return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
                    context.contentResolver.loadThumbnail(uri, Size(720, 1280), null)
                } else {
                    val path = uri.path ?: (uriStr ?: "")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        android.media.ThumbnailUtils.createVideoThumbnail(
                            java.io.File(path),
                            Size(720, 1280),
                            null
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        android.media.ThumbnailUtils.createVideoThumbnail(
                            path,
                            android.provider.MediaStore.Images.Thumbnails.MINI_KIND
                        )
                    }
                }
            }.getOrNull()
        }
        if (loaded != null) {
            bitmap = loaded
            uriStr?.let { (thumbnailCache as? MutableMap<String, android.graphics.Bitmap>)?.put(it, loaded) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
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
