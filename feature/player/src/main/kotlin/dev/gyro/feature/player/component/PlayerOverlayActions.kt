package dev.gyro.feature.player.component

import dev.gyro.feature.player.component.sheet.PlayerSheetRoute

/**
 * The single contract between [PlayerScreen] and the controls overlay: every user intent the
 * chrome can fire, as one object. Owner-side wiring (auto-hide timer resets, engine calls) stays
 * in the lambdas; the overlay only forwards them, so its parameter list carries display state
 * instead of a lambda dump.
 */
data class PlayerOverlayActions(
    val onTogglePlayPause: () -> Unit,
    val onToggleLock: () -> Unit,
    val onSeekToFraction: (Float) -> Unit,
    val onEnterPiP: () -> Unit,
    val onOpenSheetTab: (PlayerSheetRoute) -> Unit,
    val onToggleOrientation: () -> Unit,
    val onBack: () -> Unit,
    val onPlayPrevious: () -> Unit = {},
    val onPlayNext: () -> Unit = {},
    val onSelectPlaylistItem: (Int) -> Unit = {},
    val onRewind10: () -> Unit = {},
    val onForward10: () -> Unit = {},
    val onToggleSubtitle: () -> Unit = {},
    val onCycleAbLoop: () -> Unit = {},
    val onSetAbLoopA: (Long) -> Unit = {},
    val onSetAbLoopB: (Long) -> Unit = {},
    val onClearAbLoop: () -> Unit = {},
    val onScreenshot: () -> Unit = {},
    val onPausePlayback: () -> Unit = {},
    val onResumePlayback: () -> Unit = {},
    val onFrameStep: () -> Unit = {},
    val onFrameBackStep: () -> Unit = {},
    val onPreciseSeek: (Long, Boolean) -> Unit = { _, _ -> }
)
