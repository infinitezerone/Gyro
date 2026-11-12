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
import androidx.compose.material.icons.filled.Check
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
import dev.gyro.core.model.GlslShader
import dev.gyro.engine.model.ResizeMode
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.EmptyTrackNotice
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetChip
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.SheetTrackColor
import dev.gyro.feature.player.component.sheet.common.SyncCalibrationItem
import dev.gyro.feature.player.component.sheet.common.TrackItem

/**
 * 紧凑单选行项
 */
@Composable
internal fun SettingRadioRow(
    title: String,
    subtitle: String? = null,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) SheetAccentBlue else SheetTextColor
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = SheetTextSecondaryColor
                )
            }
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = SheetAccentBlue,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 解码模式二级选择页 (HwdecSubTab)
 */
@Composable
internal fun HwdecSubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    val isCopyMode = state.hwdec.contains("copy")
    val isHwDirect = state.hwdec.contains("mediacodec") && !isCopyMode
    val isSw = !state.hwdec.contains("mediacodec")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        SettingRadioRow(
            title = stringResource(R.string.hwdec_copy_recommended),
            subtitle = stringResource(R.string.hwdec_copy_desc),
            isSelected = isCopyMode,
            onClick = { callbacks.onSetHwdec("mediacodec-copy") }
        )
        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        SettingRadioRow(
            title = stringResource(R.string.hwdec_direct),
            subtitle = stringResource(R.string.hwdec_direct_desc),
            isSelected = isHwDirect,
            onClick = { callbacks.onSetHwdec("mediacodec") }
        )
        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        SettingRadioRow(
            title = stringResource(R.string.hwdec_sw),
            subtitle = stringResource(R.string.hwdec_sw_desc),
            isSelected = isSw,
            onClick = { callbacks.onSetHwdec("no") }
        )
    }
}

/**
 * 超分辨率渲染二级选择页 (ShadersSubTab)
 */
@Composable
internal fun ShadersSubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    val isHwDirect = state.hwdec.contains("mediacodec") && !state.hwdec.contains("copy")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        SettingRadioRow(
            title = stringResource(R.string.shader_none),
            subtitle = stringResource(R.string.shader_none_desc),
            isSelected = state.shaderState.activeShader == GlslShader.OFF,
            onClick = { callbacks.onClearShader() }
        )
        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        SettingRadioRow(
            title = stringResource(R.string.shader_anime4k_upscale),
            subtitle = stringResource(R.string.shader_anime4k_upscale_desc),
            isSelected = state.shaderState.activeShader == GlslShader.ANIME4K,
            onClick = {
                if (isHwDirect) callbacks.onSetHwdec("mediacodec-copy")
                callbacks.onSelectShader(GlslShader.ANIME4K)
            }
        )
        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        SettingRadioRow(
            title = stringResource(R.string.shader_cas_sharpen),
            subtitle = stringResource(R.string.shader_cas_sharpen_desc),
            isSelected = state.shaderState.activeShader == GlslShader.CAS,
            onClick = {
                if (isHwDirect) callbacks.onSetHwdec("mediacodec-copy")
                callbacks.onSelectShader(GlslShader.CAS)
            }
        )
    }
}

/**
 * 画面比例二级选择页 (AspectSubTab)
 */
@Composable
internal fun AspectSubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    val options = listOf(
        ResizeMode.FIT to stringResource(R.string.aspect_mode_fit_full),
        ResizeMode.CROP to stringResource(R.string.aspect_mode_crop_full),
        ResizeMode.STRETCH to stringResource(R.string.aspect_mode_stretch_full),
        ResizeMode.ASPECT_16_9 to stringResource(R.string.aspect_mode_16_9_full),
        ResizeMode.ASPECT_21_9 to stringResource(R.string.aspect_mode_21_9_full)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        options.forEachIndexed { index, (mode, label) ->
            if (index > 0) {
                HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)
            }
            SettingRadioRow(
                title = label,
                isSelected = state.currentResizeMode == mode,
                onClick = { callbacks.onSelectResizeMode(mode) }
            )
        }
    }
}

/**
 * 色彩校准二级页 (ColorSubTab)
 */
@Composable
internal fun ColorSubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.color_preset_section),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue
            )

            Text(
                text = stringResource(R.string.action_reset),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue,
                modifier = Modifier
                    .clickable { callbacks.onResetColors() }
                    .padding(4.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                stringResource(R.string.color_preset_original) to Triple(0, 0, 0),
                stringResource(R.string.color_preset_vivid) to Triple(0, 10, 20),
                stringResource(R.string.color_preset_cinema) to Triple(-5, 15, 5),
                stringResource(R.string.color_preset_soft) to Triple(5, -10, -10)
            ).forEach { (label, preset) ->
                val isSelected = state.brightness == preset.first &&
                        state.contrast == preset.second &&
                        state.saturation == preset.third
                SheetChip(
                    selected = isSelected,
                    onClick = {
                        callbacks.onSetBrightness(preset.first)
                        callbacks.onSetContrast(preset.second)
                        callbacks.onSetSaturation(preset.third)
                    },
                    label = label,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        Text(
            text = stringResource(R.string.color_fine_tune_section),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = SheetAccentBlue
        )

        SubCompactSlider(stringResource(R.string.color_label_brightness), state.brightness, -100f..100f) { callbacks.onSetBrightness(it.toInt()) }
        SubCompactSlider(stringResource(R.string.color_label_contrast), state.contrast, -100f..100f) { callbacks.onSetContrast(it.toInt()) }
        SubCompactSlider(stringResource(R.string.color_label_saturation), state.saturation, -100f..100f) { callbacks.onSetSaturation(it.toInt()) }
    }
}

@Composable
private fun SubCompactSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp, color = SheetTextColor, modifier = Modifier.width(48.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = SheetAccentBlue,
                activeTrackColor = SheetAccentBlue,
                inactiveTrackColor = SheetTrackColor
            )
        )
        Text(
            text = if (value > 0) "+$value" else "$value",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (value != 0) SheetAccentBlue else SheetTextSecondaryColor,
            modifier = Modifier.width(36.dp).padding(start = 4.dp)
        )
    }
}

/**
 * 音轨选择二级页 (AudioTracksSubTab)
 */
@Composable
internal fun AudioTracksSubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        if (state.audioTracks.isEmpty()) {
            EmptyTrackNotice(stringResource(R.string.track_none_audio))
        } else {
            state.audioTracks.forEachIndexed { index, track ->
                if (index > 0) {
                    HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)
                }
                SettingRadioRow(
                    title = track.title ?: stringResource(R.string.track_audio_index_fmt, track.id),
                    subtitle = track.lang?.uppercase() ?: "Standard Audio",
                    isSelected = track.isSelected,
                    onClick = { callbacks.onSelectTrack("audio", track.id) }
                )
            }
        }
    }
}

/**
 * 音量软增益二级页 (AudioBoostSubTab)
 */
@Composable
internal fun AudioBoostSubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    val roundedVolume = kotlin.math.round(state.softwareVolume).toInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.audio_boost_levels), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SheetAccentBlue)
            Text(text = "$roundedVolume%", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SheetAccentBlue)
        }

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

        Slider(
            value = state.softwareVolume.toFloat(),
            onValueChange = { callbacks.onSetSoftwareVolume(it.toDouble()) },
            valueRange = 100f..200f,
            modifier = Modifier.fillMaxWidth().height(36.dp),
            colors = SliderDefaults.colors(
                thumbColor = SheetAccentBlue,
                activeTrackColor = SheetAccentBlue,
                inactiveTrackColor = SheetTrackColor
            )
        )
    }
}

/**
 * 延迟同步二级微调页 (DelaySubTab)
 */
@Composable
internal fun DelaySubTabContent(
    currentSec: Double,
    label: String,
    onValueChange: (Double) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        SyncCalibrationItem(
            label = label,
            currentSec = currentSec,
            accentColor = SheetAccentBlue,
            onValueChange = onValueChange
        )
    }
}

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

        SettingRadioRow(
            title = stringResource(R.string.track_none_subtitle),
            isSelected = state.subtitleTracks.none { it.isSelected },
            onClick = { callbacks.onSelectTrack("sub", 0) }
        )

        state.subtitleTracks.forEach { track ->
            HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)
            SettingRadioRow(
                title = track.title ?: stringResource(R.string.track_builtin_subtitle_fmt, track.id),
                subtitle = track.lang?.uppercase() ?: "ASS/SRT",
                isSelected = track.isSelected,
                onClick = { callbacks.onSelectTrack("sub", track.id) }
            )
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
    }
}

/**
 * 播放倍速二级选择页 (SpeedSubTab)
 */
@Composable
internal fun SpeedSubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        speeds.forEachIndexed { index, sp ->
            if (index > 0) {
                HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)
            }
            SettingRadioRow(
                title = "${sp}x",
                isSelected = Math.abs(state.currentSpeed - sp) < 0.05f,
                onClick = { callbacks.onSelectSpeed(sp) }
            )
        }
    }
}

/**
 * HDR 与色调映射二级选择页
 */
@Composable
internal fun HdrToneMappingSubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        val autoLabel = if (state.isHdr) {
            stringResource(
                R.string.hdr_auto_detected_fmt,
                state.hdrFormat.ifBlank { stringResource(R.string.hdr_active_lit) }
            )
        } else {
            stringResource(R.string.hdr_auto_sdr_notice)
        }

        SettingRadioRow(
            title = autoLabel,
            subtitle = stringResource(R.string.hdr_auto_desc),
            isSelected = state.hdrOutputMode == dev.gyro.core.model.HDR_OUTPUT_AUTO,
            onClick = { callbacks.onSetHdrOutputMode(dev.gyro.core.model.HDR_OUTPUT_AUTO) }
        )
        SettingRadioRow(
            title = stringResource(R.string.hdr_force_pq),
            subtitle = stringResource(R.string.hdr_force_pq_desc),
            isSelected = state.hdrOutputMode == dev.gyro.core.model.HDR_OUTPUT_PQ,
            onClick = { callbacks.onSetHdrOutputMode(dev.gyro.core.model.HDR_OUTPUT_PQ) }
        )
        SettingRadioRow(
            title = stringResource(R.string.hdr_force_hlg),
            subtitle = stringResource(R.string.hdr_force_hlg_desc),
            isSelected = state.hdrOutputMode == dev.gyro.core.model.HDR_OUTPUT_HLG,
            onClick = { callbacks.onSetHdrOutputMode(dev.gyro.core.model.HDR_OUTPUT_HLG) }
        )
        SettingRadioRow(
            title = stringResource(R.string.hdr_tone_map_sdr),
            subtitle = stringResource(R.string.hdr_tone_map_sdr_desc),
            isSelected = state.hdrOutputMode == dev.gyro.core.model.HDR_OUTPUT_SDR,
            onClick = { callbacks.onSetHdrOutputMode(dev.gyro.core.model.HDR_OUTPUT_SDR) }
        )

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(6.dp))

        SettingRadioRow(
            title = stringResource(R.string.tone_map_auto),
            subtitle = stringResource(R.string.tone_map_auto_desc),
            isSelected = state.toneMappingAlgorithm == dev.gyro.core.model.TONE_MAPPING_AUTO,
            onClick = { callbacks.onSetToneMappingAlgorithm(dev.gyro.core.model.TONE_MAPPING_AUTO) }
        )
        SettingRadioRow(
            title = stringResource(R.string.tone_map_hable),
            subtitle = stringResource(R.string.tone_map_hable_desc),
            isSelected = state.toneMappingAlgorithm == dev.gyro.core.model.TONE_MAPPING_HABLE,
            onClick = { callbacks.onSetToneMappingAlgorithm(dev.gyro.core.model.TONE_MAPPING_HABLE) }
        )
        SettingRadioRow(
            title = stringResource(R.string.tone_map_bt2446a),
            subtitle = stringResource(R.string.tone_map_bt2446a_desc),
            isSelected = state.toneMappingAlgorithm == dev.gyro.core.model.TONE_MAPPING_BT2446A,
            onClick = { callbacks.onSetToneMappingAlgorithm(dev.gyro.core.model.TONE_MAPPING_BT2446A) }
        )
        SettingRadioRow(
            title = stringResource(R.string.tone_map_spline),
            subtitle = stringResource(R.string.tone_map_spline_desc),
            isSelected = state.toneMappingAlgorithm == dev.gyro.core.model.TONE_MAPPING_SPLINE,
            onClick = { callbacks.onSetToneMappingAlgorithm(dev.gyro.core.model.TONE_MAPPING_SPLINE) }
        )
        SettingRadioRow(
            title = stringResource(R.string.tone_map_reinhard),
            subtitle = stringResource(R.string.tone_map_reinhard_desc),
            isSelected = state.toneMappingAlgorithm == dev.gyro.core.model.TONE_MAPPING_REINHARD,
            onClick = { callbacks.onSetToneMappingAlgorithm(dev.gyro.core.model.TONE_MAPPING_REINHARD) }
        )
    }
}

/**
 * 画面缩放与采样滤镜二级选择页
 */
@Composable
internal fun VideoScaleSubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        SettingRadioRow(
            title = stringResource(R.string.video_scale_spline36),
            subtitle = stringResource(R.string.video_scale_spline36_desc),
            isSelected = state.videoScale == dev.gyro.core.model.SCALE_SPLINE36 || state.videoScale == dev.gyro.core.model.SCALE_AUTO,
            onClick = { callbacks.onSetVideoScale(dev.gyro.core.model.SCALE_SPLINE36) }
        )
        SettingRadioRow(
            title = stringResource(R.string.video_scale_bilinear),
            subtitle = stringResource(R.string.video_scale_bilinear_desc),
            isSelected = state.videoScale == dev.gyro.core.model.SCALE_BILINEAR || state.videoScale == dev.gyro.core.model.SCALE_OFF,
            onClick = { callbacks.onSetVideoScale(dev.gyro.core.model.SCALE_BILINEAR) }
        )
        SettingRadioRow(
            title = stringResource(R.string.video_scale_lanczos),
            subtitle = stringResource(R.string.video_scale_lanczos_desc),
            isSelected = state.videoScale == dev.gyro.core.model.SCALE_LANCZOS,
            onClick = { callbacks.onSetVideoScale(dev.gyro.core.model.SCALE_LANCZOS) }
        )
    }
}
