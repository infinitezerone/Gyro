package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetChip
import dev.gyro.feature.player.component.sheet.common.SheetSectionHeader
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetCardColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SheetTrackColor
import java.util.Locale

/**
 * 倍速调节 (SpeedTab)：预设矩阵 + 无级精细调速。
 */
@Composable
internal fun SpeedTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    val currentSpeed = state.currentSpeed
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Card: 当前倍速与快速原速
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SheetCardColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = String.format(Locale.US, "%.2fx", currentSpeed),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = SheetAccentBlue,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (Math.abs(currentSpeed - 1.0f) < 0.01f) "正常速度" else "变速播放",
                    fontSize = 13.sp,
                    color = SheetTextSecondaryColor
                )

                if (Math.abs(currentSpeed - 1.0f) >= 0.01f) {
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = { callbacks.onSelectSpeed(1.0f) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SheetAccentBlue.copy(alpha = 0.2f),
                            contentColor = SheetAccentBlue
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("恢复 1.0x", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Section 1: 预设倍速矩阵
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetSectionHeader("预设倍速", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))

            val row1 = listOf(0.5f, 0.75f, 1.0f, 1.25f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row1.forEach { sp ->
                    SheetChip(
                        selected = Math.abs(currentSpeed - sp) < 0.01f,
                        onClick = { callbacks.onSelectSpeed(sp) },
                        label = "${sp}x",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            val row2 = listOf(1.5f, 1.75f, 2.0f, 3.0f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row2.forEach { sp ->
                    SheetChip(
                        selected = Math.abs(currentSpeed - sp) < 0.01f,
                        onClick = { callbacks.onSelectSpeed(sp) },
                        label = "${sp}x",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Section 2: 无级精细调速
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetSectionHeader("精细调节", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SheetCardColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            callbacks.onSelectSpeed(Math.max(0.25f, (Math.round((currentSpeed - 0.05f) * 100f) / 100f)))
                        },
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SheetCardAltColor,
                            contentColor = SheetTextColor
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("-0.05x", fontSize = 12.sp)
                    }

                    Slider(
                        value = currentSpeed.coerceIn(0.25f, 3.0f),
                        onValueChange = { callbacks.onSelectSpeed(Math.round(it * 20f) / 20f) },
                        valueRange = 0.25f..3.0f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = SheetAccentBlue,
                            activeTrackColor = SheetAccentBlue,
                            inactiveTrackColor = SheetTrackColor
                        )
                    )

                    FilledTonalButton(
                        onClick = {
                            callbacks.onSelectSpeed(Math.min(3.0f, (Math.round((currentSpeed + 0.05f) * 100f) / 100f)))
                        },
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SheetCardAltColor,
                            contentColor = SheetTextColor
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("+0.05x", fontSize = 12.sp)
                    }
                }
            }
        }
        }
    }
}
