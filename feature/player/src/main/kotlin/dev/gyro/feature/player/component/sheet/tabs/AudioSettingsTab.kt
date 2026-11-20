package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import dev.gyro.core.model.EQUALIZER_BANDS
import dev.gyro.core.model.EQUALIZER_GAIN_LIMIT_DB
import dev.gyro.engine.model.TrackType
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.DelayCalibrationBlock
import dev.gyro.feature.player.component.sheet.common.EmptyTrackNotice
import dev.gyro.feature.player.component.sheet.common.formatDelayValue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

private val EqBandLabels = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")

private val EqPresets: List<Pair<Int, List<Double>>> = listOf(
    R.string.audio_equalizer_flat to listOf(0.0, 0.0, 0.0, 0.0, 0.0),
    R.string.eq_preset_vocal to listOf(-2.0, 0.0, 3.0, 2.0, -1.0),
    R.string.eq_preset_bass to listOf(6.0, 3.0, 0.0, -1.0, -2.0),
    R.string.eq_preset_treble to listOf(-2.0, -1.0, 0.0, 3.0, 5.0),
)

/**
 * 「声音」领域二级页：
 * - 首屏直控：音频轨道 + 音量软增益快捷芯片（超 100% 时自动展开精细滑块）；
 * - 渐进折叠：音效与均衡器（响度均衡 + 预设 + 可展开 5 段滑块）、同步与输出（音频延迟 + 下混 + 透传）。
 */
@Composable
internal fun AudioSettingsTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
) {
    val eqModified = state.eqGainsDb.any { abs(it) >= 0.05 }
    val matchedPreset = EqPresets.firstOrNull { (_, preset) ->
        preset.size == state.eqGainsDb.size &&
            preset.zip(state.eqGainsDb).all { (a, b) -> abs(a - b) < 0.05 }
    }
    var effectsExpanded by rememberSaveable { mutableStateOf(false) }
    var customEqExpanded by rememberSaveable { mutableStateOf(eqModified && matchedPreset == null) }
    var syncOutputExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AudioTracksSection(state = state, callbacks = callbacks)

        SoftwareVolumeCard(state = state, callbacks = callbacks)

        AudioEffectsCard(
            state = state,
            callbacks = callbacks,
            eqModified = eqModified,
            matchedPreset = matchedPreset,
            expanded = effectsExpanded,
            onToggleExpanded = { effectsExpanded = !effectsExpanded },
            customEqExpanded = customEqExpanded,
            onToggleCustomEq = { customEqExpanded = !customEqExpanded },
        )

        AudioSyncAndOutputCard(
            state = state,
            callbacks = callbacks,
            expanded = syncOutputExpanded,
            onToggleExpanded = { syncOutputExpanded = !syncOutputExpanded },
        )
    }
}

@Composable
private fun AudioTracksSection(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        GyroSectionHeader(title = stringResource(R.string.settings_audio_track))
        GyroSettingsGroup {
            if (state.audioTracks.isEmpty()) {
                EmptyTrackNotice(stringResource(R.string.track_none_audio))
            } else {
                state.audioTracks.forEachIndexed { index, track ->
                    if (index > 0) GyroGroupDivider()
                    GyroRadioRow(
                        title = track.title ?: stringResource(R.string.track_audio_index_fmt, track.id),
                        subtitle = track.lang?.uppercase(),
                        selected = track.isSelected,
                        onClick = { callbacks.onSelectTrack(TrackType.AUDIO, track.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SoftwareVolumeCard(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
) {
    val roundedVolume = round(state.softwareVolume).toInt()
    val isBoosted = roundedVolume != 100

    GyroSettingsGroup {
        GyroSectionHeader(
            title = stringResource(R.string.settings_audio_boost),
            actionLabel = stringResource(R.string.action_reset),
            onActionClick = { callbacks.onSetSoftwareVolume(100.0) },
            actionEnabled = isBoosted,
            horizontalPadding = 16.dp,
            modifier = Modifier.padding(top = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(100.0 to "100%", 125.0 to "125%", 150.0 to "150%", 200.0 to "200%").forEach { (v, label) ->
                GyroChip(
                    label = label,
                    selected = abs(state.softwareVolume - v) < 2.0,
                    onClick = { callbacks.onSetSoftwareVolume(v) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        AnimatedVisibility(
            visible = roundedVolume > 100,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            GyroFineTuneSliderRow(
                label = stringResource(R.string.audio_boost_levels),
                valueText = "$roundedVolume%",
                value = state.softwareVolume.toFloat(),
                valueRange = 100f..200f,
                step = 5f,
                snapValue = 100f,
                snapThreshold = 3f,
                isModified = isBoosted,
                onValueChange = { raw ->
                    callbacks.onSetSoftwareVolume(round(raw).toDouble().coerceIn(100.0, 200.0))
                },
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun AudioEffectsCard(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    eqModified: Boolean,
    matchedPreset: Pair<Int, List<Double>>?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    customEqExpanded: Boolean,
    onToggleCustomEq: () -> Unit,
) {
    val eqLabel = when {
        matchedPreset != null -> stringResource(matchedPreset.first)
        eqModified -> stringResource(R.string.sheet_status_custom)
        else -> null
    }
    val summary = when {
        state.nightMode && eqModified && eqLabel != null ->
            "${stringResource(R.string.audio_loudness_leveling)} · $eqLabel"
        state.nightMode -> stringResource(R.string.audio_loudness_leveling)
        eqModified && eqLabel != null -> eqLabel
        else -> stringResource(R.string.sheet_status_default)
    }
    val highlighted = state.nightMode || eqModified

    GyroSettingsGroup {
        GyroExpandableSection(
            title = stringResource(R.string.sheet_section_audio_effects),
            icon = GyroIcons.GraphicEq,
            summary = summary,
            summaryHighlighted = highlighted,
            expanded = expanded,
            onToggle = onToggleExpanded,
        ) {
            GyroSwitchRow(
                title = stringResource(R.string.audio_loudness_leveling),
                subtitle = stringResource(R.string.audio_loudness_leveling_desc),
                checked = state.nightMode,
                onCheckedChange = callbacks.onSetNightMode,
            )

            GyroGroupDivider()

            GyroSectionHeader(
                title = stringResource(R.string.audio_equalizer),
                actionLabel = stringResource(R.string.action_reset),
                onActionClick = {
                    callbacks.onSetEqualizerGains(List(EQUALIZER_BANDS.size) { 0.0 })
                },
                actionEnabled = eqModified,
                horizontalPadding = 16.dp,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EqPresets.forEach { (labelRes, gains) ->
                    val isSelected = gains.size == state.eqGainsDb.size &&
                        gains.zip(state.eqGainsDb).all { (a, b) -> abs(a - b) < 0.05 }
                    GyroChip(
                        label = stringResource(labelRes),
                        selected = isSelected,
                        onClick = { callbacks.onSetEqualizerGains(gains) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            GyroGroupDivider()

            GyroExpandableSection(
                title = stringResource(R.string.sheet_section_eq_custom),
                summary = if (eqModified) eqLabel else null,
                summaryHighlighted = eqModified && matchedPreset == null,
                expanded = customEqExpanded,
                onToggle = onToggleCustomEq,
            ) {
                EQUALIZER_BANDS.forEachIndexed { index, band ->
                    val gain = state.eqGainsDb.getOrElse(index) { 0.0 }
                    GyroFineTuneSliderRow(
                        label = EqBandLabels.getOrElse(index) { "${band.toInt()} Hz" },
                        valueText = formatEqGain(gain),
                        value = gain.toFloat(),
                        valueRange = -EQUALIZER_GAIN_LIMIT_DB.toFloat()..EQUALIZER_GAIN_LIMIT_DB.toFloat(),
                        step = 1f,
                        snapValue = 0f,
                        snapThreshold = 0.5f,
                        isModified = abs(gain) >= 0.05,
                        onValueChange = { raw ->
                            val updated = state.eqGainsDb.toMutableList()
                            while (updated.size <= index) updated.add(0.0)
                            updated[index] = round(raw * 2.0) / 2.0
                            callbacks.onSetEqualizerGains(updated)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioSyncAndOutputCard(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val delayModified = abs(state.audioDelaySec) >= 0.05
    val summaryParts = buildList {
        if (delayModified) add(formatDelayValue(state.audioDelaySec))
        if (state.stereoDownmix) add(stringResource(R.string.hub_stereo_downmix))
        if (state.audioPassthrough) add(stringResource(R.string.hub_audio_passthrough))
    }
    val summary = if (summaryParts.isEmpty()) {
        stringResource(R.string.delay_synced_label)
    } else {
        summaryParts.joinToString(" · ")
    }
    val highlighted = summaryParts.isNotEmpty()

    GyroSettingsGroup {
        GyroExpandableSection(
            title = stringResource(R.string.sheet_section_audio_sync_output),
            icon = GyroIcons.Sync,
            summary = summary,
            summaryHighlighted = highlighted,
            expanded = expanded,
            onToggle = onToggleExpanded,
        ) {
            DelayCalibrationBlock(
                label = stringResource(R.string.sync_audio_delay),
                currentSec = state.audioDelaySec,
                onValueChange = callbacks.onSetAudioDelay,
            )
            GyroGroupDivider()
            GyroSwitchRow(
                title = stringResource(R.string.hub_stereo_downmix),
                subtitle = stringResource(R.string.downmix_desc),
                checked = state.stereoDownmix,
                onCheckedChange = callbacks.onSetStereoDownmix,
            )
            GyroGroupDivider()
            GyroSwitchRow(
                title = stringResource(R.string.hub_audio_passthrough),
                checked = state.audioPassthrough,
                onCheckedChange = callbacks.onToggleAudioPassthrough,
            )
        }
    }
}

private fun formatEqGain(gain: Double): String =
    if (abs(gain) < 0.05) "0 dB" else String.format(Locale.US, "%+.1f dB", gain)
