package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.engine.model.ResizeMode
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetAmber
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetCardColor
import dev.gyro.feature.player.component.sheet.common.SheetGreen
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.TrackItem

/**
 * 画面比例与解码加速 (AspectTab)
 */
@Composable
internal fun AspectTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 画面显示比例
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "画面显示比例",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SheetAccentBlue,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
            )

            ResizeMode.entries.forEach { mode ->
                TrackItem(
                    title = mode.label,
                    subtitle = when (mode) {
                        ResizeMode.FIT -> stringResource(R.string.aspect_fit_desc)
                        ResizeMode.CROP -> stringResource(R.string.aspect_crop_desc)
                        ResizeMode.STRETCH -> stringResource(R.string.aspect_stretch_desc)
                        ResizeMode.ASPECT_16_9 -> stringResource(R.string.aspect_16_9_desc)
                        ResizeMode.ASPECT_21_9 -> stringResource(R.string.aspect_21_9_desc)
                    },
                    isSelected = state.currentResizeMode == mode,
                    accentColor = SheetAccentBlue,
                    icon = Icons.Default.AspectRatio,
                    onClick = { callbacks.onSelectResizeMode(mode) }
                )
            }
        }

        // 2. 硬件加速解码
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "解码加速",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SheetAccentBlue,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            val isHw = state.hwdec.contains("mediacodec")
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SheetCardColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isHw) SheetGreen.copy(alpha = 0.15f) else SheetAmber.copy(alpha = 0.15f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = if (isHw) SheetGreen else SheetAmber,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.stats_hwdec_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = SheetTextColor
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isHw) stringResource(R.string.stats_hwdec_active) else stringResource(R.string.stats_hwdec_software),
                                fontSize = 12.sp,
                                color = if (isHw) SheetGreen else SheetAmber,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = callbacks.onToggleHwdec,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SheetCardAltColor,
                            contentColor = SheetTextColor
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = stringResource(if (isHw) R.string.stats_switch_to_sw else R.string.stats_switch_to_hw),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
