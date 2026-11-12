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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.model.GlslShader
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetTab
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SettingsItemRow
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.SheetTrackColor
import dev.gyro.feature.player.component.sheet.common.displayLabel
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
        CategoryHeader(stringResource(R.string.hub_group_video))

        val hwLabel = when {
            state.hwdec.contains("copy") -> stringResource(R.string.hwdec_copy)
            state.hwdec.contains("mediacodec") -> stringResource(R.string.hwdec_direct)
            else -> stringResource(R.string.hwdec_sw)
        }
        SettingsItemRow(
            title = stringResource(R.string.stats_decoder),
            icon = Icons.Default.Memory,
            currentValue = hwLabel,
            onClick = { onNavigate(PlayerSheetTab.HWDEC) }
        )

        val shaderLabel = when (state.shaderState.activeShader) {
            GlslShader.ANIME4K -> stringResource(R.string.shader_anime4k_short)
            GlslShader.CAS -> stringResource(R.string.shader_cas_short)
            GlslShader.CUSTOM -> state.shaderState.displayName ?: stringResource(R.string.shader_custom)
            else -> stringResource(R.string.shader_none_short)
        }
        SettingsItemRow(
            title = stringResource(R.string.settings_upscale_title),
            icon = Icons.Default.AutoAwesome,
            currentValue = shaderLabel,
            onClick = { onNavigate(PlayerSheetTab.SHADERS) }
        )

        SettingsItemRow(
            title = stringResource(R.string.aspect_title),
            icon = Icons.Default.FitScreen,
            currentValue = state.currentResizeMode.displayLabel(),
            onClick = { onNavigate(PlayerSheetTab.ASPECT) }
        )

        SettingsItemRow(
            title = stringResource(R.string.settings_deband),
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
            title = stringResource(R.string.settings_video_color_calibration),
            icon = Icons.Default.ColorLens,
            currentValue = if (hasColorModifications) stringResource(R.string.color_state_tuned) else stringResource(R.string.color_state_standard),
            onClick = { onNavigate(PlayerSheetTab.COLOR) }
        )

        val hdrSummary = when (state.hdrOutputMode) {
            dev.gyro.core.model.HDR_OUTPUT_PQ -> stringResource(R.string.hdr_force_pq)
            dev.gyro.core.model.HDR_OUTPUT_HLG -> stringResource(R.string.hdr_force_hlg)
            dev.gyro.core.model.HDR_OUTPUT_SDR -> stringResource(R.string.hdr_summary_sdr)
            else -> if (state.isHdr) {
                stringResource(
                    R.string.hdr_summary_auto_fmt,
                    state.hdrFormat.ifBlank { "HDR" }
                )
            } else {
                stringResource(R.string.hdr_summary_auto_sdr)
            }
        }
        SettingsItemRow(
            title = stringResource(R.string.settings_hdr_tone_mapping),
            icon = Icons.Default.AutoAwesome,
            currentValue = hdrSummary,
            onClick = { onNavigate(PlayerSheetTab.HDR_TONE_MAPPING) }
        )

        val scaleSummary = when (state.videoScale) {
            dev.gyro.core.model.SCALE_BILINEAR, dev.gyro.core.model.SCALE_OFF -> stringResource(R.string.scale_summary_bilinear)
            dev.gyro.core.model.SCALE_LANCZOS -> stringResource(R.string.scale_summary_lanczos)
            else -> stringResource(R.string.scale_summary_spline36)
        }
        SettingsItemRow(
            title = stringResource(R.string.settings_video_scale),
            icon = Icons.Default.Tune,
            currentValue = scaleSummary,
            onClick = { onNavigate(PlayerSheetTab.VIDEO_SCALE) }
        )

        SettingsItemRow(
            title = stringResource(R.string.settings_motion_interpolation),
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
        CategoryHeader(stringResource(R.string.hub_group_audio))

        val currentAudioName = state.audioTracks.firstOrNull { it.isSelected }?.title ?: stringResource(R.string.track_no_internal_audio)
        SettingsItemRow(
            title = stringResource(R.string.settings_audio_track),
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            currentValue = currentAudioName,
            onClick = { onNavigate(PlayerSheetTab.AUDIO_TRACKS) }
        )

        val roundedVolume = kotlin.math.round(state.softwareVolume).toInt()
        SettingsItemRow(
            title = stringResource(R.string.hub_audio_boost),
            icon = Icons.Default.GraphicEq,
            currentValue = "$roundedVolume%",
            onClick = { onNavigate(PlayerSheetTab.AUDIO_BOOST) }
        )

        val audioDelayText = if (Math.abs(state.audioDelaySec) > 0.01) {
            "${String.format(Locale.US, "%.1f", state.audioDelaySec)}s"
        } else stringResource(R.string.delay_synced_label)
        SettingsItemRow(
            title = stringResource(R.string.hub_audio_delay_sync),
            icon = Icons.Default.Schedule,
            currentValue = audioDelayText,
            onClick = { onNavigate(PlayerSheetTab.AUDIO_DELAY) }
        )

        SettingsItemRow(
            title = stringResource(R.string.hub_stereo_downmix),
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
            title = stringResource(R.string.hub_audio_passthrough),
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
        CategoryHeader(stringResource(R.string.hub_group_subtitles))

        val currentSubName = state.subtitleTracks.firstOrNull { it.isSelected }?.title ?: stringResource(R.string.common_off)
        SettingsItemRow(
            title = stringResource(R.string.settings_subtitle_track),
            icon = Icons.Default.ClosedCaption,
            currentValue = currentSubName,
            onClick = { onNavigate(PlayerSheetTab.SUBTITLE_TRACKS) }
        )

        val subDelayText = if (Math.abs(state.subDelaySec) > 0.01) {
            "${String.format(Locale.US, "%.1f", state.subDelaySec)}s"
        } else stringResource(R.string.delay_synced_label)
        SettingsItemRow(
            title = stringResource(R.string.hub_sub_delay_sync),
            icon = Icons.Default.Schedule,
            currentValue = subDelayText,
            onClick = { onNavigate(PlayerSheetTab.SUBTITLE_DELAY) }
        )

        SettingsItemRow(
            title = stringResource(R.string.hub_sub_style),
            icon = Icons.Default.TextFields,
            currentValue = "${state.subScale}x",
            onClick = { onNavigate(PlayerSheetTab.SUBTITLE_STYLE) }
        )

        SectionDivider()

        // ==================== 4. 高级与诊断分组 ====================
        CategoryHeader(stringResource(R.string.hub_group_advanced_diag))

        SettingsItemRow(
            title = stringResource(R.string.settings_playback_speed),
            icon = Icons.Default.Speed,
            currentValue = "${state.currentSpeed}x",
            onClick = { onNavigate(PlayerSheetTab.SPEED) }
        )

        SettingsItemRow(
            title = stringResource(R.string.hub_stats_overlay),
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
            title = stringResource(R.string.hub_media_details),
            icon = Icons.Default.Assessment,
            currentValue = if (mediaSummary.isNotBlank()) mediaSummary else stringResource(R.string.action_view_details),
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
