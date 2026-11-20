package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroChip
import dev.gyro.core.designsystem.component.GyroExpandableSection
import dev.gyro.core.designsystem.component.GyroFineTuneSliderRow
import dev.gyro.core.designsystem.component.GyroGroupDivider
import dev.gyro.core.designsystem.component.GyroRadioRow
import dev.gyro.core.designsystem.component.GyroSectionHeader
import dev.gyro.core.designsystem.component.GyroSettingsGroup
import dev.gyro.core.designsystem.component.GyroSwitchRow
import dev.gyro.core.designsystem.icon.GyroIcons
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.HwdecMode
import dev.gyro.engine.model.ResizeMode
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.displayLabel
import kotlin.math.roundToInt

/**
 * 「画面」领域二级页：
 * - 首屏直控：画面比例一排快捷芯片；
 * - 渐进折叠：画质增强（着色器 + Deband）、色彩校准（预设 + 三轴微调）、解码模式（排障用）。
 */
@Composable
internal fun VideoSettingsTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
) {
    var enhanceExpanded by rememberSaveable { mutableStateOf(false) }
    var colorExpanded by rememberSaveable { mutableStateOf(false) }
    var hwdecExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AspectRatioSection(
            currentMode = state.currentResizeMode,
            onSelectMode = callbacks.onSelectResizeMode,
        )

        PictureEnhanceCard(
            state = state,
            callbacks = callbacks,
            expanded = enhanceExpanded,
            onToggle = { enhanceExpanded = !enhanceExpanded },
        )

        ColorCalibrationCard(
            state = state,
            callbacks = callbacks,
            expanded = colorExpanded,
            onToggle = { colorExpanded = !colorExpanded },
        )

        DecoderModeCard(
            state = state,
            callbacks = callbacks,
            expanded = hwdecExpanded,
            onToggle = { hwdecExpanded = !hwdecExpanded },
        )
    }
}

@Composable
private fun AspectRatioSection(
    currentMode: ResizeMode,
    onSelectMode: (ResizeMode) -> Unit,
) {
    val options = listOf(
        ResizeMode.FIT,
        ResizeMode.CROP,
        ResizeMode.STRETCH,
        ResizeMode.ASPECT_16_9,
        ResizeMode.ASPECT_4_3,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        GyroSectionHeader(title = stringResource(R.string.aspect_title))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { mode ->
                GyroChip(
                    label = mode.displayLabel(),
                    selected = currentMode == mode,
                    onClick = { onSelectMode(mode) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PictureEnhanceCard(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val shaderLabel = when (state.shaderState.activeShader) {
        GlslShader.ANIME4K -> stringResource(R.string.shader_anime4k_short)
        GlslShader.CAS -> stringResource(R.string.shader_cas_short)
        GlslShader.CUSTOM -> state.shaderState.displayName ?: stringResource(R.string.shader_custom)
        else -> stringResource(R.string.shader_none)
    }
    val debandTag = if (state.deband) stringResource(R.string.settings_deband) else null
    val summary = listOfNotNull(shaderLabel, debandTag).joinToString(" · ")
    val highlighted = state.shaderState.activeShader != GlslShader.OFF || state.deband
    val isHwDirect = state.hwdecMode == HwdecMode.HARDWARE_DIRECT

    GyroSettingsGroup {
        GyroExpandableSection(
            title = stringResource(R.string.sheet_section_picture_enhance),
            icon = GyroIcons.AutoAwesome,
            summary = summary,
            summaryHighlighted = highlighted,
            expanded = expanded,
            onToggle = onToggle,
        ) {
            GyroRadioRow(
                title = stringResource(R.string.shader_none),
                subtitle = stringResource(R.string.shader_none_desc),
                selected = state.shaderState.activeShader == GlslShader.OFF,
                onClick = callbacks.onClearShader,
            )
            GyroGroupDivider()
            GyroRadioRow(
                title = stringResource(R.string.shader_anime4k_upscale),
                subtitle = stringResource(R.string.shader_anime4k_upscale_desc),
                selected = state.shaderState.activeShader == GlslShader.ANIME4K,
                onClick = {
                    if (isHwDirect) callbacks.onSetHwdec(HwdecMode.HARDWARE_COPY)
                    callbacks.onSelectShader(GlslShader.ANIME4K)
                },
            )
            GyroGroupDivider()
            GyroRadioRow(
                title = stringResource(R.string.shader_cas_sharpen),
                subtitle = stringResource(R.string.shader_cas_sharpen_desc),
                selected = state.shaderState.activeShader == GlslShader.CAS,
                onClick = {
                    if (isHwDirect) callbacks.onSetHwdec(HwdecMode.HARDWARE_COPY)
                    callbacks.onSelectShader(GlslShader.CAS)
                },
            )
            GyroGroupDivider()
            GyroSwitchRow(
                title = stringResource(R.string.settings_deband),
                subtitle = stringResource(R.string.video_deband_desc),
                checked = state.deband,
                onCheckedChange = callbacks.onSetDeband,
            )
        }
    }
}

@Composable
private fun ColorCalibrationCard(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val hasModifications = state.brightness != 0 || state.contrast != 0 || state.saturation != 0
    val summary = if (hasModifications) {
        stringResource(R.string.color_state_tuned)
    } else {
        stringResource(R.string.color_state_standard)
    }

    val presets = listOf(
        R.string.color_preset_original to Triple(0, 0, 0),
        R.string.color_preset_vivid to Triple(0, 10, 20),
        R.string.color_preset_cinema to Triple(-5, 15, 5),
        R.string.color_preset_soft to Triple(5, -10, -10),
    )

    GyroSettingsGroup {
        GyroExpandableSection(
            title = stringResource(R.string.settings_video_color_calibration),
            icon = GyroIcons.ColorTone,
            summary = summary,
            summaryHighlighted = hasModifications,
            expanded = expanded,
            onToggle = onToggle,
        ) {
            GyroSectionHeader(
                title = stringResource(R.string.color_preset_section),
                actionLabel = stringResource(R.string.action_reset),
                onActionClick = callbacks.onResetColors,
                actionEnabled = hasModifications,
                horizontalPadding = 16.dp,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                presets.forEach { (labelRes, values) ->
                    val selected = state.brightness == values.first &&
                        state.contrast == values.second &&
                        state.saturation == values.third
                    GyroChip(
                        label = stringResource(labelRes),
                        selected = selected,
                        onClick = {
                            callbacks.onSetBrightness(values.first)
                            callbacks.onSetContrast(values.second)
                            callbacks.onSetSaturation(values.third)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            GyroFineTuneSliderRow(
                label = stringResource(R.string.color_label_brightness),
                valueText = formatSignedInt(state.brightness),
                value = state.brightness.toFloat(),
                valueRange = -100f..100f,
                snapValue = 0f,
                snapThreshold = 2.5f,
                isModified = state.brightness != 0,
                onValueChange = { callbacks.onSetBrightness(it.roundToInt()) },
            )
            GyroFineTuneSliderRow(
                label = stringResource(R.string.color_label_contrast),
                valueText = formatSignedInt(state.contrast),
                value = state.contrast.toFloat(),
                valueRange = -100f..100f,
                snapValue = 0f,
                snapThreshold = 2.5f,
                isModified = state.contrast != 0,
                onValueChange = { callbacks.onSetContrast(it.roundToInt()) },
            )
            GyroFineTuneSliderRow(
                label = stringResource(R.string.color_label_saturation),
                valueText = formatSignedInt(state.saturation),
                value = state.saturation.toFloat(),
                valueRange = -100f..100f,
                snapValue = 0f,
                snapThreshold = 2.5f,
                isModified = state.saturation != 0,
                onValueChange = { callbacks.onSetSaturation(it.roundToInt()) },
            )
        }
    }
}

@Composable
private fun DecoderModeCard(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val hwLabel = when (state.hwdecMode) {
        HwdecMode.HARDWARE_COPY -> stringResource(R.string.hwdec_copy_recommended)
        HwdecMode.HARDWARE_DIRECT -> stringResource(R.string.hwdec_direct)
        HwdecMode.SOFTWARE -> stringResource(R.string.hwdec_sw)
    }
    val summary = "$hwLabel · ${stringResource(R.string.sheet_decoder_hint)}"

    GyroSettingsGroup {
        GyroExpandableSection(
            title = stringResource(R.string.stats_decoder),
            icon = GyroIcons.Memory,
            summary = summary,
            summaryHighlighted = state.hwdecMode == HwdecMode.SOFTWARE,
            expanded = expanded,
            onToggle = onToggle,
        ) {
            GyroRadioRow(
                title = stringResource(R.string.hwdec_copy_recommended),
                subtitle = stringResource(R.string.hwdec_copy_desc),
                selected = state.hwdecMode == HwdecMode.HARDWARE_COPY,
                onClick = { callbacks.onSetHwdec(HwdecMode.HARDWARE_COPY) },
            )
            GyroGroupDivider()
            GyroRadioRow(
                title = stringResource(R.string.hwdec_direct),
                subtitle = stringResource(R.string.hwdec_direct_desc),
                selected = state.hwdecMode == HwdecMode.HARDWARE_DIRECT,
                onClick = { callbacks.onSetHwdec(HwdecMode.HARDWARE_DIRECT) },
            )
            GyroGroupDivider()
            GyroRadioRow(
                title = stringResource(R.string.hwdec_sw),
                subtitle = stringResource(R.string.hwdec_sw_desc),
                selected = state.hwdecMode == HwdecMode.SOFTWARE,
                onClick = { callbacks.onSetHwdec(HwdecMode.SOFTWARE) },
            )
        }
    }
}

private fun formatSignedInt(value: Int): String = if (value > 0) "+$value" else "$value"
