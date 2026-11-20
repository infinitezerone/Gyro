package dev.gyro.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.model.HDR_OUTPUT_AUTO
import dev.gyro.core.model.HwdecMode
import dev.gyro.core.model.SCALE_SPLINE36
import dev.gyro.core.model.TONE_MAPPING_HABLE
import dev.gyro.core.model.UserSettings

@Preview(
    name = "Settings Screen - Progressive Dark",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF0F1117,
)
@Composable
fun SettingsScreenPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GyroColors.Background),
    ) {
        val mockSettings = UserSettings(
            autoResume = true,
            autoPip = true,
            backgroundAudio = false,
            defaultHwdec = HwdecMode.HARDWARE_DIRECT.persistedKey,
            subAutoLoad = true,
            gesturesEnabled = true,
        )

        SettingsContent(
            settings = mockSettings,
            onBack = {},
            onOpenFolderManagement = {},
            onNavigateToGestures = {},
            onNavigateToVideoRendering = {},
            onUpdateAutoResume = {},
            onUpdateAutoPip = {},
            onUpdateBackgroundAudio = {},
            onUpdateSubAutoLoad = {},
            onUpdateGestures = {},
        )
    }
}

@Preview(
    name = "Video Rendering Settings Preview",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF0F1117,
)
@Composable
fun VideoRenderingSettingsPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GyroColors.Background),
    ) {
        val sampleSettings = UserSettings(
            defaultHwdec = HwdecMode.HARDWARE_COPY.persistedKey,
            hdrOutputMode = HDR_OUTPUT_AUTO,
            highQualityRendering = true,
            videoScale = SCALE_SPLINE36,
            toneMappingAlgorithm = TONE_MAPPING_HABLE,
            interpolation = false,
        )

        VideoRenderingSettingsContent(
            settings = sampleSettings,
            onBack = {},
            onNavigateToMpvConfig = {},
            onUpdateHwdec = {},
            onUpdateHdrOutput = {},
            onUpdateHighQuality = {},
            onUpdateVideoScale = {},
            onUpdateToneMapping = {},
            onUpdateInterpolation = {},
        )
    }
}
