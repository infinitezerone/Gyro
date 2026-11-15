package dev.gyro.feature.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.ui.GyroSurfaceView
import dev.gyro.feature.player.component.PlayerOverlayActions
import dev.gyro.feature.player.component.sheet.PlayerSheetTab

/**
 * Hoisted factory for [PlayerOverlayActions], wiring every UI control interaction callback
 * to the underlying engine, viewModel, and playerState according to the domain contract.
 *
 * Enforces the core invariant: every user interaction callback calls [PlayerState.resetControlsTimeout]
 * to maintain visibility and prevent premature auto-hiding during active interaction.
 */
@Composable
fun rememberPlayerScreenActions(
    engine: PlayerEngine,
    viewModel: PlayerViewModel,
    playerState: PlayerState,
    screenshotController: PlayerScreenshotController,
    activity: Activity?,
    configuration: Configuration,
    onBack: () -> Unit,
    durationMsProvider: () -> Long,
    timePosMsProvider: () -> Long,
    surfaceViewProvider: () -> GyroSurfaceView?,
    mediaTitleProvider: () -> String,
    subtitleEnabled: Boolean,
    lastSubTrackId: Int,
    seekStepSeconds: Int = 10,
    onEnterPiP: () -> Unit = {},
): PlayerOverlayActions {
    return remember(
        engine,
        viewModel,
        playerState,
        screenshotController,
        activity,
        configuration,
        subtitleEnabled,
        lastSubTrackId,
        seekStepSeconds,
    ) {
        createPlayerOverlayActions(
            engine = engine,
            viewModel = viewModel,
            playerState = playerState,
            screenshotController = screenshotController,
            activity = activity,
            configuration = configuration,
            onBack = onBack,
            durationMsProvider = durationMsProvider,
            timePosMsProvider = timePosMsProvider,
            surfaceViewProvider = surfaceViewProvider,
            mediaTitleProvider = mediaTitleProvider,
            subtitleEnabled = subtitleEnabled,
            lastSubTrackId = lastSubTrackId,
            seekStepSeconds = seekStepSeconds,
            onEnterPiP = onEnterPiP,
        )
    }
}

/**
 * Pure factory creating [PlayerOverlayActions] without Compose runtime dependency,
 * enabling direct JVM unit testing of all interaction callbacks and timeout reset invariants.
 */
fun createPlayerOverlayActions(
    engine: PlayerEngine,
    viewModel: PlayerViewModel,
    playerState: PlayerState,
    screenshotController: PlayerScreenshotController,
    activity: Activity?,
    configuration: Configuration,
    onBack: () -> Unit,
    durationMsProvider: () -> Long,
    timePosMsProvider: () -> Long,
    surfaceViewProvider: () -> GyroSurfaceView?,
    mediaTitleProvider: () -> String,
    subtitleEnabled: Boolean,
    lastSubTrackId: Int,
    seekStepSeconds: Int = 10,
    onEnterPiP: () -> Unit = {},
    onResetControlsTimeout: () -> Unit = { playerState.resetControlsTimeout() },
): PlayerOverlayActions {
    return PlayerOverlayActions(
        onTogglePlayPause = {
            onResetControlsTimeout()
            engine.togglePlayPause()
        },
        onToggleLock = {
            onResetControlsTimeout()
            playerState.isLocked = !playerState.isLocked
            playerState.showControls = !playerState.isLocked
        },
        onSeekToFraction = { fraction ->
            onResetControlsTimeout()
            val target = (fraction * durationMsProvider()).toLong()
            engine.seekTo(target)
        },
        onEnterPiP = {
            onResetControlsTimeout()
            onEnterPiP()
        },
        onOpenSheetTab = { tab: PlayerSheetTab ->
            onResetControlsTimeout()
            playerState.hideControls()
            playerState.activeSheetTab = tab
        },
        onToggleOrientation = {
            onResetControlsTimeout()
            activity?.let { act ->
                val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                act.requestedOrientation = if (isLandscape) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        },
        onBack = {
            onResetControlsTimeout()
            viewModel.onUserExit()
            onBack()
        },
        onPlayPrevious = {
            onResetControlsTimeout()
            viewModel.playPrevious()
        },
        onPlayNext = {
            onResetControlsTimeout()
            viewModel.playNext()
        },
        onSelectPlaylistItem = { idx ->
            onResetControlsTimeout()
            viewModel.playAtIndex(idx)
        },
        onRewind10 = {
            onResetControlsTimeout()
            engine.seekRelative(-seekStepSeconds * 1000L)
        },
        onForward10 = {
            onResetControlsTimeout()
            engine.seekRelative(seekStepSeconds * 1000L)
        },
        onToggleSubtitle = {
            onResetControlsTimeout()
            if (subtitleEnabled) {
                engine.trackManager.selectTrack("sub", 0)
            } else {
                engine.trackManager.selectTrack("sub", lastSubTrackId.coerceAtLeast(1))
            }
        },
        onCycleAbLoop = {
            onResetControlsTimeout()
            viewModel.cycleAbLoop()
        },
        onClearAbLoop = {
            onResetControlsTimeout()
            viewModel.clearAbLoop()
        },
        onScreenshot = {
            onResetControlsTimeout()
            screenshotController.capture(
                surfaceView = surfaceViewProvider(),
                mediaTitle = mediaTitleProvider(),
                positionMs = timePosMsProvider(),
            )
        },
        onCycleHwdec = {
            onResetControlsTimeout()
            engine.cycleHwdec()
        },
    )
}
