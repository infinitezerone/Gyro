package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.engine.model.TrackType
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SettingsItemRow
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetChip
import dev.gyro.feature.player.component.sheet.common.SheetSwitch
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor

/**
 * 字幕轨道选择二级页 (SubtitleTracksSubTab)
 */
@Composable
internal fun SubtitleTracksSubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        Surface(
            onClick = { callbacks.onAddExternalSubtitle() },
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = SheetAccentBlue,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.track_add_external_sub),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = SheetAccentBlue
                )
            }
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        Text(
            text = stringResource(R.string.settings_subtitle_track),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = SheetAccentBlue,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
        )

        SettingRadioRow(
            title = stringResource(R.string.track_none_subtitle),
            isSelected = state.subtitleTracks.none { it.isSelected },
            onClick = { callbacks.onSelectTrack(TrackType.SUBTITLE, 0) }
        )

        state.subtitleTracks.forEach { track ->
            HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)
            SettingRadioRow(
                title = track.title ?: stringResource(R.string.track_builtin_subtitle_fmt, track.id),
                subtitle = track.lang?.uppercase() ?: "ASS/SRT",
                isSelected = track.isSelected,
                onClick = { callbacks.onSelectTrack(TrackType.SUBTITLE, track.id) }
            )
        }

        val secondaryCandidates = state.subtitleTracks.filter { !it.isSelected }
        if (secondaryCandidates.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.track_secondary_subtitle),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            )

            SettingRadioRow(
                title = stringResource(R.string.track_secondary_none),
                isSelected = state.secondarySubId <= 0,
                onClick = { callbacks.onSelectSecondarySubtitle(0) }
            )

            secondaryCandidates.forEach { track ->
                HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)
                SettingRadioRow(
                    title = track.title ?: stringResource(R.string.track_builtin_subtitle_fmt, track.id),
                    subtitle = track.lang?.uppercase() ?: "ASS/SRT",
                    isSelected = state.secondarySubId == track.id,
                    onClick = { callbacks.onSelectSecondarySubtitle(track.id) }
                )
            }
        }
    }
}

/**
 * 字幕排版与位置二级页 (SubtitleStyleSubTab)
 */
@Composable
internal fun SubtitleStyleSubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    var previewPreset by remember { mutableIntStateOf(0) } // 0: White, 1: Yellow, 2: Card

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 顶部沉浸式字幕预览卡片
        SubtitlePreviewCard(
            fontScale = state.subScale,
            presetStyle = previewPreset
        )

        // 样式预设切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                0 to stringResource(R.string.sub_preset_white),
                1 to stringResource(R.string.sub_preset_yellow),
                2 to stringResource(R.string.sub_preset_card)
            ).forEach { (presetIdx, label) ->
                SheetChip(
                    selected = previewPreset == presetIdx,
                    onClick = { previewPreset = presetIdx },
                    label = label,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // 2. 字号缩放
        Text(text = stringResource(R.string.sub_scale_section), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SheetAccentBlue)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                0.8 to stringResource(R.string.sub_scale_compact),
                1.0 to stringResource(R.string.sub_scale_standard),
                1.2 to stringResource(R.string.sub_scale_large),
                1.5 to stringResource(R.string.sub_scale_xlarge)
            ).forEach { (scale, label) ->
                SheetChip(
                    selected = Math.abs(state.subScale - scale) < 0.05,
                    onClick = { callbacks.onSetSubScale(scale) },
                    label = label,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // 3. 字幕位置
        Text(text = stringResource(R.string.sub_pos_title), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SheetAccentBlue)
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

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // 4. ASS 样式覆盖开关 (mpv sub-ass-override)
        SettingsItemRow(
            title = stringResource(R.string.sub_ass_override_title),
            icon = Icons.Default.ClosedCaption,
            trailingContent = {
                SheetSwitch(
                    checked = state.subAssOverride,
                    onCheckedChange = callbacks.onSetSubAssOverride
                )
            }
        )
        Text(
            text = stringResource(R.string.sub_ass_override_desc),
            fontSize = 11.sp,
            color = SheetTextSecondaryColor,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
        )
    }
}

@Composable
private fun SubtitlePreviewCard(
    fontScale: Double,
    presetStyle: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.85f),
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(8.dp)
            ) {
                val textColor = when (presetStyle) {
                    1 -> Color(0xFFFFEB3B)
                    else -> Color.White
                }
                val bgModifier = if (presetStyle == 2) {
                    Modifier
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                } else Modifier

                Text(
                    text = stringResource(R.string.sub_preview_sample_cn),
                    color = textColor,
                    fontSize = (13 * fontScale).coerceIn(10.0, 22.0).sp,
                    fontWeight = FontWeight.Medium,
                    modifier = bgModifier
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.sub_preview_sample_en),
                    color = textColor.copy(alpha = 0.85f),
                    fontSize = (10 * fontScale).coerceIn(8.0, 18.0).sp,
                    modifier = bgModifier
                )
            }
        }
    }
}
