package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.domain.session.PlaylistRepeatMode
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SheetChip
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import java.util.Locale

/**
 * 定时关闭。和循环彻底分开：这里只负责到点暂停。
 */
@Composable
internal fun TimerTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (state.sleepTimerSecondsRemaining > 0) {
            val rem = state.sleepTimerSecondsRemaining
            Text(
                text = stringResource(
                    R.string.sleep_timer_active_fmt,
                    "%02d:%02d".format(Locale.US, rem / 60, rem % 60)
                ),
                fontSize = 12.sp,
                color = SheetTextSecondaryColor,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                0 to stringResource(R.string.sleep_timer_off),
                15 to stringResource(R.string.sleep_timer_15m),
                30 to stringResource(R.string.sleep_timer_30m),
                60 to stringResource(R.string.sleep_timer_60m)
            ).forEach { (minutes, label) ->
                SheetChip(
                    selected = (minutes == 0 && state.sleepTimerSecondsRemaining == 0L) ||
                        (minutes > 0 && state.sleepTimerSecondsRemaining in (minutes * 60 - 5L)..(minutes * 60L)),
                    onClick = { callbacks.onSetSleepTimer(minutes) },
                    label = label,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 列表播放模式：顺序 / 列表循环 / 单集循环。
 */
@Composable
internal fun PlaybackModeTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        PlaylistRepeatMode.entries.forEach { mode ->
            SettingRadioRow(
                title = stringResource(mode.labelRes()),
                isSelected = state.playlistRepeatMode == mode,
                onClick = { callbacks.onSetPlaylistRepeatMode(mode) }
            )
        }
    }
}

internal fun PlaylistRepeatMode.labelRes(): Int = when (this) {
    PlaylistRepeatMode.SEQUENTIAL -> R.string.loop_mode_none
    PlaylistRepeatMode.LOOP_ALL -> R.string.loop_mode_all
    PlaylistRepeatMode.LOOP_ONE -> R.string.loop_mode_single
}
