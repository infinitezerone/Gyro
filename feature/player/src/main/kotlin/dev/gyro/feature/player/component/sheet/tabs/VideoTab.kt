package dev.gyro.feature.player.component.sheet.tabs

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.gyro.feature.player.component.sheet.common.FilterSwitchRow
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetChip
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.SheetTrackColor

/**
 * 色彩风格预设档位
 */
private enum class VisualColorPreset(
    @StringRes val labelRes: Int,
    val brightness: Int,
    val contrast: Int,
    val saturation: Int
) {
    STANDARD(R.string.color_preset_original, 0, 0, 0),
    VIVID(R.string.color_preset_vivid, 0, 10, 20),
    CINEMA(R.string.color_preset_cinema, -5, 15, 5),
    SOFT(R.string.color_preset_soft, 5, -10, -10);

    companion object {
        fun match(b: Int, c: Int, s: Int): VisualColorPreset? {
            return entries.firstOrNull { it.brightness == b && it.contrast == c && it.saturation == s }
        }
    }
}

/**
 * 画面调节看板 (VideoTab) - 高密度扁平化纯平铺布局
 */
@Composable
internal fun VideoTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    val hasColorModifications =
        state.brightness != 0 || state.contrast != 0 || state.saturation != 0
    val currentPreset = VisualColorPreset.match(state.brightness, state.contrast, state.saturation)

    val isCopyMode = state.hwdec.contains("copy")
    val isHwDirect = state.hwdec.contains("mediacodec") && !isCopyMode
    val isSw = !state.hwdec.contains("mediacodec")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ==================== 1. 解码器模式 ====================
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.stats_decoder),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SheetChip(
                    selected = isCopyMode,
                    onClick = { callbacks.onSetHwdec("mediacodec-copy") },
                    label = stringResource(R.string.hwdec_copy),
                    modifier = Modifier.weight(1.1f)
                )
                SheetChip(
                    selected = isHwDirect,
                    onClick = { callbacks.onSetHwdec("mediacodec") },
                    label = stringResource(R.string.hwdec_direct),
                    modifier = Modifier.weight(1f)
                )
                SheetChip(
                    selected = isSw,
                    onClick = { callbacks.onSetHwdec("no") },
                    label = stringResource(R.string.hwdec_sw),
                    modifier = Modifier.weight(0.9f)
                )
            }

            Text(
                text = when {
                    isCopyMode -> stringResource(R.string.video_mode_desc_copy)
                    isHwDirect -> stringResource(R.string.video_mode_desc_hw)
                    else -> stringResource(R.string.video_mode_desc_sw)
                },
                fontSize = 11.sp,
                color = SheetTextSecondaryColor,
                lineHeight = 15.sp
            )
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // ==================== 2. 画质超分辨率与渲染增强 ====================
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.settings_upscale_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SheetChip(
                    selected = state.shaderState.activeShader == GlslShader.OFF,
                    onClick = { callbacks.onClearShader() },
                    label = stringResource(R.string.shader_none),
                    modifier = Modifier.weight(1f)
                )
                SheetChip(
                    selected = state.shaderState.activeShader == GlslShader.ANIME4K,
                    onClick = {
                        if (isHwDirect) callbacks.onSetHwdec("mediacodec-copy")
                        callbacks.onSelectShader(GlslShader.ANIME4K)
                    },
                    label = stringResource(R.string.shader_anime4k_short),
                    modifier = Modifier.weight(1.1f)
                )
                SheetChip(
                    selected = state.shaderState.activeShader == GlslShader.CAS,
                    onClick = {
                        if (isHwDirect) callbacks.onSetHwdec("mediacodec-copy")
                        callbacks.onSelectShader(GlslShader.CAS)
                    },
                    label = stringResource(R.string.shader_cas_short),
                    modifier = Modifier.weight(1.1f)
                )
            }

            Text(
                text = when (state.shaderState.activeShader) {
                    GlslShader.ANIME4K -> stringResource(R.string.video_shader_desc_anime4k)
                    GlslShader.CAS -> stringResource(R.string.video_shader_desc_cas)
                    else -> stringResource(R.string.video_shader_desc_off)
                },
                fontSize = 11.sp,
                color = SheetTextSecondaryColor,
                lineHeight = 15.sp
            )
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // ==================== 3. 画面比例 (Aspect Ratio) ====================
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.video_aspect_section),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    ResizeMode.FIT to stringResource(R.string.resize_fit),
                    ResizeMode.CROP to stringResource(R.string.resize_crop),
                    ResizeMode.STRETCH to stringResource(R.string.resize_stretch),
                    ResizeMode.ASPECT_16_9 to "16:9",
                    ResizeMode.ASPECT_21_9 to "21:9"
                ).forEach { (mode, label) ->
                    SheetChip(
                        selected = state.currentResizeMode == mode,
                        onClick = { callbacks.onSelectResizeMode(mode) },
                        label = label,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // ==================== 4. Deband 去色带 ====================
        FilterSwitchRow(
            title = stringResource(R.string.video_deband_title),
            description = stringResource(R.string.video_deband_desc),
            checked = state.deband,
            onCheckedChange = callbacks.onSetDeband
        )

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // ==================== 5. 画面色彩风格与参数微调 ====================
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_color_calibration),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SheetAccentBlue
                )

                if (hasColorModifications) {
                    Text(
                        text = stringResource(R.string.effects_color_reset),
                        fontSize = 12.sp,
                        color = SheetAccentBlue,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { callbacks.onResetColors() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            // 预设胶囊平铺
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VisualColorPreset.entries.forEach { preset ->
                    SheetChip(
                        selected = currentPreset == preset,
                        onClick = {
                            callbacks.onSetBrightness(preset.brightness)
                            callbacks.onSetContrast(preset.contrast)
                            callbacks.onSetSaturation(preset.saturation)
                        },
                        label = stringResource(preset.labelRes),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // 紧凑滑块三连排
            CompactSliderItem(
                label = stringResource(R.string.color_label_brightness),
                value = state.brightness,
                valueRange = -100f..100f,
                onValueChange = { callbacks.onSetBrightness(it.toInt()) },
                formatValue = { if (it > 0) "+$it" else "$it" }
            )
            CompactSliderItem(
                label = stringResource(R.string.color_label_contrast),
                value = state.contrast,
                valueRange = -100f..100f,
                onValueChange = { callbacks.onSetContrast(it.toInt()) },
                formatValue = { if (it > 0) "+$it" else "$it" }
            )
            CompactSliderItem(
                label = stringResource(R.string.color_label_saturation),
                value = state.saturation,
                valueRange = -100f..100f,
                onValueChange = { callbacks.onSetSaturation(it.toInt()) },
                formatValue = { if (it > 0) "+$it" else "$it" }
            )
        }
    }
}

@Composable
private fun CompactSliderItem(
    label: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    formatValue: (Int) -> String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = SheetTextColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(48.dp)
        )

        Slider(
            value = value.toFloat(),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = SheetAccentBlue,
                activeTrackColor = SheetAccentBlue,
                inactiveTrackColor = SheetTrackColor
            )
        )

        Text(
            text = formatValue(value),
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (value != 0) SheetAccentBlue else SheetTextSecondaryColor
            ),
            modifier = Modifier
                .width(36.dp)
                .padding(start = 4.dp)
        )
    }
}
