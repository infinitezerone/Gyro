package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gyro.core.designsystem.R
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.HwdecMode
import dev.gyro.engine.model.ResizeMode
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor

/**
 * 解码模式二级选择页 (HwdecSubTab)
 */
@Composable
internal fun HwdecSubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    val isCopyMode = state.hwdecMode == HwdecMode.HARDWARE_COPY
    val isHwDirect = state.hwdecMode == HwdecMode.HARDWARE_DIRECT
    val isSw = state.hwdecMode == HwdecMode.SOFTWARE

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
            onClick = { callbacks.onSetHwdec(HwdecMode.HARDWARE_COPY) }
        )
        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        SettingRadioRow(
            title = stringResource(R.string.hwdec_direct),
            subtitle = stringResource(R.string.hwdec_direct_desc),
            isSelected = isHwDirect,
            onClick = { callbacks.onSetHwdec(HwdecMode.HARDWARE_DIRECT) }
        )
        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        SettingRadioRow(
            title = stringResource(R.string.hwdec_sw),
            subtitle = stringResource(R.string.hwdec_sw_desc),
            isSelected = isSw,
            onClick = { callbacks.onSetHwdec(HwdecMode.SOFTWARE) }
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
    val isHwDirect = state.hwdecMode == HwdecMode.HARDWARE_DIRECT

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
                if (isHwDirect) callbacks.onSetHwdec(HwdecMode.HARDWARE_COPY)
                callbacks.onSelectShader(GlslShader.ANIME4K)
            }
        )
        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        SettingRadioRow(
            title = stringResource(R.string.shader_cas_sharpen),
            subtitle = stringResource(R.string.shader_cas_sharpen_desc),
            isSelected = state.shaderState.activeShader == GlslShader.CAS,
            onClick = {
                if (isHwDirect) callbacks.onSetHwdec(HwdecMode.HARDWARE_COPY)
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
        ResizeMode.ASPECT_4_3 to stringResource(R.string.aspect_mode_4_3_full)
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
 * 播放倍速二级选择页 (SpeedSubTab)
 */
@Composable
internal fun SpeedSubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)

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
