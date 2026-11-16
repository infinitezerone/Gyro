package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.model.GlslShader
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetTab
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SettingsItemRow
import dev.gyro.feature.player.component.sheet.common.SheetSwitch
import dev.gyro.feature.player.component.sheet.common.shortCodecName
import dev.gyro.feature.player.component.sheet.common.SheetChip
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetCardColor
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.displayLabel
import java.util.Locale

/**
 * 主播放设置菜单 (SettingsHubTab) - 高密度扁平列表
 *
 * 规范：左边图标+标题，右边当前状态/Switch，去除所有冗余说明文案，需要二级就二级。
 */
@Composable
internal fun SettingsHubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    onNavigate: (PlayerSheetTab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // The only tile left is the one action that cannot live in a list: entering PiP closes the
        // sheet and changes the whole window mode. The three selectors that used to sit here were
        // choices, not actions, so they moved into their own groups below where the rest of the
        // settings of the same kind live.
        PipTile(onClick = callbacks.onEnterPip, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

        // ==================== 1. 播放分组 ====================
        CategoryHeader(stringResource(R.string.hub_group_playback))

        SettingsItemRow(
            title = stringResource(R.string.settings_playback_speed),
            icon = Icons.Default.Speed,
            currentValue = "${state.currentSpeed}x",
            onClick = { onNavigate(PlayerSheetTab.SPEED) }
        )

        // The presets sit under the row instead of behind it: the row is the way into the fine
        // control, the chips are the shortcut for the few speeds people actually switch between.
        val speedPresets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            speedPresets.forEach { speed ->
                SheetChip(
                    selected = kotlin.math.abs(state.currentSpeed - speed) < 0.01f,
                    onClick = { callbacks.onSelectSpeed(speed) },
                    label = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        val abLoopText = when {
            state.abLoopASeconds >= 0 && state.abLoopBSeconds >= 0 -> {
                val aSec = state.abLoopASeconds.toInt()
                val bSec = state.abLoopBSeconds.toInt()
                val aStr = "%02d:%02d".format(Locale.US, aSec / 60, aSec % 60)
                val bStr = "%02d:%02d".format(Locale.US, bSec / 60, bSec % 60)
                "$aStr - $bStr"
            }
            state.abLoopASeconds >= 0 -> {
                val aSec = state.abLoopASeconds.toInt()
                val aStr = "%02d:%02d".format(Locale.US, aSec / 60, aSec % 60)
                stringResource(R.string.ab_loop_set_b_short) + " ($aStr)"
            }
            else -> stringResource(R.string.common_off)
        }
        SettingsItemRow(
            title = stringResource(R.string.ab_loop_title),
            icon = Icons.Default.Repeat,
            currentValue = abLoopText,
            onClick = callbacks.onCycleAbLoop
        )

        // The loop row only ever cycles A -> B -> off, so getting out of a half-set loop meant
        // cycling through states. This row appears only while a loop is armed.
        if (state.abLoopASeconds >= 0) {
            SettingsItemRow(
                title = stringResource(R.string.ab_loop_clear),
                icon = Icons.Default.Close,
                onClick = callbacks.onClearAbLoop
            )
        }

        SectionDivider()

        // ==================== 2. 画面设置分组 ====================
        CategoryHeader(stringResource(R.string.hub_group_video))

        SettingsItemRow(
            title = stringResource(R.string.aspect_title),
            icon = Icons.Default.FitScreen,
            currentValue = state.currentResizeMode.displayLabel(),
            onClick = { onNavigate(PlayerSheetTab.ASPECT) }
        )

        val hwLabel = when {
            state.hwdec.contains("copy") -> stringResource(R.string.hwdec_copy)
            state.hwdec.contains("mediacodec") -> stringResource(R.string.hwdec_direct)
            else -> stringResource(R.string.hwdec_sw)
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
            title = stringResource(R.string.video_quality),
            icon = Icons.Default.AutoAwesome,
            currentValue = shaderLabel,
            onClick = { onNavigate(PlayerSheetTab.VIDEO_QUALITY) }
        )

        SectionDivider()

        // ==================== 3. 声音设置分组 ====================
        CategoryHeader(stringResource(R.string.hub_group_audio))

        val audioTrack = state.audioTracks.firstOrNull { it.isSelected }?.title
            ?: stringResource(R.string.track_no_internal_audio)
        SettingsItemRow(
            title = stringResource(R.string.settings_audio_track),
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            currentValue = audioTrack,
            onClick = { onNavigate(PlayerSheetTab.AUDIO_TRACKS) }
        )

        val roundedVolume = kotlin.math.round(state.softwareVolume).toInt()
        SettingsItemRow(
            title = stringResource(R.string.hub_audio_boost),
            icon = Icons.Default.GraphicEq,
            currentValue = "$roundedVolume%",
            onClick = { onNavigate(PlayerSheetTab.AUDIO_BOOST) }
        )

        // Processing moved into its own page: loudness levelling, downmix, passthrough and delay
        // are all "how the audio is treated", and they are only touched when something sounds
        // wrong. The row shows the one that is currently active.
        val audioProcessingState = when {
            state.nightMode -> stringResource(R.string.audio_loudness_leveling)
            state.eqGainsDb.any { Math.abs(it) > 0.1 } -> stringResource(R.string.audio_equalizer)
            else -> stringResource(R.string.audio_equalizer_flat)
        }
        SettingsItemRow(
            title = stringResource(R.string.sheet_tab_audio_effects),
            icon = Icons.Default.Tune,
            currentValue = audioProcessingState,
            onClick = { onNavigate(PlayerSheetTab.AUDIO_EFFECTS) }
        )

        SectionDivider()

        // ==================== 4. 字幕设置分组 ====================
        CategoryHeader(stringResource(R.string.hub_group_subtitles))

        val subtitleTrack = state.subtitleTracks.firstOrNull { it.isSelected }?.title
            ?: stringResource(R.string.common_off)
        SettingsItemRow(
            title = stringResource(R.string.settings_subtitle_track),
            icon = Icons.Default.ClosedCaption,
            currentValue = subtitleTrack,
            onClick = { onNavigate(PlayerSheetTab.SUBTITLE_TRACKS) }
        )

        val subDelayText = if (Math.abs(state.subDelaySec) > 0.01) {
            "${String.format(Locale.US, "%.1f", state.subDelaySec)}s"
        } else stringResource(R.string.delay_synced_label)
        SettingsItemRow(
            title = stringResource(R.string.hub_sub_delay_sync),
            icon = Icons.Default.Schedule,
            currentValue = subDelayText,
            onClick = { onNavigate(PlayerSheetTab.SUBTITLE_DELAY) }
        )

        SettingsItemRow(
            title = stringResource(R.string.hub_sub_style),
            icon = Icons.Default.TextFields,
            currentValue = "${state.subScale}x",
            onClick = { onNavigate(PlayerSheetTab.SUBTITLE_STYLE) }
        )

        SectionDivider()

        // ==================== 5. 信息与诊断分组 ====================
        CategoryHeader(stringResource(R.string.hub_group_advanced_diag))

        // The file: what is in it. Static, and the thing people check when something looks wrong
        // with the picture ("is this actually 1080p, is it really HEVC?").
        val codecText = state.mediaInfo?.videoCodec?.let { shortCodecName(it) }?.ifBlank { null }
        val resolutionText = if (state.videoWidth > 0) "${state.videoHeight}P" else null
        val mediaSummary = listOfNotNull(resolutionText, codecText).joinToString(" ")
        SettingsItemRow(
            title = stringResource(R.string.hub_media_details),
            icon = Icons.Default.Assessment,
            currentValue = if (mediaSummary.isNotBlank()) mediaSummary else stringResource(R.string.action_view_details),
            onClick = { onNavigate(PlayerSheetTab.STATS_INFO) }
        )

        // The playback: how it is running right now. Dynamic, and the answer to "why is it
        // stuttering". Same group, because both answer questions about the same file.
        SettingsItemRow(
            title = stringResource(R.string.hub_stats_overlay),
            icon = Icons.Default.Monitor,
            trailingContent = {
                SheetSwitch(
                    checked = state.statsOverlayEnabled,
                    onCheckedChange = callbacks.onToggleStatsOverlay
                )
            }
        )

        SectionDivider()

        // Not a diagnostic: this is the way out to app-level settings. Kept outside the group and
        // last, the way every settings screen ends.
        SettingsItemRow(
            title = stringResource(R.string.settings_more),
            icon = Icons.Default.Settings,
            onClick = callbacks.onOpenFullSettings
        )
    }
}

/**
 * A single full-width action entry. Unlike the settings rows below, this does not open a page -
 * it performs something (leaving the current window for PiP), so it gets a filled card rather than
 * a plain list row to say "this is a button, not a value".
 */
@Composable
private fun PipTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SheetCardColor,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.PictureInPicture,
                contentDescription = null,
                tint = SheetAccentBlue,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.cd_pip),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = SheetTextColor,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = SheetTextSecondaryColor.copy(alpha = 0.5f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun CategoryHeader(title: String) {
    // Section labels in mainstream settings sheets are quiet: they separate groups, they are not
    // another accent competing with the icons. Letter spacing carries the "label" reading instead
    // of colour.
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = SheetTextSecondaryColor,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
    )
}

/** Prints the gap between groups; the group label above it already does the separating. */
@Composable
private fun SectionDivider() {
    Spacer(modifier = Modifier.height(6.dp))
}
