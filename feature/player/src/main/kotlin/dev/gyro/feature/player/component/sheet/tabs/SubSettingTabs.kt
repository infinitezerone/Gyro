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
            title = "HW+ 硬件复制 (推荐)",
            subtitle = "芯片硬解 + GPU 回写，支持 Anime4K / CAS 画面着色器",
            isSelected = isCopyMode,
            onClick = { callbacks.onSetHwdec("mediacodec-copy") }
        )
        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        SettingRadioRow(
            title = "HW 硬解直出",
            subtitle = "Surface 零拷贝直接送屏，低功耗省电，不支持复杂着色器",
            isSelected = isHwDirect,
            onClick = { callbacks.onSetHwdec("mediacodec") }
        )
        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        SettingRadioRow(
            title = "SW 纯软解",
            subtitle = "CPU 软件解码，格式全兼容",
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
            title = "原画 (无滤镜)",
            subtitle = "关闭后处理滤镜，纯净原画直出",
            isSelected = state.shaderState.activeShader == GlslShader.OFF,
            onClick = { callbacks.onClearShader() }
        )
        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        SettingRadioRow(
            title = "Anime4K 动漫超分",
            subtitle = "二次元动漫特化：边缘轮廓加深 + 自适应对比度重建",
            isSelected = state.shaderState.activeShader == GlslShader.ANIME4K,
            onClick = {
                if (isHwDirect) callbacks.onSetHwdec("mediacodec-copy")
                callbacks.onSelectShader(GlslShader.ANIME4K)
            }
        )
        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        SettingRadioRow(
            title = "AMD CAS 超清锐化",
            subtitle = "真人影视与美剧首选：自适应超分辨率，强化纹理且无白边",
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
        ResizeMode.FIT to "适应窗口 (原比例)",
        ResizeMode.CROP to "填充屏幕 (裁剪黑边)",
        ResizeMode.STRETCH to "拉伸全屏 (忽略比例)",
        ResizeMode.ASPECT_16_9 to "16:9 宽屏比例",
        ResizeMode.ASPECT_21_9 to "21:9 影院超宽比例"
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
                text = "预设色彩风格",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue
            )

            Text(
                text = "重置",
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
                "原画" to Triple(0, 0, 0),
                "鲜艳" to Triple(0, 10, 20),
                "影院" to Triple(-5, 15, 5),
                "柔和" to Triple(5, -10, -10)
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
            text = "精细调节",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = SheetAccentBlue
        )

        SubCompactSlider("亮度", state.brightness, -100f..100f) { callbacks.onSetBrightness(it.toInt()) }
        SubCompactSlider("对比度", state.contrast, -100f..100f) { callbacks.onSetContrast(it.toInt()) }
        SubCompactSlider("饱和度", state.saturation, -100f..100f) { callbacks.onSetSaturation(it.toInt()) }
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
                    title = track.title ?: "音频轨道 #${track.id}",
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
            Text(text = "软增益档位", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SheetAccentBlue)
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
            title = "关闭字幕",
            isSelected = state.subtitleTracks.none { it.isSelected },
            onClick = { callbacks.onSelectTrack("sub", 0) }
        )

        state.subtitleTracks.forEach { track ->
            HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)
            SettingRadioRow(
                title = track.title ?: "内置字幕 #${track.id}",
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
        Text(text = "字号缩放", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SheetAccentBlue)
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

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        Text(text = "垂直位置", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SheetAccentBlue)
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
            "自动识别 (${state.hdrFormat.ifBlank { "已点亮 HDR" }})"
        } else {
            "自动识别 (当前 SDR / 未触发)"
        }

        SettingRadioRow(
            title = autoLabel,
            subtitle = "根据片源动态元数据与屏幕 10-bit 能力全自动智能匹配 (推荐)",
            isSelected = state.hdrOutputMode == dev.gyro.core.model.HDR_OUTPUT_AUTO,
            onClick = { callbacks.onSetHdrOutputMode(dev.gyro.core.model.HDR_OUTPUT_AUTO) }
        )
        SettingRadioRow(
            title = "强制 BT.2100 PQ",
            subtitle = "HDR10 / Dolby Vision 标准绝对亮度曲线 (0~10000 nits)",
            isSelected = state.hdrOutputMode == dev.gyro.core.model.HDR_OUTPUT_PQ,
            onClick = { callbacks.onSetHdrOutputMode(dev.gyro.core.model.HDR_OUTPUT_PQ) }
        )
        SettingRadioRow(
            title = "强制 BT.2100 HLG",
            subtitle = "广播级混合对数伽马曲线 (广电 4K / 相机原片)",
            isSelected = state.hdrOutputMode == dev.gyro.core.model.HDR_OUTPUT_HLG,
            onClick = { callbacks.onSetHdrOutputMode(dev.gyro.core.model.HDR_OUTPUT_HLG) }
        )
        SettingRadioRow(
            title = "色调映射 (SDR 映射)",
            subtitle = "将高动态压缩到标准 SDR 亮度，暗室观影护眼省电",
            isSelected = state.hdrOutputMode == dev.gyro.core.model.HDR_OUTPUT_SDR,
            onClick = { callbacks.onSetHdrOutputMode(dev.gyro.core.model.HDR_OUTPUT_SDR) }
        )

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(6.dp))

        SettingRadioRow(
            title = "色调映射: 自动匹配 (Auto · 默认推荐)",
            subtitle = "自适应最佳色调映射，高对比度通透质感，色彩纯正",
            isSelected = state.toneMappingAlgorithm == dev.gyro.core.model.TONE_MAPPING_AUTO,
            onClick = { callbacks.onSetToneMappingAlgorithm(dev.gyro.core.model.TONE_MAPPING_AUTO) }
        )
        SettingRadioRow(
            title = "色调映射: Hable (经典游戏级曲线)",
            subtitle = "高对比度通透质感，有效抑制高光过曝细节损失",
            isSelected = state.toneMappingAlgorithm == dev.gyro.core.model.TONE_MAPPING_HABLE,
            onClick = { callbacks.onSetToneMappingAlgorithm(dev.gyro.core.model.TONE_MAPPING_HABLE) }
        )
        SettingRadioRow(
            title = "色调映射: BT.2446a (广电色彩精准)",
            subtitle = "国际电信联盟 ITU 广播推荐算法，色彩还原最写实",
            isSelected = state.toneMappingAlgorithm == dev.gyro.core.model.TONE_MAPPING_BT2446A,
            onClick = { callbacks.onSetToneMappingAlgorithm(dev.gyro.core.model.TONE_MAPPING_BT2446A) }
        )
        SettingRadioRow(
            title = "色调映射: Spline (样条曲线保细节)",
            subtitle = "中间调过渡平滑细腻，适合明亮场景与大片",
            isSelected = state.toneMappingAlgorithm == dev.gyro.core.model.TONE_MAPPING_SPLINE,
            onClick = { callbacks.onSetToneMappingAlgorithm(dev.gyro.core.model.TONE_MAPPING_SPLINE) }
        )
        SettingRadioRow(
            title = "色调映射: Reinhard (线性平滑压缩)",
            subtitle = "暗部噪点压制强，老旧低亮屏幕表现温和",
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
            title = "Spline36 (样条插值 · 推荐清晰)",
            subtitle = "三次样条插值算法，对齐系统相册清晰锐度，线条紧实扎实",
            isSelected = state.videoScale == dev.gyro.core.model.SCALE_SPLINE36 || state.videoScale == dev.gyro.core.model.SCALE_AUTO,
            onClick = { callbacks.onSetVideoScale(dev.gyro.core.model.SCALE_SPLINE36) }
        )
        SettingRadioRow(
            title = "Bilinear (双线性 · 基础采样)",
            subtitle = "原生硬件平滑采样，关闭锐化与重采样算法，线条偏软",
            isSelected = state.videoScale == dev.gyro.core.model.SCALE_BILINEAR || state.videoScale == dev.gyro.core.model.SCALE_OFF,
            onClick = { callbacks.onSetVideoScale(dev.gyro.core.model.SCALE_BILINEAR) }
        )
        SettingRadioRow(
            title = "Lanczos (电影级重采样)",
            subtitle = "Sinc 窗口滤波算法，高频细节丰富，适合高码率原盘",
            isSelected = state.videoScale == dev.gyro.core.model.SCALE_LANCZOS,
            onClick = { callbacks.onSetVideoScale(dev.gyro.core.model.SCALE_LANCZOS) }
        )
    }
}
