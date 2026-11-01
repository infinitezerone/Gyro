package dev.gyro.feature.settings.gestures

import dev.gyro.core.model.PlayerGestureConfig

/**
 * UI state for the Gesture Settings screen.
 *
 * @property config Current player gesture configuration.
 * @property availableSeekSteps Supported double-tap leap seconds (5s, 10s, 15s, 30s).
 * @property availableSpeedMultipliers Supported long-press playback speed multipliers (1.5x, 2.0x, 3.0x).
 */
data class GestureSettingsUiState(
    val config: PlayerGestureConfig = PlayerGestureConfig(),
    val availableSeekSteps: List<Int> = listOf(5, 10, 15, 30),
    val availableSpeedMultipliers: List<Float> = listOf(1.5f, 2.0f, 3.0f)
)
