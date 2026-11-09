package dev.gyro.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class VerticalSwipeMode {
    BRIGHTNESS_AND_VOLUME,
    SWITCH_VIDEO
}

/**
 * Configuration for player gestures aligning with MX Player customizable gesture model:
 *
 * @property isLocked When true, locks all gestures except single tap (for HUD/unlock).
 * @property isVolumeEnabled Vertical swipe on volume area adjusts audio volume.
 * @property isBrightnessEnabled Vertical swipe on brightness area adjusts screen brightness.
 * @property isSeekEnabled Horizontal swipe scrubs/seeks through playback.
 * @property isZoomEnabled Two-finger pinch-to-zoom and pan when zoomed.
 * @property isDoubleTapSeekEnabled Double-tap on left/right screen edges seeks -/+ doubleTapSeekSeconds.
 * @property isDoubleTapPlayPauseEnabled Double-tap in the screen center toggles play/pause.
 * @property isLongPressSpeedEnabled Long-press activates fast-forward playback speed.
 * @property swapVolumeAndBrightness Swaps left and right vertical gesture regions (left for volume, right for brightness).
 * @property verticalSwipeMode Vertical swipe mode: adjust brightness/volume vs swipe up/down to switch videos.
 * @property doubleTapSeekSeconds The leap seconds added per double tap (default 10s).
 * @property longPressSpeed The playback speed multiplier during long-press (default 2.0x).
 * @property edgeExclusionPx Margin from screen edges where horizontal/vertical drag is ignored to prevent conflict with system gestures.
 * @property volumeSensitivity Multiplier for volume swipe sensitivity (default 1.0f).
 * @property brightnessSensitivity Multiplier for brightness swipe sensitivity (default 1.0f).
 * @property seekSensitivity Multiplier for horizontal seek swipe sensitivity (default 1.0f).
 * @property longPressTimeoutMs Milliseconds before a stationary press triggers fast-forward.
 * @property doubleTapTimeoutMs Maximum interval between taps to register as a double-tap.
 * @property singleTapDelayMs Milliseconds to wait before confirming a single tap (to avoid false single-tap during double-tap).
 */
@Serializable
data class PlayerGestureConfig(
    val isLocked: Boolean = false,
    val isVolumeEnabled: Boolean = true,
    val isBrightnessEnabled: Boolean = true,
    val isSeekEnabled: Boolean = true,
    val isZoomEnabled: Boolean = true,
    val isDoubleTapSeekEnabled: Boolean = true,
    val isDoubleTapPlayPauseEnabled: Boolean = true,
    val isLongPressSpeedEnabled: Boolean = true,
    val swapVolumeAndBrightness: Boolean = false,
    val verticalSwipeMode: VerticalSwipeMode = VerticalSwipeMode.SWITCH_VIDEO,
    val doubleTapSeekSeconds: Int = 10,
    val longPressSpeed: Float = 2.0f,
    val edgeExclusionPx: Float = 72f,
    val volumeSensitivity: Float = 1.0f,
    val brightnessSensitivity: Float = 1.0f,
    val seekSensitivity: Float = 1.0f,
    val longPressTimeoutMs: Long = 450L,
    val doubleTapTimeoutMs: Long = 320L,
    val singleTapDelayMs: Long = 260L
)
