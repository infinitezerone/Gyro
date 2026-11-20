package dev.gyro.feature.player

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gyro.core.model.MediaInfo
import dev.gyro.core.model.PowerMetrics
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.feature.player.component.sheet.PlayerPlaylistSheet
import dev.gyro.feature.player.component.sheet.PlayerSettingsSheet
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.rememberSheetNavState

/**
 * Modal tabbed settings / calibrator / playlist sheet host.
 *
 * Mounted while the sheet is opening, open, or animating shut via [AnchoredSheetScaffold], so
 * telemetry sampling and mpv diagnostics only tick while the sheet is actually on screen.
 */
@Composable
internal fun PlayerSheetsHost(
    tab: PlayerSheetRoute,
    visible: Boolean,
    isLandscape: Boolean,
    viewModel: PlayerViewModel,
    playerState: PlayerState,
    snapshot: PlaybackSnapshot,
    playlist: List<VideoMediaItem>,
    currentPlaylistIndex: Int,
    mediaTitle: String,
    activeUri: Uri?,
    telemetryState: PlayerTelemetryState,
    powerMetrics: PowerMetrics,
    onImportCustomShader: () -> Unit,
    onAddExternalSubtitle: () -> Unit,
    onOpenFullSettings: () -> Unit,
    onEnterPip: () -> Unit,
    onHidden: () -> Unit,
) {
    val timePosMs by viewModel.timePosMs.collectAsStateWithLifecycle()
    val telemetry by viewModel.diagnostics.collectAsStateWithLifecycle()
    val sleepTimerSec by viewModel.sleepTimerSecondsRemaining.collectAsStateWithLifecycle()
    val playlistRepeatMode by viewModel.playlistRepeatMode.collectAsStateWithLifecycle()
    val mediaInfo by viewModel.mediaInfo.collectAsStateWithLifecycle()
    val customShaders by viewModel.customShaders.collectAsStateWithLifecycle()
    val liveSettings by viewModel.settings.collectAsStateWithLifecycle()

    LaunchedEffect(activeUri, mediaTitle) {
        viewModel.refreshMediaInfo(
            fallbackTitle = mediaTitle,
            fallbackUri = activeUri?.toString() ?: "",
        )
    }
    // 抽屉内部导航的唯一状态源：随抽屉实例创建，关闭后重新打开回到初始页。
    val sheetNav = rememberSheetNavState(tab)
    DisposableEffect(sheetNav) {
        playerState.sheetNav = sheetNav
        onDispose {
            if (playerState.sheetNav === sheetNav) playerState.sheetNav = null
        }
    }
    // 返回统一在宿主处理：先逐级退子页，根页再关闭抽屉。
    BackHandler(enabled = visible) {
        if (!sheetNav.goBack()) playerState.activeSheetTab = null
    }
    LaunchedEffect(snapshot.shaderState) {
        viewModel.refreshCustomShaders()
    }

    val fps = telemetry.fps
    val droppedFrames = telemetry.droppedFrames
    val currentMediaInfo = mediaInfo ?: MediaInfo(
        title = mediaTitle,
        pathOrUri = activeUri?.toString() ?: "",
        durationMs = snapshot.durationMs,
        hwdecCurrent = snapshot.hwdecCurrent,
    )
    val sheetUiState = PlayerSheetUiState(
        playlist = playlist,
        currentPlaylistIndex = currentPlaylistIndex,
        playlistRepeatMode = playlistRepeatMode,
        chapters = snapshot.chapters,
        currentChapterIndex = snapshot.currentChapter,
        currentTimePosMs = timePosMs,
        tracks = snapshot.tracks,
        secondarySubId = snapshot.secondarySubId,
        statsOverlayEnabled = playerState.statsOverlayEnabled,
        backgroundAudio = liveSettings.backgroundAudio,
        currentResizeMode = snapshot.resizeMode,
        currentSpeed = snapshot.speed,
        subDelaySec = snapshot.subtitleDelaySec,
        audioDelaySec = snapshot.audioDelaySec,
        subScale = snapshot.subtitleScale,
        subPos = snapshot.subtitlePositionPercent,
        hwdec = snapshot.hwdecCurrent,
        hwdecMode = snapshot.hwdecMode,
        fps = fps,
        droppedFrames = droppedFrames,
        videoBitrate = telemetryState.liveVideoBitrate,
        audioBitrate = telemetryState.liveAudioBitrate,
        bitrateHistory = telemetryState.bitrateHistory.toList(),
        videoWidth = snapshot.videoDimensions.width,
        videoHeight = snapshot.videoDimensions.height,
        powerMetrics = powerMetrics,
        mediaInfo = currentMediaInfo,
        shaderState = snapshot.shaderState,
        customShaders = customShaders,
        deband = snapshot.deband,
        brightness = snapshot.brightness,
        contrast = snapshot.contrast,
        saturation = snapshot.saturation,
        softwareVolume = snapshot.softwareVolume,
        nightMode = snapshot.nightMode,
        stereoDownmix = snapshot.stereoDownmix,
        eqGainsDb = snapshot.equalizerGains,
        isHdr = snapshot.isHdr,
        hdrFormat = snapshot.hdrFormat,
        hdrOutputMode = snapshot.hdrOutputMode,
        toneMappingAlgorithm = snapshot.toneMappingAlgorithm,
        videoScale = snapshot.videoScale,
        interpolation = snapshot.interpolation,
        audioPassthrough = snapshot.audioPassthrough,
        sleepTimerSecondsRemaining = sleepTimerSec,
        subAssOverride = snapshot.subAssOverride
    )

    val sheetCallbacks = remember(
        viewModel,
        playerState,
        onImportCustomShader,
        onAddExternalSubtitle,
        onOpenFullSettings,
        onEnterPip,
    ) {
        createPlayerSheetCallbacks(
            viewModel = viewModel,
            playerState = playerState,
            onImportCustomShader = onImportCustomShader,
            onAddExternalSubtitle = onAddExternalSubtitle,
            onOpenFullSettings = onOpenFullSettings,
            onEnterPip = onEnterPip,
        )
    }

    val isPlaylistScene = tab == PlayerSheetRoute.PLAYLIST || tab == PlayerSheetRoute.CHAPTERS
    if (isPlaylistScene) {
        PlayerPlaylistSheet(
            state = sheetUiState,
            callbacks = sheetCallbacks,
            nav = sheetNav,
            isLandscape = isLandscape,
            visible = visible,
            onBindVisibleHeightProvider = playerState::bindSheetVisibleHeight,
            onUnbindVisibleHeightProvider = playerState::unbindSheetVisibleHeight,
            onDismiss = {
                playerState.activeSheetTab = null
            },
            onHidden = onHidden,
        )
    } else {
        PlayerSettingsSheet(
            nav = sheetNav,
            visible = visible,
            isLandscape = isLandscape,
            state = sheetUiState,
            callbacks = sheetCallbacks,
            onBindVisibleHeightProvider = playerState::bindSheetVisibleHeight,
            onUnbindVisibleHeightProvider = playerState::unbindSheetVisibleHeight,
            onDismiss = {
                playerState.activeSheetTab = null
            },
            onHidden = onHidden,
        )
    }
}

/**
 * Pure factory creating [PlayerSheetCallbacks] without Compose runtime dependency,
 * routing all sheet mutations through [PlayerViewModel].
 */
internal fun createPlayerSheetCallbacks(
    viewModel: PlayerViewModel,
    playerState: PlayerState,
    onImportCustomShader: () -> Unit = {},
    onAddExternalSubtitle: () -> Unit = {},
    onOpenFullSettings: () -> Unit = {},
    onEnterPip: () -> Unit = {},
): PlayerSheetCallbacks = PlayerSheetCallbacks(
    onSelectPlaylistItem = { idx ->
        viewModel.playAtIndex(idx)
        playerState.activeSheetTab = null
    },
    onSelectChapter = { idx ->
        viewModel.seekToChapter(idx)
        playerState.activeSheetTab = null
    },
    onSelectShader = { shader -> viewModel.setShader(shader) },
    onClearShader = { viewModel.clearShader() },
    onSelectCustomShaderFile = { file -> viewModel.setCustomShaderFile(file) },
    onImportCustomShader = onImportCustomShader,
    onSetDeband = { enabled -> viewModel.setDeband(enabled) },
    onSetBrightness = { value -> viewModel.setBrightness(value) },
    onSetContrast = { value -> viewModel.setContrast(value) },
    onSetSaturation = { value -> viewModel.setSaturation(value) },
    onResetColors = { viewModel.resetColors() },
    onSetSoftwareVolume = { gain -> viewModel.setSoftwareVolume(gain) },
    onSetNightMode = { enabled -> viewModel.setNightMode(enabled) },
    onSetEqualizerGains = { gains -> viewModel.setEqualizerGains(gains) },
    onSetStereoDownmix = { enabled -> viewModel.setStereoDownmix(enabled) },
    onSelectTrack = { type, id -> viewModel.selectTrack(type, id) },
    onSelectResizeMode = { mode -> viewModel.setResizeMode(mode) },
    onSelectSpeed = { sp -> viewModel.setSpeed(sp) },
    onSetSubDelay = { delay -> viewModel.setSubtitleDelay(delay) },
    onSetAudioDelay = { delay -> viewModel.setAudioDelay(delay) },
    onSetSubScale = { scale -> viewModel.setSubtitleScale(scale) },
    onSetSubPos = { pos -> viewModel.setSubtitlePosition(pos) },
    onToggleHwdec = { viewModel.toggleHwdec() },
    onSetHwdec = { mode -> viewModel.setHwdec(mode) },
    onToggleStatsOverlay = { playerState.statsOverlayEnabled = it },
    onToggleBackgroundAudio = { enabled -> viewModel.setBackgroundAudio(enabled) },
    onAddExternalSubtitle = onAddExternalSubtitle,
    onSetHdrOutputMode = { mode -> viewModel.setHdrOutputMode(mode) },
    onSetToneMappingAlgorithm = { algo -> viewModel.setToneMappingAlgorithm(algo) },
    onSetVideoScale = { scale -> viewModel.setVideoScale(scale) },
    onToggleInterpolation = { enabled -> viewModel.setInterpolation(enabled) },
    onToggleAudioPassthrough = { enabled -> viewModel.setAudioPassthrough(enabled) },
    onCycleAbLoop = { viewModel.cycleAbLoop() },
    onClearAbLoop = { viewModel.clearAbLoop() },
    onOpenFullSettings = {
        playerState.activeSheetTab = null
        onOpenFullSettings()
    },
    // Close the sheet first: otherwise it is still open inside the floating window, which
    // makes the PiP preview a picture of the settings panel.
    onEnterPip = {
        playerState.activeSheetTab = null
        onEnterPip()
    },
    onSelectSecondarySubtitle = { id -> viewModel.selectSecondarySubtitle(id) },
    onSetSubAssOverride = { force -> viewModel.setSubAssOverride(force) },
    onFrameStep = { viewModel.frameStep() },
    onFrameBackStep = { viewModel.frameBackStep() },
    onSetSleepTimer = { minutes -> viewModel.setSleepTimer(minutes) },
    onSetPlaylistRepeatMode = { mode -> viewModel.setPlaylistRepeatMode(mode) },
)
