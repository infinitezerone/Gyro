package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import dev.gyro.core.designsystem.R
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.EmptyTrackNotice
import dev.gyro.feature.player.component.sheet.common.FilterSwitchRow
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetCardColor
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetGreen
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SyncCalibrationItem
import dev.gyro.feature.player.component.sheet.common.TrackItem

/**
 * 字幕轨道 + 同步校准 + 样式微调 (SubtitlesTab)。
 * 预留：次字幕（secondary-sid）分区将在后续接入。
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
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 字幕轨道选择
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sheet_tab_subtitles),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = SheetAccentBlue
                )
                Text(
                    text = stringResource(R.string.subtitle_count_format, state.subtitleTracks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = SheetTextSecondaryColor
                )
            }

            TrackItem(
                title = stringResource(R.string.track_none_subtitle),
                subtitle = "Off",
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

            Surface(
                onClick = { callbacks.onAddExternalSubtitle() },
                shape = RoundedCornerShape(12.dp),
                color = SheetCardColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(SheetAccentBlue.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = SheetAccentBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.track_add_external_sub),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = SheetAccentBlue
                    )
                }
            }
        }

        // 2. 次字幕（双语同屏）：不影响主字幕轨，mpv secondary-sid 驱动
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.secondary_sub_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = SheetAccentBlue
                )
                Text(
                    text = stringResource(
                        if (state.secondarySubId > 0) R.string.secondary_sub_on else R.string.secondary_sub_off
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = SheetTextSecondaryColor
                )
            }

            TrackItem(
                title = stringResource(R.string.secondary_sub_disable),
                subtitle = "Off",
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

        // 3. 字幕同步校准
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.subtitles_sync_group),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SheetAccentBlue,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            SyncCalibrationItem(
                label = stringResource(R.string.sync_sub_delay),
                currentSec = state.subDelaySec,
                accentColor = SheetAccentBlue,
                onValueChange = callbacks.onSetSubDelay
            )
        }

        // 3. 字幕样式微调 (尺寸与位置)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = SheetCardColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.sub_scale_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SheetTextColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0.8, 1.0, 1.2, 1.5).forEach { scale ->
                        FilterChip(
                            selected = Math.abs(state.subScale - scale) < 0.05,
                            onClick = { callbacks.onSetSubScale(scale) },
                            label = { Text("${scale}x") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.sub_pos_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SheetTextColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        100L to stringResource(R.string.sub_pos_bottom),
                        95L to stringResource(R.string.sub_pos_raised),
                        90L to stringResource(R.string.sub_pos_middle)
                    ).forEach { (pos, label) ->
                        FilterChip(
                            selected = state.subPos == pos,
                            onClick = { callbacks.onSetSubPos(pos) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 音轨选择 + 音效增强 + 声音延迟 (AudioTab)
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
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 音轨选择列表 (卡片化微磨砂)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sheet_tab_audio),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = SheetGreen
                )
                Text(
                    text = stringResource(R.string.audio_track_count_format, state.audioTracks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = SheetTextSecondaryColor
                )
            }

            if (state.audioTracks.isEmpty()) {
                EmptyTrackNotice(stringResource(R.string.track_none_audio))
            } else {
                state.audioTracks.forEach { track ->
                    TrackItem(
                        title = track.title ?: "音频轨道 #${track.id}",
                        subtitle = track.lang?.uppercase() ?: "Standard Audio",
                        isSelected = track.isSelected,
                        accentColor = SheetGreen,
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        onClick = { callbacks.onSelectTrack("audio", track.id) }
                    )
                }
            }
        }

        // 2. 音效增强 (夜间模式 / 声道混缩)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.effects_audio_group),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SheetGreen,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SheetCardColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Column {
                    FilterSwitchRow(
                        title = stringResource(R.string.effects_night_dialogue),
                        description = stringResource(R.string.effects_night_dialogue_desc),
                        checked = state.nightMode,
                        onCheckedChange = callbacks.onSetNightMode
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = SheetCardAltColor
                    )
                    FilterSwitchRow(
                        title = stringResource(R.string.effects_stereo_downmix),
                        description = stringResource(R.string.effects_stereo_downmix_desc),
                        checked = state.stereoDownmix,
                        onCheckedChange = callbacks.onSetStereoDownmix
                    )
                }
            }
        }

        // 3. 声音延迟校准卡片
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.audio_sync_group),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SheetGreen,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            SyncCalibrationItem(
                label = stringResource(R.string.sync_audio_delay),
                currentSec = state.audioDelaySec,
                accentColor = SheetGreen,
                onValueChange = callbacks.onSetAudioDelay
            )
        }
    }
}
