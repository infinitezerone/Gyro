package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.HwdecMode
import dev.gyro.engine.model.ResizeMode
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetTab
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SettingsItemRow
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetCardColor
import dev.gyro.feature.player.component.sheet.common.SheetChip
import dev.gyro.feature.player.component.sheet.common.SheetSwitch
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.displayLabel
import dev.gyro.feature.player.component.sheet.common.shortCodecName
import java.util.Locale

/**
 * 主播放设置菜单 (SettingsHubTab)
 *
 * 现代化抽屉布局结构（参考 Bilibili / YouTube / Infuse 设计哲学）：
 * 1. 顶部 2x2 快捷操作矩阵 (PiP 画中画、后台播放、定时关闭倒计时、A-B 循环)
 * 2. 高频属性分段快切 (倍速预设、画面比例预设)
 * 3. 四大核心收纳枢纽 (画面与画质、声音与音效、字幕与排版、信息与极客)
 * 4. 辅助开关与全局设置 (统计浮层开关、更多设置)
 */
@Composable
internal fun SettingsHubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    onNavigate: (PlayerSheetTab) -> Unit
) {
    var showSleepPresets by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // ==================== 0. 顶部快捷操作矩阵 ====================
        val abLoopText = when {
            state.abLoopASeconds >= 0 && state.abLoopBSeconds >= 0 -> {
                val aSec = state.abLoopASeconds.toInt()
                val bSec = state.abLoopBSeconds.toInt()
                val aStr = "%02d:%02d".format(Locale.US, aSec / 60, aSec % 60)
                val bStr = "%02d:%02d".format(Locale.US, bSec / 60, bSec % 60)
                "$aStr - $bStr"
            }
            state.abLoopASeconds >= 0 -> {
                val aSec = state.abLoopASeconds.toInt()
                val aStr = "%02d:%02d".format(Locale.US, aSec / 60, aSec % 60)
                stringResource(R.string.ab_loop_set_b_short) + " ($aStr)"
            }
            else -> stringResource(R.string.common_off)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickActionCard(
                title = stringResource(R.string.cd_pip),
                subtitle = "",
                icon = Icons.Default.PictureInPicture,
                onClick = callbacks.onEnterPip,
                modifier = Modifier.weight(1f)
            )
            QuickActionCard(
                title = stringResource(R.string.action_bg_play),
                subtitle = stringResource(R.string.common_enabled),
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                onClick = callbacks.onEnterPip,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val sleepTimerActive = state.sleepTimerSecondsRemaining > 0
            val sleepTimerSubtitle = if (sleepTimerActive) {
                val rem = state.sleepTimerSecondsRemaining
                "%02d:%02d".format(Locale.US, rem / 60, rem % 60)
            } else {
                stringResource(R.string.sleep_timer_off)
            }
            QuickActionCard(
                title = stringResource(R.string.sleep_timer_title),
                subtitle = sleepTimerSubtitle,
                icon = Icons.Default.Schedule,
                isActive = sleepTimerActive,
                onClick = { showSleepPresets = !showSleepPresets },
                modifier = Modifier.weight(1f)
            )

            val loopActive = state.abLoopASeconds >= 0
            QuickActionCard(
                title = stringResource(R.string.action_loop_mode),
                subtitle = abLoopText,
                icon = Icons.Default.Repeat,
                isActive = loopActive,
                onClick = callbacks.onCycleAbLoop,
                modifier = Modifier.weight(1f)
            )
        }

        // 定时关闭快捷选择展开行
        if (showSleepPresets) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    0 to stringResource(R.string.sleep_timer_off),
                    15 to stringResource(R.string.sleep_timer_15m),
                    30 to stringResource(R.string.sleep_timer_30m),
                    60 to stringResource(R.string.sleep_timer_60m)
                ).forEach { (minutes, label) ->
                    SheetChip(
                        selected = (minutes == 0 && state.sleepTimerSecondsRemaining == 0L) ||
                            (minutes > 0 && state.sleepTimerSecondsRemaining in (minutes * 60 - 5L)..(minutes * 60L)),
                        onClick = {
                            callbacks.onSetSleepTimer(minutes)
                            if (minutes == 0) showSleepPresets = false
                        },
                        label = label,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // A-B 循环清除按钮
        if (state.abLoopASeconds >= 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = stringResource(R.string.ab_loop_clear),
                    fontSize = 12.sp,
                    color = SheetAccentBlue,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(onClick = callbacks.onClearAbLoop)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        SectionDivider()

        // ==================== 1. 常用播放快捷分段 ====================
        CategoryHeader(stringResource(R.string.hub_group_playback))

        // 倍速
        SettingsItemRow(
            title = stringResource(R.string.settings_playback_speed),
            icon = Icons.Default.Speed,
            currentValue = "${state.currentSpeed}x",
            onClick = { onNavigate(PlayerSheetTab.SPEED) }
        )
        val speedPresets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            speedPresets.forEach { speed ->
                SheetChip(
                    selected = kotlin.math.abs(state.currentSpeed - speed) < 0.01f,
                    onClick = { callbacks.onSelectSpeed(speed) },
                    label = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 画面比例
        SettingsItemRow(
            title = stringResource(R.string.aspect_title),
            icon = Icons.Default.FitScreen,
            currentValue = state.currentResizeMode.displayLabel(),
            onClick = { onNavigate(PlayerSheetTab.ASPECT) }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ResizeMode.entries.forEach { mode ->
                SheetChip(
                    selected = state.currentResizeMode == mode,
                    onClick = { callbacks.onSelectResizeMode(mode) },
                    label = mode.displayLabel(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        SectionDivider()

        // ==================== 2. 四大核心收纳枢纽 ====================
        CategoryHeader(stringResource(R.string.settings_title))

        // 1. 画面与画质 (Video & Rendering)
        val hwLabel = when (state.hwdecMode) {
            HwdecMode.HARDWARE_COPY -> stringResource(R.string.hwdec_copy)
            HwdecMode.HARDWARE_DIRECT -> stringResource(R.string.hwdec_direct)
            HwdecMode.SOFTWARE -> stringResource(R.string.hwdec_sw)
        }
        val shaderLabel = when (state.shaderState.activeShader) {
            GlslShader.ANIME4K -> stringResource(R.string.shader_anime4k_short)
            GlslShader.CAS -> stringResource(R.string.shader_cas_short)
            GlslShader.CUSTOM -> state.shaderState.displayName ?: stringResource(R.string.shader_custom)
            else -> stringResource(R.string.shader_none_short)
        }
        SettingsItemRow(
            title = stringResource(R.string.hub_group_video_rendering),
            icon = Icons.Default.AutoAwesome,
            currentValue = "$hwLabel · $shaderLabel",
            onClick = { onNavigate(PlayerSheetTab.VIDEO_QUALITY) }
        )

        // 2. 声音与音效 (Audio & Sound Effects)
        val roundedVolume = kotlin.math.round(state.softwareVolume).toInt()
        val audioProcessingState = when {
            state.nightMode -> stringResource(R.string.audio_loudness_leveling)
            state.eqGainsDb.any { Math.abs(it) > 0.1 } -> stringResource(R.string.audio_equalizer)
            else -> stringResource(R.string.audio_equalizer_flat)
        }
        SettingsItemRow(
            title = stringResource(R.string.hub_group_audio_effects),
            icon = Icons.Default.GraphicEq,
            currentValue = "$roundedVolume% · $audioProcessingState",
            onClick = { onNavigate(PlayerSheetTab.AUDIO_EFFECTS) }
        )

        // 3. 字幕与排版 (Subtitles & Typography)
        val subtitleTrack = state.subtitleTracks.firstOrNull { it.isSelected }?.title
            ?: stringResource(R.string.common_off)
        val subTrackDisplay = if (state.secondarySubId > 0) {
            val secTrack = state.subtitleTracks.firstOrNull { it.id == state.secondarySubId }?.title
                ?: stringResource(R.string.track_secondary_subtitle)
            "$subtitleTrack + $secTrack"
        } else {
            subtitleTrack
        }
        SettingsItemRow(
            title = stringResource(R.string.hub_group_subtitle_styling),
            icon = Icons.Default.ClosedCaption,
            currentValue = subTrackDisplay,
            onClick = { onNavigate(PlayerSheetTab.SUBTITLE_TRACKS) }
        )

        // 4. 信息与极客 (Media Info & Diagnostics)
        val codecText = state.mediaInfo?.videoCodec?.let { shortCodecName(it) }?.ifBlank { null }
        val resolutionText = if (state.videoWidth > 0) "${state.videoHeight}P" else null
        val mediaSummary = listOfNotNull(resolutionText, codecText).joinToString(" ")
        SettingsItemRow(
            title = stringResource(R.string.hub_group_info_advanced),
            icon = Icons.Default.Assessment,
            currentValue = if (mediaSummary.isNotBlank()) mediaSummary else stringResource(R.string.action_view_details),
            onClick = { onNavigate(PlayerSheetTab.STATS_INFO) }
        )

        // 实时极客统计浮层开关
        SettingsItemRow(
            title = stringResource(R.string.hub_stats_overlay),
            icon = Icons.Default.Monitor,
            trailingContent = {
                SheetSwitch(
                    checked = state.statsOverlayEnabled,
                    onCheckedChange = callbacks.onToggleStatsOverlay
                )
            }
        )

        SectionDivider()

        // 系统设置
        SettingsItemRow(
            title = stringResource(R.string.settings_more),
            icon = Icons.Default.Settings,
            onClick = callbacks.onOpenFullSettings
        )
    }
}

/**
 * 快捷操作方卡
 */
@Composable
private fun QuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isActive: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) SheetAccentBlue.copy(alpha = 0.15f) else SheetCardColor,
        modifier = modifier
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) SheetAccentBlue else SheetTextColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = SheetTextColor
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = if (isActive) SheetAccentBlue else SheetTextSecondaryColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = SheetTextSecondaryColor,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun SectionDivider() {
    Spacer(modifier = Modifier.height(6.dp))
}
