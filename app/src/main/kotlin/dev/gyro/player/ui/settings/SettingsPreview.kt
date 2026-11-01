package dev.gyro.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import dev.gyro.core.model.UserSettings

@Preview(
    name = "Settings Screen - Light & Dark Clean",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF0F1117
)
@Composable
fun SettingsScreenPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1117))
    ) {
        // Mock static view
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
            onUpdateAutoResume = {},
            onUpdateBgPlay = {},
            onUpdateSeekStep = {},
            onUpdateHwdec = {},
            onUpdateSubAutoLoad = {},
            onUpdateGestures = {}
        )
    }
}
