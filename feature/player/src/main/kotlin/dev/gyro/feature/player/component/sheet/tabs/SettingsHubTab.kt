package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SettingsItemRow
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetCardColor
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetBgColor
import dev.gyro.feature.player.component.sheet.common.SheetChip
import dev.gyro.feature.player.component.sheet.common.SheetChip
import dev.gyro.feature.player.component.sheet.common.SheetSwitch
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.displayLabel
import dev.gyro.feature.player.component.sheet.common.shortCodecName
import java.util.Locale

/**
 * 主播放设置菜单 (SettingsHubTab)
 *
 * 播放页只保留会话级、高频操作：
 * 1. 快捷操作 (PiP、定时关闭、A-B 循环)
 * 2. 倍速、画面比例、音轨、字幕轨、音量增强
 * 3. 更多播放设置（解码器/着色器/色彩/音效/字幕样式等低频项）
 * 4. 媒体信息、统计浮层、应用设置入口
 */
@Composable
internal fun SettingsHubTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    onNavigate: (PlayerSheetRoute) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        HubQuickActions(state = state, callbacks = callbacks)

        // 列表播放模式（顺序 / 列表循环 / 单集循环）
        SettingsItemRow(
            title = stringResource(R.string.playback_mode_title),
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
            currentValue = stringResource(state.playlistRepeatMode.labelRes()),
            onClick = { onNavigate(PlayerSheetRoute.PLAYBACK_MODE) }
        )

        // 定时关闭只负责到点暂停，和循环互不相干
        SettingsItemRow(
            title = stringResource(R.string.sleep_timer_title),
            icon = Icons.Default.Schedule,
            currentValue = sleepTimerSummary(state),
            onClick = { onNavigate(PlayerSheetRoute.TIMER) }
        )

        HubDivider()

        // 倍速
        SettingsItemRow(
            title = stringResource(R.string.settings_playback_speed),
            icon = Icons.Default.Speed,
            currentValue = "${state.currentSpeed}x",
            onClick = { onNavigate(PlayerSheetRoute.SPEED) }
        )
        val speedPresets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
        val speedScrollState = rememberScrollState()
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(speedScrollState)
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                speedPresets.forEach { speed ->
                    SheetChip(
                        selected = kotlin.math.abs(state.currentSpeed - speed) < 0.01f,
                        onClick = { callbacks.onSelectSpeed(speed) },
                        label = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x",
                        modifier = Modifier.widthIn(min = 56.dp)
                    )
                }
            }
            if (speedScrollState.canScrollForward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(32.dp)
                        .height(36.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, SheetBgColor)
                            )
                        )
                )
            }
        }

        // 画面比例
        SettingsItemRow(
            title = stringResource(R.string.aspect_title),
            icon = Icons.Default.FitScreen,
            currentValue = state.currentResizeMode.displayLabel(),
            onClick = { onNavigate(PlayerSheetRoute.ASPECT) }
        )

        HubDivider()

        // 音轨
        val audioTrackName = state.audioTracks.firstOrNull { it.isSelected }?.title
            ?: stringResource(R.string.track_none_audio)
        SettingsItemRow(
            title = stringResource(R.string.settings_audio_track),
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            currentValue = audioTrackName,
            onClick = { onNavigate(PlayerSheetRoute.AUDIO_TRACKS) }
        )

        // 字幕轨
        val subtitleTrack = state.subtitleTracks.firstOrNull { it.isSelected }?.title
            ?: stringResource(R.string.common_off)
        val subTrackDisplay = if (state.secondarySubId > 0) {
            val secTrack = state.subtitleTracks.firstOrNull { it.id == state.secondarySubId }?.title
                ?: stringResource(R.string.track_secondary_subtitle)
            "$subtitleTrack + $secTrack"
        } else {
            subtitleTrack
        }
        SettingsItemRow(
            title = stringResource(R.string.settings_subtitle_track),
            icon = Icons.Default.ClosedCaption,
            currentValue = subTrackDisplay,
            onClick = { onNavigate(PlayerSheetRoute.SUBTITLE_TRACKS) }
        )

        // 音量增强
        val roundedVolume = kotlin.math.round(state.softwareVolume).toInt()
        SettingsItemRow(
            title = stringResource(R.string.settings_audio_boost),
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            currentValue = "$roundedVolume%",
            onClick = { onNavigate(PlayerSheetRoute.AUDIO_BOOST) }
        )

        HubDivider()

        SettingsItemRow(
            title = stringResource(R.string.more_entry),
            icon = Icons.Default.AutoAwesome,
            onClick = { onNavigate(PlayerSheetRoute.MORE_PLAYBACK) },
            showChevron = true
        )
    }
}

@Composable
private fun sleepTimerSummary(state: PlayerSheetUiState): String {
    if (state.sleepTimerSecondsRemaining <= 0) return stringResource(R.string.sleep_timer_off)
    val rem = state.sleepTimerSecondsRemaining
    return "%02d:%02d".format(Locale.US, rem / 60, rem % 60)
}

@Composable
private fun HubDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        thickness = 0.5.dp,
        color = SheetCardAltColor.copy(alpha = 0.4f)
    )
}
