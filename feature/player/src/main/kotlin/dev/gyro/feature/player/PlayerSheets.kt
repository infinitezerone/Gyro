package dev.gyro.feature.player

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import android.net.Uri
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.ResizeMode
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
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val timePosMs by engine.timePosMs.collectAsState()
    val telemetry by engine.diagnostics.collectAsState()
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
        softwareVolume = engine.state.value.softwareVolume,
        nightMode = snapshot.nightMode,
        stereoDownmix = snapshot.stereoDownmix,
        isHdr = snapshot.isHdr,
        hdrFormat = snapshot.hdrFormat,
        hdrOutputMode = snapshot.hdrOutputMode,
        toneMappingAlgorithm = snapshot.toneMappingAlgorithm,
        videoScale = snapshot.videoScale,
        interpolation = snapshot.interpolation,
        audioPassthrough = snapshot.audioPassthrough,
        abLoopASeconds = snapshot.abLoopASeconds,
        abLoopBSeconds = snapshot.abLoopBSeconds
    )

    val sheetCallbacks = PlayerSheetCallbacks(
        onSelectPlaylistItem = { idx ->
            viewModel.playAtIndex(idx)
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
        onImportCustomShader = onImportCustomShader,
        onSetDeband = { enabled -> engine.video.setDeband(enabled) },
        onSetBrightness = { value -> engine.video.setBrightness(value) },
        onSetContrast = { value -> engine.video.setContrast(value) },
        onSetSaturation = { value -> engine.video.setSaturation(value) },
        onResetColors = { engine.video.resetColors() },
        onSetSoftwareVolume = { gain -> engine.audio.setSoftwareVolume(gain) },
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
        onSetHwdec = { target -> engine.setHwdec(target) },
        onToggleStatsOverlay = { playerState.statsOverlayEnabled = it },
        onAddExternalSubtitle = onAddExternalSubtitle,
        onSetHdrOutputMode = { mode -> engine.setHdrOutputMode(mode) },
        onSetToneMappingAlgorithm = { algo -> engine.setToneMappingAlgorithm(algo) },
        onSetVideoScale = { scale -> engine.setVideoScale(scale) },
        onToggleInterpolation = { enabled -> engine.setInterpolation(enabled) },
        onToggleAudioPassthrough = { enabled -> engine.setAudioPassthrough(enabled) },
        onCycleAbLoop = { viewModel.cycleAbLoop() },
        onClearAbLoop = { viewModel.clearAbLoop() },
        onOpenFullSettings = {
            playerState.activeSheetTab = null
            onOpenFullSettings()
        }
    )

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
