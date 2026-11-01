package dev.gyro.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.model.UserSettings

@Preview(
    name = "Settings Screen - Clean Dark",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF0F1117
)
@Composable
fun SettingsScreenPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GyroColors.Background)
    ) {
        val mockSettings = UserSettings(
            autoResume = true,
            backgroundPlayMode = "pip",
            seekStepSeconds = 10,
            defaultHwdec = "mediacodec",
            subAutoLoad = true,
            gesturesEnabled = true
        )

        SettingsContent(
            settings = mockSettings,
            onBack = {},
            onOpenFolderManagement = {},
            onNavigateToMpvConfig = {},
            onNavigateToGestures = {},
            onUpdateAutoResume = {},
            onUpdateBgPlay = {},
            onUpdateSeekStep = {},
            onUpdateHwdec = {},
            onUpdateSubAutoLoad = {},
            onUpdateGestures = {}
        )
    }
}
