package dev.gyro.feature.player

import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gyro.core.model.PowerMetrics
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.feature.player.component.sheet.PlayerPlaylistSheet
import dev.gyro.feature.player.component.sheet.PlayerSettingsSheet
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetTab
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState

/**
 * Modal tabbed settings / calibrator / playlist sheet host.
 *
 * Lives inside the screen's [androidx.compose.animation.AnimatedVisibility] for the sheet so the
 * telemetry subscriptions below only tick while the sheet is actually composed - battery/thermal
 * sampling and mpv diagnostics must not invalidate the player layout while the sheet is closed.
 */
@Composable
internal fun PlayerSheetsHost(
    tab: PlayerSheetTab,
    isLandscape: Boolean,
    engine: PlayerEngine,
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
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val timePosMs by engine.timePosMs.collectAsStateWithLifecycle()
    val telemetry by engine.diagnostics.collectAsStateWithLifecycle()
    val eqGainsDb by engine.audio.equalizerGains.collectAsStateWithLifecycle()
    val subAssOverride by engine.tuning.subAssOverride.collectAsStateWithLifecycle()
    val sleepTimerSec by viewModel.sleepTimerSecondsRemaining.collectAsStateWithLifecycle()
    val fps = telemetry.fps
    val droppedFrames = telemetry.droppedFrames
    val currentMediaInfo = remember(tab, activeUri) {
        engine.queryMediaInfo(
            fallbackTitle = mediaTitle,
            fallbackUri = activeUri?.toString() ?: ""
        )
    }
    val sheetUiState = PlayerSheetUiState(
        playlist = playlist,
        currentPlaylistIndex = currentPlaylistIndex,
        chapters = snapshot.chapters,
        currentChapterIndex = snapshot.currentChapter,
        currentTimePosMs = timePosMs,
        tracks = snapshot.tracks,
        secondarySubId = snapshot.secondarySubId,
        statsOverlayEnabled = playerState.statsOverlayEnabled,
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
        customShaders = remember(snapshot.shaderState) { engine.shaders.getCustomShaders() },
        deband = snapshot.deband,
        brightness = snapshot.brightness,
        contrast = snapshot.contrast,
        saturation = snapshot.saturation,
        softwareVolume = snapshot.softwareVolume,
        nightMode = snapshot.nightMode,
        stereoDownmix = snapshot.stereoDownmix,
        eqGainsDb = eqGainsDb,
        isHdr = snapshot.isHdr,
        hdrFormat = snapshot.hdrFormat,
        hdrOutputMode = snapshot.hdrOutputMode,
        toneMappingAlgorithm = snapshot.toneMappingAlgorithm,
        videoScale = snapshot.videoScale,
        interpolation = snapshot.interpolation,
        audioPassthrough = snapshot.audioPassthrough,
        abLoopASeconds = snapshot.abLoopASeconds,
        abLoopBSeconds = snapshot.abLoopBSeconds,
        sleepTimerSecondsRemaining = sleepTimerSec,
        subAssOverride = subAssOverride
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

    val isPlaylistScene = tab == PlayerSheetTab.PLAYLIST || tab == PlayerSheetTab.CHAPTERS
    if (isPlaylistScene) {
        PlayerPlaylistSheet(
            state = sheetUiState,
            callbacks = sheetCallbacks,
            isLandscape = isLandscape,
            animatedVisibilityScope = animatedVisibilityScope,
            onDismiss = {
                playerState.activeSheetTab = null
                // Cleared on dismiss rather than on drag end: the exit animation starts from the
                // last dragged position, so the offset has to survive until then, but the next
                // opening must not inherit it.
                playerState.onSheetDragged(0f)
                playerState.showControls()
            }
        )
    } else {
        PlayerSettingsSheet(
            initialTab = tab,
            isLandscape = isLandscape,
            animatedVisibilityScope = animatedVisibilityScope,
            state = sheetUiState,
            callbacks = sheetCallbacks,
            // The video is fitted into the space this sheet leaves, so the surface needs its
            // measured height rather than a guessed offset.
            onSheetHeightChanged = playerState::onSheetHeightMeasured,
            onSheetDragOffsetChanged = playerState::onSheetDragged,
            onDismiss = {
                playerState.activeSheetTab = null
                // Cleared on dismiss rather than on drag end: the exit animation starts from the
                // last dragged position, so the offset has to survive until then, but the next
                // opening must not inherit it.
                playerState.onSheetDragged(0f)
                playerState.showControls()
            }
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
)
