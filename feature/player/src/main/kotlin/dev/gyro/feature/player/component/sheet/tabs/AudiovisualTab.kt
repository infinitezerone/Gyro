package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.ColorSliderRow
import dev.gyro.feature.player.component.sheet.common.FilterSwitchRow
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetSectionHeader
import dev.gyro.feature.player.component.sheet.common.SheetCardColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor

/**
 * 视听画质增强 (AudiovisualTab)：去色带 + 色彩校准。
 */
@Composable
internal fun AudiovisualTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 画质滤波优化
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SheetSectionHeader("画质优化", modifier = Modifier.padding(start = 4.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SheetCardColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterSwitchRow(
                    title = stringResource(R.string.effects_deband),
                    description = "消除暗场与色彩断层",
                    checked = state.deband,
                    onCheckedChange = callbacks.onSetDeband
                )
            }
        }

        // 2. 画面色彩校准卡片
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SheetSectionHeader(stringResource(R.string.effects_color_group), modifier = Modifier.padding(start = 4.dp))

                val hasColorModifications =
                    state.brightness != 0 || state.contrast != 0 || state.saturation != 0
                FilledTonalButton(
                    onClick = callbacks.onResetColors,
                    enabled = hasColorModifications,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = SheetAccentBlue.copy(alpha = 0.2f),
                        contentColor = SheetAccentBlue,
                        disabledContainerColor = SheetCardColor,
                        disabledContentColor = SheetTextSecondaryColor
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(text = stringResource(R.string.effects_color_reset), fontSize = 12.sp)
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SheetCardColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ColorSliderRow(
                        label = stringResource(R.string.effects_color_brightness),
                        value = state.brightness,
                        onValueChange = callbacks.onSetBrightness
                    )
                    ColorSliderRow(
                        label = stringResource(R.string.effects_color_contrast),
                        value = state.contrast,
                        onValueChange = callbacks.onSetContrast
                    )
                    ColorSliderRow(
                        label = stringResource(R.string.effects_color_saturation),
                        value = state.saturation,
                        onValueChange = callbacks.onSetSaturation
                    )
                }
            }
        }
    }
}
