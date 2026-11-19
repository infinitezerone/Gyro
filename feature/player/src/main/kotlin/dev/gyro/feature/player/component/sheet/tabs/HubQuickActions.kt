package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.gyro.core.designsystem.R
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SettingsItemRow
import dev.gyro.feature.player.component.sheet.common.SheetSwitch

/**
 * 播放页最上方的快捷动作行（画中画、后台播放）。
 */
@Composable
internal fun HubQuickActions(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsItemRow(
            title = stringResource(R.string.cd_pip),
            icon = Icons.Default.PictureInPicture,
            onClick = callbacks.onEnterPip
        )
        SettingsItemRow(
            title = stringResource(R.string.settings_bg_audio),
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            trailingContent = {
                SheetSwitch(
                    checked = state.backgroundAudio,
                    onCheckedChange = callbacks.onToggleBackgroundAudio
                )
            }
        )
    }
}
