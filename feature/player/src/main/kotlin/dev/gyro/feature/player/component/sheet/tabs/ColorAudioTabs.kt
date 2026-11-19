package dev.gyro.feature.player.component.sheet.tabs

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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.engine.model.TrackType
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
    step: Float = 1f,
    onChange: (Float) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var lastValue by remember { mutableIntStateOf(value) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp, color = SheetTextColor, modifier = Modifier.width(48.dp))

        // Precision step down
        Text(
            text = "−",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = SheetAccentBlue,
            modifier = Modifier
                .clickable {
                    val next = (value - step).coerceIn(range.start, range.endInclusive)
                    onChange(next)
                }
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )

        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val snapped = if (Math.abs(raw) < 2.5f && range.start < 0f && range.endInclusive > 0f) 0f else raw
                if (snapped.toInt() == 0 && lastValue != 0) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                lastValue = snapped.toInt()
                onChange(snapped)
            },
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = SheetAccentBlue,
                activeTrackColor = SheetAccentBlue,
                inactiveTrackColor = SheetTrackColor
            )
        )

        // Precision step up
        Text(
            text = "+",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = SheetAccentBlue,
            modifier = Modifier
                .clickable {
                    val next = (value + step).coerceIn(range.start, range.endInclusive)
                    onChange(next)
                }
                .padding(horizontal = 6.dp, vertical = 4.dp)
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
                    onClick = { callbacks.onSelectTrack(TrackType.AUDIO, track.id) }
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
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                -0.5 to "-0.5s",
                -0.1 to "-0.1s",
                0.0 to stringResource(R.string.action_reset),
                0.1 to "+0.1s",
                0.5 to "+0.5s"
            ).forEach { (offset, btnLabel) ->
                SheetChip(
                    selected = offset == 0.0 && Math.abs(currentSec) < 0.01,
                    onClick = {
                        if (offset == 0.0) {
                            onValueChange(0.0)
                        } else {
                            val next = (Math.round((currentSec + offset) * 10.0) / 10.0).coerceIn(-10.0, 10.0)
                            onValueChange(next)
                        }
                    },
                    label = btnLabel,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
