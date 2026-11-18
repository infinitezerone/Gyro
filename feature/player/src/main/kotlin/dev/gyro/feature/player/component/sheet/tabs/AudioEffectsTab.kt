package dev.gyro.feature.player.component.sheet.tabs

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.model.EQUALIZER_BANDS
import dev.gyro.core.model.EQUALIZER_GAIN_LIMIT_DB
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetTab
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SettingsItemRow
import dev.gyro.feature.player.component.sheet.common.SheetSwitch
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetChip
import dev.gyro.feature.player.component.sheet.common.SheetSectionHeader
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.SheetTrackColor
import java.util.Locale

/** Short band labels; the raw frequencies are too wide for a row. */
private val EqBandLabels = listOf("60", "230", "910", "3.6k", "14k")

/**
 * Equaliser presets in dB, aligned with [EQUALIZER_BANDS].
 *
 * Starting points rather than modes: moving any slider drops the row out of the preset and the
 * chips stop highlighting, which is why there is no explicit "custom" entry.
 */
private val EqPresets: List<Pair<Int, List<Double>>> = listOf(
    R.string.audio_equalizer_flat to listOf(0.0, 0.0, 0.0, 0.0, 0.0),
    R.string.eq_preset_vocal to listOf(-2.0, 0.0, 3.0, 2.0, -1.0),
    R.string.eq_preset_bass to listOf(6.0, 3.0, 0.0, -1.0, -2.0),
    R.string.eq_preset_treble to listOf(-2.0, -1.0, 0.0, 3.0, 5.0),
)

/**
 * Everything that changes *how* the audio sounds, in one page.
 *
 * The equaliser needs a libavfilter with the audio filters compiled in. That is why `libmpv` no
 * longer comes from the prebuilt AAR (whose 138 KB libavfilter had neither `equalizer` nor
 * `bass`): the .so files under `app/src/main/jniLibs` come from an mpv-android build with the full
 * filter set.
 */
@Composable
internal fun AudioEffectsTabContent(
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
        SettingsItemRow(
            title = stringResource(R.string.audio_loudness_leveling),
            icon = Icons.Default.GraphicEq,
            trailingContent = {
                SheetSwitch(checked = state.nightMode, onCheckedChange = callbacks.onSetNightMode)
            }
        )
        Text(
            text = stringResource(R.string.audio_loudness_leveling_desc),
            fontSize = 11.sp,
            color = SheetTextSecondaryColor,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )

        SheetSectionHeader(
            stringResource(R.string.settings_audio_boost),
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
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
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(horizontal = 16.dp),
            colors = SliderDefaults.colors(
                thumbColor = SheetAccentBlue,
                activeTrackColor = SheetAccentBlue,
                inactiveTrackColor = SheetTrackColor
            )
        )
        Spacer(modifier = Modifier.height(4.dp))

        SheetSectionHeader(
            stringResource(R.string.audio_equalizer),
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            EqPresets.forEach { (labelRes, gains) ->
                SheetChip(
                    selected = state.eqGainsDb == gains,
                    onClick = { callbacks.onSetEqualizerGains(gains) },
                    label = stringResource(labelRes),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // One row per band instead of a fader bank: horizontal sliders stay thumb-reachable and the
        // numeric readout says what the drag actually did.
        EQUALIZER_BANDS.forEachIndexed { index, band ->
            val gain = state.eqGainsDb.getOrElse(index) { 0.0 }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = EqBandLabels.getOrElse(index) { band.toInt().toString() },
                    fontSize = 11.sp,
                    color = SheetTextSecondaryColor,
                    modifier = Modifier.width(40.dp)
                )
                Slider(
                    value = gain.toFloat(),
                    onValueChange = { value ->
                        val updated = state.eqGainsDb.toMutableList()
                        while (updated.size <= index) updated.add(0.0)
                        updated[index] = value.toDouble()
                        callbacks.onSetEqualizerGains(updated)
                    },
                    valueRange = -EQUALIZER_GAIN_LIMIT_DB.toFloat()..
                        EQUALIZER_GAIN_LIMIT_DB.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = SheetAccentBlue,
                        activeTrackColor = SheetAccentBlue,
                        inactiveTrackColor = SheetTrackColor
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatGain(gain),
                    fontSize = 11.sp,
                    color = SheetTextSecondaryColor,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        SheetSectionHeader(
            stringResource(R.string.hub_group_audio),
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp)
        )

        SettingsItemRow(
            title = stringResource(R.string.hub_stereo_downmix),
            icon = Icons.Default.Tune,
            trailingContent = {
                SheetSwitch(checked = state.stereoDownmix, onCheckedChange = callbacks.onSetStereoDownmix)
            }
        )

        SettingsItemRow(
            title = stringResource(R.string.hub_audio_passthrough),
            icon = Icons.Default.Speed,
            trailingContent = {
                SheetSwitch(checked = state.audioPassthrough, onCheckedChange = callbacks.onToggleAudioPassthrough)
            }
        )

        val audioDelayText = if (Math.abs(state.audioDelaySec) > 0.01) {
            "${String.format(Locale.US, "%.1f", state.audioDelaySec)}s"
        } else {
            stringResource(R.string.delay_synced_label)
        }
        SettingsItemRow(
            title = stringResource(R.string.hub_audio_delay_sync),
            icon = Icons.Default.Schedule,
            currentValue = audioDelayText,
            onClick = { onNavigate(PlayerSheetTab.AUDIO_DELAY) }
        )
    }
}

private fun formatGain(gain: Double): String =
    if (Math.abs(gain) < 0.05) "0 dB" else String.format(Locale.US, "%+.0f dB", gain)
