package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.EmptyTrackNotice
import dev.gyro.feature.player.component.sheet.common.FilterSwitchRow
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetChip
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.SheetTrackColor
import dev.gyro.feature.player.component.sheet.common.SyncCalibrationItem
import dev.gyro.feature.player.component.sheet.common.TrackItem

/**
 * 字幕轨道 + 同步校准 + 样式微调 (SubtitlesTab) - 高密度扁平化纯平铺布局
 */
@Composable
internal fun SubtitlesTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ==================== 1. 主字幕轨道选择 ====================
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "主字幕轨道 (${state.subtitleTracks.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SheetAccentBlue
                )

                Surface(
                    onClick = { callbacks.onAddExternalSubtitle() },
                    color = Color.Transparent
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = SheetAccentBlue,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "添加外挂",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = SheetAccentBlue
                        )
                    }
                }
            }

            TrackItem(
                title = "关闭字幕",
                subtitle = "纯净原片",
                isSelected = state.subtitleTracks.none { it.isSelected },
                accentColor = SheetAccentBlue,
                icon = Icons.Default.ClosedCaption,
                onClick = { callbacks.onSelectTrack("sub", 0) }
            )

            state.subtitleTracks.forEach { track ->
                TrackItem(
                    title = track.title ?: "内置字幕 #${track.id}",
                    subtitle = track.lang?.uppercase() ?: "ASS/SRT",
                    isSelected = track.isSelected,
                    accentColor = SheetAccentBlue,
                    icon = Icons.Default.ClosedCaption,
                    onClick = { callbacks.onSelectTrack("sub", track.id) }
                )
            }
        }

        // ==================== 2. 次字幕 (双语同屏) ====================
        if (state.subtitleTracks.size > 1) {
            HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "次字幕 (双语同屏)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SheetAccentBlue
                )

                TrackItem(
                    title = "关闭次字幕",
                    subtitle = "",
                    isSelected = state.secondarySubId <= 0,
                    accentColor = SheetAccentBlue,
                    icon = Icons.Default.ClosedCaption,
                    onClick = { callbacks.onSelectTrack("secondary-sub", 0) }
                )

                state.subtitleTracks.forEach { track ->
                    TrackItem(
                        title = track.title ?: "内置字幕 #${track.id}",
                        subtitle = track.lang?.uppercase() ?: "ASS/SRT",
                        isSelected = state.secondarySubId == track.id,
                        accentColor = SheetAccentBlue,
                        icon = Icons.Default.ClosedCaption,
                        onClick = { callbacks.onSelectTrack("secondary-sub", track.id) }
                    )
                }
            }
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // ==================== 3. 字幕同步延迟微调 ====================
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "字幕同步延迟",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue
            )

            SyncCalibrationItem(
                label = stringResource(R.string.sync_sub_delay),
                currentSec = state.subDelaySec,
                accentColor = SheetAccentBlue,
                onValueChange = callbacks.onSetSubDelay
            )
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // ==================== 4. 字幕排版与样式 ====================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "字幕样式排版",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue
            )

            Text(
                text = "字号缩放",
                style = MaterialTheme.typography.bodySmall,
                color = SheetTextColor,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(0.8 to "0.8x 紧凑", 1.0 to "1.0x 标准", 1.2 to "1.2x 放大", 1.5 to "1.5x 特大").forEach { (scale, label) ->
                    SheetChip(
                        selected = Math.abs(state.subScale - scale) < 0.05,
                        onClick = { callbacks.onSetSubScale(scale) },
                        label = label,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "垂直位置",
                style = MaterialTheme.typography.bodySmall,
                color = SheetTextColor,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    100L to stringResource(R.string.sub_pos_bottom),
                    95L to stringResource(R.string.sub_pos_raised),
                    90L to stringResource(R.string.sub_pos_middle)
                ).forEach { (pos, label) ->
                    SheetChip(
                        selected = state.subPos == pos,
                        onClick = { callbacks.onSetSubPos(pos) },
                        label = label,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * 声音与音效看板 (AudioTab) - 高密度扁平化纯平铺布局
 */
@Composable
internal fun AudioTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ==================== 1. 音量软增益 (Audio Boost) ====================
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val roundedVolume = kotlin.math.round(state.softwareVolume).toInt()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "音量软增益 (Audio Boost)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SheetAccentBlue
                )
                Text(
                    text = "$roundedVolume%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (roundedVolume > 100) SheetAccentBlue else SheetTextSecondaryColor
                )
            }

            // 快捷胶囊平铺
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(100.0 to "100%", 125.0 to "125%", 150.0 to "150%", 200.0 to "200%").forEach { (v, label) ->
                    SheetChip(
                        selected = kotlin.math.abs(state.softwareVolume - v) < 2.0,
                        onClick = { callbacks.onSetSoftwareVolume(v) },
                        label = label,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 连续滑块
            Slider(
                value = state.softwareVolume.toFloat(),
                onValueChange = { callbacks.onSetSoftwareVolume(it.toDouble()) },
                valueRange = 100f..200f,
                modifier = Modifier.fillMaxWidth().height(32.dp),
                colors = SliderDefaults.colors(
                    thumbColor = SheetAccentBlue,
                    activeTrackColor = SheetAccentBlue,
                    inactiveTrackColor = SheetTrackColor
                )
            )
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // ==================== 2. 音轨选择列表 ====================
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "音频轨道 (${state.audioTracks.size})",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue
            )

            if (state.audioTracks.isEmpty()) {
                EmptyTrackNotice(stringResource(R.string.track_none_audio))
            } else {
                state.audioTracks.forEach { track ->
                    TrackItem(
                        title = track.title ?: "音频轨道 #${track.id}",
                        subtitle = track.lang?.uppercase() ?: "Standard Audio",
                        isSelected = track.isSelected,
                        accentColor = SheetAccentBlue,
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        onClick = { callbacks.onSelectTrack("audio", track.id) }
                    )
                }
            }
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // ==================== 3. 声音延迟校准 ====================
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "音频延迟同步",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue
            )

            SyncCalibrationItem(
                label = stringResource(R.string.sync_audio_delay),
                currentSec = state.audioDelaySec,
                accentColor = SheetAccentBlue,
                onValueChange = callbacks.onSetAudioDelay
            )
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // ==================== 4. 立体声混缩 ====================
        FilterSwitchRow(
            title = "立体声下混",
            description = "将 5.1 / 7.1 等多声道智能下混为双声道立体声",
            checked = state.stereoDownmix,
            onCheckedChange = callbacks.onSetStereoDownmix
        )
    }
}
