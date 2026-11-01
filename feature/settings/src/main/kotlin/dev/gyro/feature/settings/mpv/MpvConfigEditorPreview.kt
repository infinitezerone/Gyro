package dev.gyro.feature.settings.mpv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.model.MpvConfig

@Preview(
    name = "MpvConfigEditor - Default",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF0F1117
)
@Composable
fun MpvConfigEditorPreviewDefault() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GyroColors.Background)
    ) {
        val state = MpvConfigEditorUiState(
            content = MpvConfig.DEFAULT_TEMPLATE,
            originalContent = MpvConfig.DEFAULT_TEMPLATE,
            isModified = false
        )
        MpvConfigEditorContent(
            uiState = state,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onContentChange = {},
            onSave = {},
            onReset = {},
            onCopy = {},
            onHelp = {},
            onInsertPreset = {},
            onConfirmReset = {},
            onDismissResetDialog = {},
            onConfirmDiscard = {},
            onDismissDiscardDialog = {},
            onDismissHelpDialog = {}
        )
    }
}

@Preview(
    name = "MpvConfigEditor - Modified (Dirty State)",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF0F1117
)
@Composable
fun MpvConfigEditorPreviewModified() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GyroColors.Background)
    ) {
        val state = MpvConfigEditorUiState(
            content = "vo=gpu-next\nhwdec=mediacodec\ndeband=yes\n",
            originalContent = MpvConfig.DEFAULT_TEMPLATE,
            isModified = true
        )
        MpvConfigEditorContent(
            uiState = state,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onContentChange = {},
            onSave = {},
            onReset = {},
            onCopy = {},
            onHelp = {},
            onInsertPreset = {},
            onConfirmReset = {},
            onDismissResetDialog = {},
            onConfirmDiscard = {},
            onDismissDiscardDialog = {},
            onDismissHelpDialog = {}
        )
    }
}
