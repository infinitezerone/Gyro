package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroPillButton
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.HwdecMode
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SettingsItemRow
import dev.gyro.feature.player.component.sheet.common.SheetSectionHeader
import dev.gyro.feature.player.component.sheet.common.SheetSwitch
import dev.gyro.feature.player.component.sheet.common.shortCodecName
import java.util.Locale
import dev.gyro.core.designsystem.icon.GyroIcons

/**
 * 播放页的「更多播放设置」二级页。
 *
 * 这些选项按当前视频临时调整，不属于全局偏好：解码方式用于排障，着色器/去色带/色彩是画面微调，
 * 音效与字幕样式与当前片源绑定。全局默认值仍在应用设置里。
 */
@Composable
internal fun MorePlaybackTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    onNavigate: (PlayerSheetRoute) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp)
    ) {
        SheetSectionHeader(
            stringResource(R.string.hub_group_video),
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp)
        )

        val hwLabel = when (state.hwdecMode) {
            HwdecMode.HARDWARE_COPY -> stringResource(R.string.hwdec_copy)
            HwdecMode.HARDWARE_DIRECT -> stringResource(R.string.hwdec_direct)
            HwdecMode.SOFTWARE -> stringResource(R.string.hwdec_sw)
        }
        SettingsItemRow(
            title = stringResource(R.string.stats_decoder),
            icon = GyroIcons.Memory,
            currentValue = hwLabel,
            onClick = { onNavigate(PlayerSheetRoute.HWDEC) }
        )

        val shaderLabel = when (state.shaderState.activeShader) {
            GlslShader.ANIME4K -> stringResource(R.string.shader_anime4k_short)
            GlslShader.CAS -> stringResource(R.string.shader_cas_short)
            GlslShader.CUSTOM -> state.shaderState.displayName ?: stringResource(R.string.shader_custom)
            else -> stringResource(R.string.shader_none_short)
        }
        SettingsItemRow(
            title = stringResource(R.string.settings_upscale_title),
            icon = GyroIcons.AutoAwesome,
            currentValue = shaderLabel,
            onClick = { onNavigate(PlayerSheetRoute.SHADERS) }
        )

        SettingsItemRow(
            title = stringResource(R.string.settings_deband),
            icon = GyroIcons.Gradient,
            trailingContent = {
                SheetSwitch(checked = state.deband, onCheckedChange = callbacks.onSetDeband)
            }
        )

        val hasColorModifications =
            state.brightness != 0 || state.contrast != 0 || state.saturation != 0
        SettingsItemRow(
            title = stringResource(R.string.settings_video_color_calibration),
            icon = GyroIcons.Palette,
            currentValue = if (hasColorModifications) {
                stringResource(R.string.color_state_tuned)
            } else {
                stringResource(R.string.color_state_standard)
            },
            onClick = { onNavigate(PlayerSheetRoute.COLOR) }
        )

        SheetSectionHeader(
            stringResource(R.string.hub_group_audio),
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
        )
        SettingsItemRow(
            title = stringResource(R.string.audio_loudness_leveling),
            icon = GyroIcons.GraphicEq,
            trailingContent = {
                SheetSwitch(checked = state.nightMode, onCheckedChange = callbacks.onSetNightMode)
            }
        )
        SettingsItemRow(
            title = stringResource(R.string.sheet_tab_audio_effects),
            icon = GyroIcons.Tune,
            onClick = { onNavigate(PlayerSheetRoute.AUDIO_EFFECTS) }
        )

        SheetSectionHeader(
            stringResource(R.string.hub_group_subtitles),
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
        )
        SettingsItemRow(
            title = stringResource(R.string.settings_subtitle_style),
            icon = GyroIcons.ClosedCaption,
            onClick = { onNavigate(PlayerSheetRoute.SUBTITLE_STYLE) }
        )

        SheetSectionHeader(
            stringResource(R.string.sheet_tab_sync),
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
        )
        SettingsItemRow(
            title = stringResource(R.string.sync_audio_delay),
            icon = GyroIcons.Timer,
            currentValue = delayLabel(state.audioDelaySec),
            onClick = { onNavigate(PlayerSheetRoute.AUDIO_DELAY) }
        )
        SettingsItemRow(
            title = stringResource(R.string.sync_sub_delay),
            icon = GyroIcons.Timer,
            currentValue = delayLabel(state.subDelaySec),
            onClick = { onNavigate(PlayerSheetRoute.SUBTITLE_DELAY) }
        )

        SheetSectionHeader(
            stringResource(R.string.hub_group_info_advanced),
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
        )
        val codecText = state.mediaInfo?.videoCodec?.let { shortCodecName(it) }?.ifBlank { null }
        val resolutionText = if (state.videoWidth > 0) "${state.videoHeight}P" else null
        val mediaSummary = listOfNotNull(resolutionText, codecText).joinToString(" ")
        SettingsItemRow(
            title = stringResource(R.string.page_stats_info),
            icon = GyroIcons.Analytics,
            currentValue = if (mediaSummary.isNotBlank()) mediaSummary else stringResource(R.string.action_view_details),
            onClick = { onNavigate(PlayerSheetRoute.STATS_INFO) }
        )
        SettingsItemRow(
            title = stringResource(R.string.hub_stats_overlay),
            icon = GyroIcons.Monitor,
            trailingContent = {
                SheetSwitch(
                    checked = state.statsOverlayEnabled,
                    onCheckedChange = callbacks.onToggleStatsOverlay
                )
            }
        )

        SheetSectionHeader(
            stringResource(R.string.settings_title),
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
        )
        SettingsItemRow(
            title = stringResource(R.string.app_settings),
            icon = GyroIcons.Settings,
            currentValue = stringResource(R.string.app_settings_exit_hint),
            onClick = callbacks.onOpenFullSettings
        )
    }
}

@Composable
private fun delayLabel(seconds: Double): String =
    if (Math.abs(seconds) < 0.01) {
        stringResource(R.string.delay_synced_label)
    } else {
        String.format(Locale.US, "%+.1fs", seconds)
    }
