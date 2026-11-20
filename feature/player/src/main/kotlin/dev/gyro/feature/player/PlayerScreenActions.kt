package dev.gyro.feature.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.gyro.feature.player.component.PlayerOverlayActions
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import dev.gyro.feature.player.surface.GyroSurfaceView

/**
 * Hoisted factory for [PlayerOverlayActions], wiring every UI control interaction callback
 * to [PlayerViewModel] and [PlayerState].
 *
 * Enforces the core invariant: every user interaction callback calls [PlayerState.resetControlsTimeout]
 * to maintain visibility and prevent premature auto-hiding during active interaction.
 */
@Composable
fun rememberPlayerScreenActions(
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
    val durationMsProviderState = rememberUpdatedState(durationMsProvider)
    val timePosMsProviderState = rememberUpdatedState(timePosMsProvider)
    val surfaceViewProviderState = rememberUpdatedState(surfaceViewProvider)
    val mediaTitleProviderState = rememberUpdatedState(mediaTitleProvider)
    val onBackState = rememberUpdatedState(onBack)
    val onEnterPiPState = rememberUpdatedState(onEnterPiP)

    return remember(
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
            viewModel = viewModel,
            playerState = playerState,
            screenshotController = screenshotController,
            activity = activity,
            configuration = configuration,
            onBack = { onBackState.value() },
            durationMsProvider = { durationMsProviderState.value() },
            timePosMsProvider = { timePosMsProviderState.value() },
            surfaceViewProvider = { surfaceViewProviderState.value() },
            mediaTitleProvider = { mediaTitleProviderState.value() },
            subtitleEnabled = subtitleEnabled,
            lastSubTrackId = lastSubTrackId,
            seekStepSeconds = seekStepSeconds,
            onEnterPiP = { onEnterPiPState.value() },
        )
    }
}

/**
 * Pure factory creating [PlayerOverlayActions] without Compose runtime dependency,
 * routing all playback commands through [PlayerViewModel].
 */
fun createPlayerOverlayActions(
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
            viewModel.togglePlayPause()
        },
        onToggleLock = {
            onResetControlsTimeout()
            playerState.isLocked = !playerState.isLocked
            playerState.showControls = true
        },
        onSeekToFraction = { fraction ->
            onResetControlsTimeout()
            val target = (fraction * durationMsProvider()).toLong()
            viewModel.seekTo(target)
        },
        onEnterPiP = {
            onEnterPiP()
        },
        onOpenSheetTab = { tab: PlayerSheetRoute ->
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
            // The session is torn down when the player destination is disposed, i.e. after its exit
            // transition. Ending it here would remove the video surface while the screen is still
            // sliding out, which shows up as a black frame.
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
            viewModel.seekRelative(-seekStepSeconds * 1000L)
        },
        onForward10 = {
            onResetControlsTimeout()
            viewModel.seekRelative(seekStepSeconds * 1000L)
        },
        onToggleSubtitle = {
            onResetControlsTimeout()
            if (subtitleEnabled) {
                viewModel.selectSubtitleTrack(0)
            } else {
                viewModel.selectSubtitleTrack(lastSubTrackId.coerceAtLeast(1))
            }
        },
        onCycleAbLoop = {
            onResetControlsTimeout()
            viewModel.cycleAbLoop()
        },
        onSetAbLoopA = { positionMs ->
            onResetControlsTimeout()
            viewModel.setAbLoopA(positionMs / 1000.0)
        },
        onSetAbLoopB = { positionMs ->
            onResetControlsTimeout()
            viewModel.setAbLoopB(positionMs / 1000.0)
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
        onPausePlayback = {
            onResetControlsTimeout()
            viewModel.pause()
        },
        onResumePlayback = {
            onResetControlsTimeout()
            viewModel.play()
        },
        onFrameStep = {
            onResetControlsTimeout()
            viewModel.frameStep()
        },
        onFrameBackStep = {
            onResetControlsTimeout()
            viewModel.frameBackStep()
        },
        onPreciseSeek = { targetMs, isScrubbing ->
            onResetControlsTimeout()
            viewModel.onPreciseSeek(targetMs, isScrubbing)
        },
    )
}
