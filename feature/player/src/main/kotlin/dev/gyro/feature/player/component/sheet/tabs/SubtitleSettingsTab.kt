package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroChip
import dev.gyro.core.designsystem.component.GyroExpandableSection
import dev.gyro.core.designsystem.component.GyroGroupDivider
import dev.gyro.core.designsystem.component.GyroRadioRow
import dev.gyro.core.designsystem.component.GyroSectionHeader
import dev.gyro.core.designsystem.component.GyroSettingsGroup
import dev.gyro.core.designsystem.component.GyroSwitchRow
import dev.gyro.core.designsystem.icon.GyroIcons
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.engine.model.TrackType
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.DelayCalibrationBlock
import dev.gyro.feature.player.component.sheet.common.formatDelayValue
import kotlin.math.abs

/**
 * 「字幕」领域二级页：
 * - 首屏直控：添加外挂字幕 + 主字幕轨道选择；
 * - 条件折叠：次字幕（仅存在其他可用字幕轨时出现）；
 * - 渐进折叠：样式与位置（实时反映字号与位置，无假色板状态）、字幕延迟。
 */
@Composable
internal fun SubtitleSettingsTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
) {
    // 次字幕是“主字幕之外的额外一层”：主字幕关闭时没有可管理的次字幕概念，
    // 候选也只列主字幕之外的轨道，避免把唯一的一条字幕轨伪装成次字幕。
    val primaryTrack = state.subtitleTracks.firstOrNull { it.isSelected }
    val secondaryCandidates = primaryTrack
        ?.let { primary -> state.subtitleTracks.filter { it.id != primary.id } }
        .orEmpty()
    var secondaryExpanded by rememberSaveable { mutableStateOf(false) }
    var styleExpanded by rememberSaveable { mutableStateOf(false) }
    var delayExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrimarySubtitlesSection(state = state, callbacks = callbacks)

        if (secondaryCandidates.isNotEmpty()) {
            SecondarySubtitleCard(
                secondarySubId = state.effectiveSecondarySubId,
                candidates = secondaryCandidates,
                onSelectSecondary = callbacks.onSelectSecondarySubtitle,
                expanded = secondaryExpanded,
                onToggle = { secondaryExpanded = !secondaryExpanded },
            )
        }

        SubtitleStyleCard(
            state = state,
            callbacks = callbacks,
            expanded = styleExpanded,
            onToggle = { styleExpanded = !styleExpanded },
        )

        SubtitleDelayCard(
            subDelaySec = state.subDelaySec,
            onSetSubDelay = callbacks.onSetSubDelay,
            expanded = delayExpanded,
            onToggle = { delayExpanded = !delayExpanded },
        )
    }
}

@Composable
private fun PrimarySubtitlesSection(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        GyroSectionHeader(title = stringResource(R.string.settings_subtitle_track))
        GyroSettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = callbacks.onAddExternalSubtitle)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = GyroIcons.Add,
                    contentDescription = null,
                    tint = GyroColors.PrimaryLight,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.track_add_external_sub),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = GyroColors.PrimaryLight,
                )
            }

            GyroGroupDivider()
            GyroRadioRow(
                title = stringResource(R.string.track_none_subtitle),
                selected = state.subtitleTracks.none { it.isSelected },
                onClick = { callbacks.onSelectTrack(TrackType.SUBTITLE, 0) },
            )

            state.subtitleTracks.forEach { track ->
                GyroGroupDivider()
                GyroRadioRow(
                    title = track.title ?: stringResource(R.string.track_builtin_subtitle_fmt, track.id),
                    subtitle = track.lang?.uppercase(),
                    selected = track.isSelected,
                    onClick = { callbacks.onSelectTrack(TrackType.SUBTITLE, track.id) },
                )
            }
        }
    }
}

@Composable
private fun SecondarySubtitleCard(
    secondarySubId: Int,
    candidates: List<dev.gyro.engine.model.TrackInfo>,
    onSelectSecondary: (Int) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val activeTrack = candidates.firstOrNull { it.id == secondarySubId }
    val summary = activeTrack?.title
        ?: activeTrack?.let { stringResource(R.string.track_builtin_subtitle_fmt, it.id) }
        ?: stringResource(R.string.track_secondary_none)

    GyroSettingsGroup {
        GyroExpandableSection(
            title = stringResource(R.string.track_secondary_subtitle),
            icon = GyroIcons.Subtitles,
            summary = summary,
            summaryHighlighted = secondarySubId > 0,
            expanded = expanded,
            onToggle = onToggle,
        ) {
            GyroRadioRow(
                title = stringResource(R.string.track_secondary_none),
                selected = secondarySubId <= 0,
                onClick = { onSelectSecondary(0) },
            )
            candidates.forEach { track ->
                GyroGroupDivider()
                GyroRadioRow(
                    title = track.title ?: stringResource(R.string.track_builtin_subtitle_fmt, track.id),
                    subtitle = track.lang?.uppercase(),
                    selected = secondarySubId == track.id,
                    onClick = { onSelectSecondary(track.id) },
                )
            }
        }
    }
}

@Composable
private fun SubtitleStyleCard(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val posLabel = when (state.subPos) {
        95L -> stringResource(R.string.sub_pos_raised)
        90L -> stringResource(R.string.sub_pos_middle)
        else -> stringResource(R.string.sub_pos_bottom)
    }
    val summary = "${String.format(java.util.Locale.US, "%.1fx", state.subScale)} · $posLabel"
    val highlighted = abs(state.subScale - 1.0) >= 0.05 ||
        state.subPos != 100L ||
        state.subAssOverride

    GyroSettingsGroup {
        GyroExpandableSection(
            title = stringResource(R.string.sheet_section_sub_style),
            icon = GyroIcons.Tune,
            summary = summary,
            summaryHighlighted = highlighted,
            expanded = expanded,
            onToggle = onToggle,
        ) {
            SubtitlePreviewCard(
                fontScale = state.subScale,
                subPos = state.subPos,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
            )

            GyroSectionHeader(
                title = stringResource(R.string.sub_scale_section),
                horizontalPadding = 16.dp,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    0.8 to stringResource(R.string.sub_scale_compact),
                    1.0 to stringResource(R.string.sub_scale_standard),
                    1.2 to stringResource(R.string.sub_scale_large),
                    1.5 to stringResource(R.string.sub_scale_xlarge),
                ).forEach { (scale, label) ->
                    GyroChip(
                        label = label,
                        selected = abs(state.subScale - scale) < 0.05,
                        onClick = { callbacks.onSetSubScale(scale) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            GyroSectionHeader(
                title = stringResource(R.string.sub_pos_title),
                horizontalPadding = 16.dp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    100L to stringResource(R.string.sub_pos_bottom),
                    95L to stringResource(R.string.sub_pos_raised),
                    90L to stringResource(R.string.sub_pos_middle),
                ).forEach { (pos, label) ->
                    GyroChip(
                        label = label,
                        selected = state.subPos == pos,
                        onClick = { callbacks.onSetSubPos(pos) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            GyroGroupDivider()
            GyroSwitchRow(
                title = stringResource(R.string.sub_ass_override_title),
                subtitle = stringResource(R.string.sub_ass_override_desc),
                checked = state.subAssOverride,
                onCheckedChange = callbacks.onSetSubAssOverride,
            )
        }
    }
}

@Composable
private fun SubtitleDelayCard(
    subDelaySec: Double,
    onSetSubDelay: (Double) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val isModified = abs(subDelaySec) >= 0.05
    val summary = if (isModified) {
        formatDelayValue(subDelaySec)
    } else {
        stringResource(R.string.delay_synced_label)
    }

    GyroSettingsGroup {
        GyroExpandableSection(
            title = stringResource(R.string.sync_sub_delay),
            icon = GyroIcons.Sync,
            summary = summary,
            summaryHighlighted = isModified,
            expanded = expanded,
            onToggle = onToggle,
        ) {
            DelayCalibrationBlock(
                label = stringResource(R.string.sync_sub_delay),
                currentSec = subDelaySec,
                onValueChange = onSetSubDelay,
            )
        }
    }
}

@Composable
private fun SubtitlePreviewCard(
    fontScale: Double,
    subPos: Long,
    modifier: Modifier = Modifier,
) {
    val bottomPaddingDp = ((100L - subPos).coerceIn(0L, 20L) * 2.5f + 8f).dp
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Black.copy(alpha = 0.72f),
        modifier = modifier
            .fillMaxWidth()
            .height(92.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = bottomPaddingDp),
            ) {
                Text(
                    text = stringResource(R.string.sub_preview_sample_cn),
                    color = Color.White,
                    fontSize = (12.5 * fontScale).coerceIn(10.0, 20.0).sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.sub_preview_sample_en),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = (9.5 * fontScale).coerceIn(8.0, 16.0).sp,
                )
            }
        }
    }
}
