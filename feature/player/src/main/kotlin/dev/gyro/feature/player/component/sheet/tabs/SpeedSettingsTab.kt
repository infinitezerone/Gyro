package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroChip
import dev.gyro.core.designsystem.component.GyroFineTuneSliderRow
import dev.gyro.core.designsystem.component.GyroGroupDivider
import dev.gyro.core.designsystem.component.GyroSectionHeader
import dev.gyro.core.designsystem.component.GyroSettingsGroup
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import kotlin.math.abs
import kotlin.math.round

private val SpeedChipRows = listOf(
    listOf(0.5f, 0.75f, 1.0f, 1.25f),
    listOf(1.5f, 2.0f, 3.0f, 4.0f),
)

/**
 * 播放倍速微调子面板（供底栏 `1.0x` 直达或从抽屉首页进入）。
 */
@Composable
internal fun SpeedSettingsTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
) {
    val isModified = abs(state.currentSpeed - 1.0f) >= 0.01f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp),
    ) {
        GyroSettingsGroup {
            GyroSectionHeader(
                title = stringResource(R.string.speed_presets),
                actionLabel = stringResource(R.string.speed_reset),
                onActionClick = { callbacks.onSelectSpeed(1.0f) },
                actionEnabled = isModified,
                horizontalPadding = 16.dp,
                modifier = Modifier.padding(top = 8.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpeedChipRows.forEach { rowValues ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowValues.forEach { sp ->
                            GyroChip(
                                label = "${formatSpeedValue(sp)}x",
                                selected = abs(state.currentSpeed - sp) < 0.02f,
                                onClick = { callbacks.onSelectSpeed(sp) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            GyroGroupDivider()

            GyroFineTuneSliderRow(
                label = stringResource(R.string.custom_speed_slider),
                valueText = "${formatSpeedValue(state.currentSpeed)}x",
                value = state.currentSpeed,
                valueRange = 0.25f..4.0f,
                step = 0.05f,
                snapValue = 1.0f,
                snapThreshold = 0.06f,
                isModified = isModified,
                onValueChange = { raw ->
                    val stepped = (round(raw * 20f) / 20f).coerceIn(0.25f, 4.0f)
                    callbacks.onSelectSpeed(stepped)
                },
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
        }
    }
}
