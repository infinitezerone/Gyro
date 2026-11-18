package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
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
import dev.gyro.core.model.HdrOutputMode
import dev.gyro.core.model.HwdecMode
import dev.gyro.core.model.VideoScale
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetTab
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SettingsItemRow
import dev.gyro.feature.player.component.sheet.common.SheetSwitch
import dev.gyro.feature.player.component.sheet.common.SheetSectionHeader
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.displayLabel

/**
 * Everything that changes the picture without changing the stream: tone mapping, scaling, the
 * shader upscaler, debanding, interpolation and colour calibration.
 *
 * These were seven rows on the hub, where each one pushed the frequent settings further down. They
 * share a property that makes them a group: they are tuned once for a device or a kind of content,
 * not switched during playback.
 */
@Composable
internal fun VideoQualityTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    onNavigate: (PlayerSheetTab) -> Unit
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
            title = stringResource(R.string.settings_deband),
            icon = Icons.Default.Gradient,
            trailingContent = {
                SheetSwitch(checked = state.deband, onCheckedChange = callbacks.onSetDeband)
            }
        )

        SettingsItemRow(
            title = stringResource(R.string.settings_motion_interpolation),
            icon = Icons.Default.Speed,
            trailingContent = {
                SheetSwitch(checked = state.interpolation, onCheckedChange = callbacks.onToggleInterpolation)
            }
        )

        SheetSectionHeader(
            stringResource(R.string.sheet_tab_video),
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
        )

        val hdrSummary = when (state.hdrOutputMode) {
            HdrOutputMode.PQ -> stringResource(R.string.hdr_force_pq)
            HdrOutputMode.HLG -> stringResource(R.string.hdr_force_hlg)
            HdrOutputMode.SDR -> stringResource(R.string.hdr_summary_sdr)
            HdrOutputMode.AUTO -> if (state.isHdr) {
                stringResource(R.string.hdr_summary_auto_fmt, state.hdrFormat.ifBlank { "HDR" })
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
            VideoScale.BILINEAR -> stringResource(R.string.scale_summary_bilinear)
            VideoScale.LANCZOS -> stringResource(R.string.scale_summary_lanczos)
            VideoScale.SPLINE36 -> stringResource(R.string.scale_summary_spline36)
        }
        SettingsItemRow(
            title = stringResource(R.string.settings_video_scale),
            icon = Icons.Default.Tune,
            currentValue = scaleSummary,
            onClick = { onNavigate(PlayerSheetTab.VIDEO_SCALE) }
        )

        val hasColorModifications =
            state.brightness != 0 || state.contrast != 0 || state.saturation != 0
        SettingsItemRow(
            title = stringResource(R.string.settings_video_color_calibration),
            icon = Icons.Default.ColorLens,
            currentValue = if (hasColorModifications) {
                stringResource(R.string.color_state_tuned)
            } else {
                stringResource(R.string.color_state_standard)
            },
            onClick = { onNavigate(PlayerSheetTab.COLOR) }
        )

        SheetSectionHeader(
            stringResource(R.string.frame_step_title),
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GyroPillButton(
                text = stringResource(R.string.frame_step_back),
                onClick = callbacks.onFrameBackStep,
                modifier = Modifier.weight(1f)
            )
            GyroPillButton(
                text = stringResource(R.string.frame_step_forward),
                onClick = callbacks.onFrameStep,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
