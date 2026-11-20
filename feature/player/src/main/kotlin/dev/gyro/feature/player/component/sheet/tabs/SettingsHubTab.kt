package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroChip
import dev.gyro.core.designsystem.component.GyroGroupDivider
import dev.gyro.core.designsystem.component.GyroNavigationRow
import dev.gyro.core.designsystem.component.GyroQuickTile
import dev.gyro.core.designsystem.component.GyroSectionHeader
import dev.gyro.core.designsystem.component.GyroSettingsGroup
import dev.gyro.core.designsystem.icon.GyroIcons
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.domain.session.PlaylistRepeatMode
import dev.gyro.core.model.GlslShader
import dev.gyro.engine.model.ResizeMode
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.nextPlaylistRepeatMode
import dev.gyro.feature.player.component.sheet.common.displayLabel
import dev.gyro.feature.player.component.sheet.common.shortCodecName
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

private val HubSpeedPresets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

/**
 * 播放页抽屉首页 (SettingsHubTab)。
 *
 * 按渐进式披露组织，全页最多 2 层：
 * 1. 顶部四格快捷动作：画中画 / 后台播放 / 循环模式（就地切换）/ 定时关闭（就地展开）；
 * 2. 倍速直控卡片：标题行右侧可进入连续微调，下方直接点选常用档位；
 * 3. 三大核心领域（画面 / 声音 / 字幕）：带状态摘要与非默认高亮；
 * 4. 媒体信息与诊断 + 应用设置。
 */
@Composable
internal fun SettingsHubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    onNavigate: (PlayerSheetRoute) -> Unit,
) {
    var timerExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HubQuickTileRow(
            state = state,
            timerExpanded = timerExpanded,
            onToggleTimerExpanded = { timerExpanded = !timerExpanded },
            callbacks = callbacks,
        )

        AnimatedVisibility(
            visible = timerExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            SleepTimerInlineCard(state = state, callbacks = callbacks)
        }

        SpeedQuickCard(
            currentSpeed = state.currentSpeed,
            onSelectSpeed = callbacks.onSelectSpeed,
            onOpenFineTune = { onNavigate(PlayerSheetRoute.SPEED) },
        )

        CoreDomainsCard(
            state = state,
            onNavigate = onNavigate,
        )

        FooterActionsCard(
            state = state,
            callbacks = callbacks,
            onNavigate = onNavigate,
        )
    }
}

@Composable
private fun HubQuickTileRow(
    state: PlayerSheetUiState,
    timerExpanded: Boolean,
    onToggleTimerExpanded: () -> Unit,
    callbacks: PlayerSheetCallbacks,
) {
    val repeatIcon = when (state.playlistRepeatMode) {
        PlaylistRepeatMode.SEQUENTIAL -> GyroIcons.PlaylistPlay
        PlaylistRepeatMode.LOOP_ALL -> GyroIcons.Repeat
        PlaylistRepeatMode.LOOP_ONE -> GyroIcons.RepeatOne
    }
    val repeatLabel = stringResource(state.playlistRepeatMode.labelRes())
    val repeatSelected = state.playlistRepeatMode != PlaylistRepeatMode.SEQUENTIAL
    val timerBadge = if (state.sleepTimerSecondsRemaining > 0L) {
        val rem = state.sleepTimerSecondsRemaining
        "%02d:%02d".format(Locale.US, rem / 60, rem % 60)
    } else {
        null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GyroQuickTile(
            label = stringResource(R.string.sheet_quick_pip),
            icon = GyroIcons.Pip,
            selected = false,
            onClick = callbacks.onEnterPip,
            modifier = Modifier.weight(1f),
        )
        GyroQuickTile(
            label = stringResource(R.string.sheet_quick_bg_audio),
            icon = GyroIcons.Headphones,
            selected = state.backgroundAudio,
            onClick = { callbacks.onToggleBackgroundAudio(!state.backgroundAudio) },
            modifier = Modifier.weight(1f),
        )
        GyroQuickTile(
            label = repeatLabel,
            icon = repeatIcon,
            selected = repeatSelected,
            onClick = {
                callbacks.onSetPlaylistRepeatMode(nextPlaylistRepeatMode(state.playlistRepeatMode))
            },
            modifier = Modifier.weight(1f),
        )
        GyroQuickTile(
            label = stringResource(R.string.sheet_quick_timer),
            icon = GyroIcons.SleepTimer,
            selected = timerExpanded || timerBadge != null,
            badge = timerBadge,
            onClick = onToggleTimerExpanded,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SleepTimerInlineCard(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
) {
    GyroSettingsGroup {
        GyroSectionHeader(
            title = stringResource(R.string.sleep_timer_title),
            horizontalPadding = 16.dp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                0 to stringResource(R.string.sleep_timer_off),
                15 to stringResource(R.string.sleep_timer_15m),
                30 to stringResource(R.string.sleep_timer_30m),
                60 to stringResource(R.string.sleep_timer_60m),
            ).forEach { (minutes, label) ->
                val isSelected = (minutes == 0 && state.sleepTimerSecondsRemaining == 0L) ||
                    (minutes > 0 && state.sleepTimerSecondsRemaining in (minutes * 60 - 5L)..(minutes * 60L))
                GyroChip(
                    label = label,
                    selected = isSelected,
                    onClick = { callbacks.onSetSleepTimer(minutes) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SpeedQuickCard(
    currentSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
    onOpenFineTune: () -> Unit,
) {
    val speedModified = abs(currentSpeed - 1.0f) >= 0.01f
    val speedScrollState = rememberScrollState()

    GyroSettingsGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenFineTune)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = GyroIcons.Speed,
                contentDescription = null,
                tint = if (speedModified) GyroColors.PrimaryLight else GyroColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.settings_playback_speed),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = GyroColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${formatSpeedValue(currentSpeed)}x · ${stringResource(R.string.sheet_speed_fine_tune)}",
                fontSize = 13.sp,
                fontWeight = if (speedModified) FontWeight.Medium else FontWeight.Normal,
                color = if (speedModified) GyroColors.PrimaryLight else GyroColors.TextSecondary,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = GyroIcons.ChevronRight,
                contentDescription = null,
                tint = GyroColors.TextSecondary.copy(alpha = 0.55f),
                modifier = Modifier.size(12.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(speedScrollState)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HubSpeedPresets.forEach { speed ->
                    GyroChip(
                        label = "${formatSpeedValue(speed)}x",
                        selected = abs(currentSpeed - speed) < 0.01f,
                        onClick = { onSelectSpeed(speed) },
                        modifier = Modifier.widthIn(min = 52.dp),
                    )
                }
            }
            if (speedScrollState.canScrollForward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(28.dp)
                        .height(34.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, GyroColors.PanelCard.copy(alpha = 0.95f))
                            )
                        ),
                )
            }
        }
    }
}

@Composable
private fun CoreDomainsCard(
    state: PlayerSheetUiState,
    onNavigate: (PlayerSheetRoute) -> Unit,
) {
    // 画面摘要
    val aspectLabel = state.currentResizeMode.displayLabel()
    val shaderLabel = when (state.shaderState.activeShader) {
        GlslShader.ANIME4K -> stringResource(R.string.shader_anime4k_short)
        GlslShader.CAS -> stringResource(R.string.shader_cas_short)
        GlslShader.CUSTOM -> state.shaderState.displayName ?: stringResource(R.string.shader_custom)
        else -> null
    }
    val hasColorMod = state.brightness != 0 || state.contrast != 0 || state.saturation != 0
    val colorTag = if (hasColorMod) stringResource(R.string.color_state_tuned) else null
    val videoModified = state.currentResizeMode != ResizeMode.FIT ||
        shaderLabel != null ||
        state.deband ||
        hasColorMod
    val videoSummary = listOfNotNull(aspectLabel, shaderLabel, colorTag).joinToString(" · ")

    // 声音摘要
    val audioTrackName = state.audioTracks.firstOrNull { it.isSelected }?.title
        ?: stringResource(R.string.track_none_audio)
    val roundedVol = round(state.softwareVolume).toInt()
    val volTag = if (roundedVol != 100) "$roundedVol%" else null
    val eqMod = state.eqGainsDb.any { abs(it) >= 0.05 }
    val audioDelayTag = if (abs(state.audioDelaySec) >= 0.05) formatDelayTag(state.audioDelaySec) else null
    val audioModified = volTag != null ||
        state.nightMode ||
        eqMod ||
        audioDelayTag != null ||
        state.stereoDownmix ||
        state.audioPassthrough
    val audioSummary = listOfNotNull(audioTrackName, volTag, audioDelayTag).joinToString(" · ")

    // 字幕摘要
    val primarySub = state.subtitleTracks.firstOrNull { it.isSelected }?.title
    val secondarySubId = state.effectiveSecondarySubId
    val subBase = when {
        primarySub != null && secondarySubId > 0 -> {
            val sec = state.subtitleTracks.firstOrNull { it.id == secondarySubId }?.title
                ?: stringResource(R.string.track_secondary_subtitle)
            "$primarySub + $sec"
        }
        primarySub != null -> primarySub
        else -> stringResource(R.string.common_off)
    }
    val subDelayTag = if (abs(state.subDelaySec) >= 0.05) formatDelayTag(state.subDelaySec) else null
    val subModified = primarySub != null ||
        secondarySubId > 0 ||
        subDelayTag != null ||
        abs(state.subScale - 1.0) >= 0.05 ||
        state.subPos != 100L ||
        state.subAssOverride
    val subSummary = listOfNotNull(subBase, subDelayTag).joinToString(" · ")

    GyroSettingsGroup {
        GyroNavigationRow(
            title = stringResource(R.string.hub_group_video),
            icon = GyroIcons.FitScreen,
            value = videoSummary,
            valueHighlighted = videoModified,
            onClick = { onNavigate(PlayerSheetRoute.VIDEO) },
        )
        GyroGroupDivider(insetStart = 46.dp)
        GyroNavigationRow(
            title = stringResource(R.string.hub_group_audio),
            icon = GyroIcons.AudioTrack,
            value = audioSummary,
            valueHighlighted = audioModified,
            onClick = { onNavigate(PlayerSheetRoute.AUDIO) },
        )
        GyroGroupDivider(insetStart = 46.dp)
        GyroNavigationRow(
            title = stringResource(R.string.hub_group_subtitles),
            icon = GyroIcons.Subtitles,
            value = subSummary,
            valueHighlighted = subModified,
            onClick = { onNavigate(PlayerSheetRoute.SUBTITLES) },
        )
    }
}

@Composable
private fun FooterActionsCard(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    onNavigate: (PlayerSheetRoute) -> Unit,
) {
    val codecText = state.mediaInfo?.videoCodec?.let { shortCodecName(it) }?.ifBlank { null }
    val resolutionText = if (state.videoWidth > 0) "${state.videoHeight}P" else null
    val mediaSummary = listOfNotNull(resolutionText, codecText).joinToString(" · ")

    GyroSettingsGroup {
        GyroNavigationRow(
            title = stringResource(R.string.page_stats_info),
            icon = GyroIcons.Info,
            value = mediaSummary.ifBlank { stringResource(R.string.action_view_details) },
            valueHighlighted = state.statsOverlayEnabled,
            onClick = { onNavigate(PlayerSheetRoute.STATS_INFO) },
        )
        GyroGroupDivider(insetStart = 46.dp)
        GyroNavigationRow(
            title = stringResource(R.string.app_settings),
            icon = GyroIcons.Settings,
            value = stringResource(R.string.app_settings_exit_hint),
            onClick = callbacks.onOpenFullSettings,
        )
    }
}

internal fun PlaylistRepeatMode.labelRes(): Int = when (this) {
    PlaylistRepeatMode.SEQUENTIAL -> R.string.loop_mode_none
    PlaylistRepeatMode.LOOP_ALL -> R.string.loop_mode_all
    PlaylistRepeatMode.LOOP_ONE -> R.string.loop_mode_single
}

internal fun formatSpeedValue(speed: Float): String {
    val rounded = round(speed * 100f) / 100f
    return when {
        abs(rounded - rounded.toInt()) < 0.01f -> "${rounded.toInt()}.0"
        abs(rounded * 10f - round(rounded * 10f)) < 0.01f -> String.format(Locale.US, "%.1f", rounded)
        else -> String.format(Locale.US, "%.2f", rounded)
    }
}

private fun formatDelayTag(seconds: Double): String =
    String.format(Locale.US, "%+.1fs", seconds)
