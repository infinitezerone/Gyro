package dev.gyro.feature.settings.gestures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.model.PlayerGestureConfig

@Preview(
    name = "Gesture Settings - Default",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF0F1117
)
@Composable
fun GestureSettingsPreviewDefault() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GyroColors.Background)
    ) {
        val defaultConfig = PlayerGestureConfig()
        GestureSettingsContent(
            config = defaultConfig,
            availableSeekSteps = listOf(5, 10, 15, 30),
            availableSpeedMultipliers = listOf(1.5f, 2.0f, 3.0f),
            onBack = {},
            onSetVolumeEnabled = {},
            onSetBrightnessEnabled = {},
            onSetInvertSwipeDirections = {},
            onSetSeekEnabled = {},
            onSetDoubleTapSeekEnabled = {},
            onSetDoubleTapSeekSeconds = {},
            onSetDoubleTapPlayPauseEnabled = {},
            onSetLongPressSpeedEnabled = {},
            onSetLongPressSpeedMultiplier = {},
            onSetPinchZoomEnabled = {},
            onResetToDefault = {}
        )
    }
}
