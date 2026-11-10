package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.model.GlslShader
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetTab
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SettingsItemRow
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.SheetTrackColor
import java.util.Locale

/**
 * 主播放设置菜单 (SettingsHubTab) - 高密度扁平列表
 *
 * 规范：左边图标+标题，右边当前状态/Switch，去除所有冗余说明文案，需要二级就二级。
 */
@Composable
internal fun SettingsHubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    onNavigate: (PlayerSheetTab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // ==================== 1. 画面设置分组 ====================
        CategoryHeader("画面")

        val hwLabel = when {
            state.hwdec.contains("copy") -> "HW+ 硬件复制"
            state.hwdec.contains("mediacodec") -> "HW 硬解直出"
            else -> "SW 纯软解"
        }
        SettingsItemRow(
            title = "解码模式",
            icon = Icons.Default.Memory,
            currentValue = hwLabel,
            onClick = { onNavigate(PlayerSheetTab.HWDEC) }
        )

        val shaderLabel = when (state.shaderState.activeShader) {
            GlslShader.ANIME4K -> "Anime4K 动漫"
            GlslShader.CAS -> "AMD CAS 锐化"
            GlslShader.CUSTOM -> state.shaderState.displayName ?: "自定义着色器"
            else -> "原画无滤镜"
        }
        SettingsItemRow(
            title = "超分辨率渲染",
            icon = Icons.Default.AutoAwesome,
            currentValue = shaderLabel,
            onClick = { onNavigate(PlayerSheetTab.SHADERS) }
        )

        SettingsItemRow(
            title = "画面比例",
            icon = Icons.Default.FitScreen,
            currentValue = state.currentResizeMode.label,
            onClick = { onNavigate(PlayerSheetTab.ASPECT) }
        )

        SettingsItemRow(
            title = "去色带 (Deband)",
            icon = Icons.Default.Gradient,
            trailingContent = {
                Switch(
                    checked = state.deband,
                    onCheckedChange = callbacks.onSetDeband,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SheetAccentBlue,
                        checkedTrackColor = SheetAccentBlue.copy(alpha = 0.35f),
                        uncheckedThumbColor = SheetTextSecondaryColor,
                        uncheckedTrackColor = SheetTrackColor
                    )
                )
            }
        )

        val hasColorModifications = state.brightness != 0 || state.contrast != 0 || state.saturation != 0
        SettingsItemRow(
            title = "画面色彩校准",
            icon = Icons.Default.ColorLens,
            currentValue = if (hasColorModifications) "已微调" else "标准原画",
            onClick = { onNavigate(PlayerSheetTab.COLOR) }
        )

        val hdrSummary = when (state.hdrOutputMode) {
            dev.gyro.core.model.HDR_OUTPUT_PQ -> "强制 BT.2100 PQ"
            dev.gyro.core.model.HDR_OUTPUT_HLG -> "强制 BT.2100 HLG"
            dev.gyro.core.model.HDR_OUTPUT_SDR -> "SDR 色调映射"
            else -> if (state.isHdr) "自动 (${state.hdrFormat.ifBlank { "HDR" }})" else "自动 (SDR)"
        }
        SettingsItemRow(
            title = "HDR 与色调映射",
            icon = Icons.Default.AutoAwesome,
            currentValue = hdrSummary,
            onClick = { onNavigate(PlayerSheetTab.HDR_TONE_MAPPING) }
        )

        val scaleSummary = when (state.videoScale) {
            dev.gyro.core.model.SCALE_BILINEAR, dev.gyro.core.model.SCALE_OFF -> "Bilinear 基础"
            dev.gyro.core.model.SCALE_LANCZOS -> "Lanczos 电影"
            else -> "Spline36 锐利清晰"
        }
        SettingsItemRow(
            title = "画面缩放算法",
            icon = Icons.Default.Tune,
            currentValue = scaleSummary,
            onClick = { onNavigate(PlayerSheetTab.VIDEO_SCALE) }
        )

        SettingsItemRow(
            title = "运动平滑插值 (去抖动)",
            icon = Icons.Default.Speed,
            trailingContent = {
                Switch(
                    checked = state.interpolation,
                    onCheckedChange = callbacks.onToggleInterpolation,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SheetAccentBlue,
                        checkedTrackColor = SheetAccentBlue.copy(alpha = 0.35f),
                        uncheckedThumbColor = SheetTextSecondaryColor,
                        uncheckedTrackColor = SheetTrackColor
                    )
                )
            }
        )

        SectionDivider()

        // ==================== 2. 声音设置分组 ====================
        CategoryHeader("声音")

        val currentAudioName = state.audioTracks.firstOrNull { it.isSelected }?.title ?: "无内置音频"
        SettingsItemRow(
            title = "音频轨道",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            currentValue = currentAudioName,
            onClick = { onNavigate(PlayerSheetTab.AUDIO_TRACKS) }
        )

        val roundedVolume = kotlin.math.round(state.softwareVolume).toInt()
        SettingsItemRow(
            title = "音量软增益 (Audio Boost)",
            icon = Icons.Default.GraphicEq,
            currentValue = "$roundedVolume%",
            onClick = { onNavigate(PlayerSheetTab.AUDIO_BOOST) }
        )

        val audioDelayText = if (Math.abs(state.audioDelaySec) > 0.01) {
            "${String.format(Locale.US, "%.1f", state.audioDelaySec)}s"
        } else "0.0s (已同步)"
        SettingsItemRow(
            title = "音频延迟同步",
            icon = Icons.Default.Schedule,
            currentValue = audioDelayText,
            onClick = { onNavigate(PlayerSheetTab.AUDIO_DELAY) }
        )

        SettingsItemRow(
            title = "立体声下混",
            icon = Icons.Default.SurroundSound,
            trailingContent = {
                Switch(
                    checked = state.stereoDownmix,
                    onCheckedChange = callbacks.onSetStereoDownmix,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SheetAccentBlue,
                        checkedTrackColor = SheetAccentBlue.copy(alpha = 0.35f),
                        uncheckedThumbColor = SheetTextSecondaryColor,
                        uncheckedTrackColor = SheetTrackColor
                    )
                )
            }
        )

        SettingsItemRow(
            title = "音频源码透传 (S/PDIF)",
            icon = Icons.Default.GraphicEq,
            trailingContent = {
                Switch(
                    checked = state.audioPassthrough,
                    onCheckedChange = callbacks.onToggleAudioPassthrough,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SheetAccentBlue,
                        checkedTrackColor = SheetAccentBlue.copy(alpha = 0.35f),
                        uncheckedThumbColor = SheetTextSecondaryColor,
                        uncheckedTrackColor = SheetTrackColor
                    )
                )
            }
        )

        SectionDivider()

        // ==================== 3. 字幕设置分组 ====================
        CategoryHeader("字幕")

        val currentSubName = state.subtitleTracks.firstOrNull { it.isSelected }?.title ?: "关闭"
        SettingsItemRow(
            title = "字幕轨道",
            icon = Icons.Default.ClosedCaption,
            currentValue = currentSubName,
            onClick = { onNavigate(PlayerSheetTab.SUBTITLE_TRACKS) }
        )

        val subDelayText = if (Math.abs(state.subDelaySec) > 0.01) {
            "${String.format(Locale.US, "%.1f", state.subDelaySec)}s"
        } else "0.0s (已同步)"
        SettingsItemRow(
            title = "字幕延迟同步",
            icon = Icons.Default.Schedule,
            currentValue = subDelayText,
            onClick = { onNavigate(PlayerSheetTab.SUBTITLE_DELAY) }
        )

        SettingsItemRow(
            title = "字幕字号与位置",
            icon = Icons.Default.TextFields,
            currentValue = "${state.subScale}x",
            onClick = { onNavigate(PlayerSheetTab.SUBTITLE_STYLE) }
        )

        SectionDivider()

        // ==================== 4. 高级与诊断分组 ====================
        CategoryHeader("高级与诊断")

        SettingsItemRow(
            title = "播放倍速",
            icon = Icons.Default.Speed,
            currentValue = "${state.currentSpeed}x",
            onClick = { onNavigate(PlayerSheetTab.SPEED) }
        )

        SettingsItemRow(
            title = "悬浮性能监视窗",
            icon = Icons.Default.Monitor,
            trailingContent = {
                Switch(
                    checked = state.statsOverlayEnabled,
                    onCheckedChange = callbacks.onToggleStatsOverlay,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SheetAccentBlue,
                        checkedTrackColor = SheetAccentBlue.copy(alpha = 0.35f),
                        uncheckedThumbColor = SheetTextSecondaryColor,
                        uncheckedTrackColor = SheetTrackColor
                    )
                )
            }
        )

        val codecText = state.mediaInfo?.videoCodec?.uppercase()?.ifBlank { null }
        val resolutionText = if (state.videoWidth > 0) "${state.videoHeight}P" else null
        val mediaSummary = listOfNotNull(resolutionText, codecText).joinToString(" ")
        SettingsItemRow(
            title = "媒体规格详情与图表",
            icon = Icons.Default.Assessment,
            currentValue = if (mediaSummary.isNotBlank()) mediaSummary else "查看详情",
            onClick = { onNavigate(PlayerSheetTab.STATS_INFO) }
        )
    }
}

@Composable
private fun CategoryHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = SheetAccentBlue,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = SheetCardAltColor.copy(alpha = 0.5f),
        thickness = 0.5.dp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}
