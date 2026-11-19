package dev.gyro.feature.player.component.sheet

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.tabs.AspectSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.AudioBoostSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.AudioEffectsTabContent
import dev.gyro.feature.player.component.sheet.tabs.AudioTracksSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.ChaptersTabContent
import dev.gyro.feature.player.component.sheet.tabs.ColorSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.DelaySubTabContent
import dev.gyro.feature.player.component.sheet.tabs.HwdecSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.MediaInfoStatsTabContent
import dev.gyro.feature.player.component.sheet.tabs.MorePlaybackTabContent
import dev.gyro.feature.player.component.sheet.tabs.PlaylistTabContent
import dev.gyro.feature.player.component.sheet.tabs.SettingsHubTabContent
import dev.gyro.feature.player.component.sheet.tabs.ShadersSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.SpeedSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.SubtitleStyleSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.SubtitleTracksSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.PlaybackModeTabContent
import dev.gyro.feature.player.component.sheet.tabs.TimerTabContent

/**
 * 无 Modal 容器的内容体（预览与测试可直接复用）。
 */
@Composable
fun PlayerSettingsSheetContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    initialTab: PlayerSheetRoute = PlayerSheetRoute.SETTINGS_HUB,
    onDismiss: () -> Unit = {}
) {
    val tabBackStack = remember(initialTab) { mutableStateListOf(initialTab) }
    val selectedTab = tabBackStack.lastOrNull() ?: PlayerSheetRoute.SETTINGS_HUB
    PlayerSettingsSheetContent(
        state = state,
        callbacks = callbacks,
        selectedTab = selectedTab,
        onSelectTab = { tabBackStack.add(it) },
        onNavigateBack = {
            if (tabBackStack.size > 1) {
                tabBackStack.removeAt(tabBackStack.lastIndex)
            } else {
                onDismiss()
            }
        },
        initialTab = initialTab,
        onDismiss = onDismiss
    )
}

@Composable
fun PlayerSettingsSheetContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    selectedTab: PlayerSheetRoute,
    onSelectTab: (PlayerSheetRoute) -> Unit,
    initialTab: PlayerSheetRoute = PlayerSheetRoute.SETTINGS_HUB,
    onDismiss: () -> Unit = {}
) {
    PlayerSettingsSheetContent(
        state = state,
        callbacks = callbacks,
        selectedTab = selectedTab,
        onSelectTab = onSelectTab,
        onNavigateBack = { onSelectTab(selectedTab.parent ?: PlayerSheetRoute.SETTINGS_HUB) },
        initialTab = initialTab,
        onDismiss = onDismiss
    )
}

@Composable
fun PlayerSettingsSheetContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    selectedTab: PlayerSheetRoute,
    onSelectTab: (PlayerSheetRoute) -> Unit,
    onNavigateBack: () -> Unit,
    initialTab: PlayerSheetRoute = PlayerSheetRoute.SETTINGS_HUB,
    onDismiss: () -> Unit = {}
) {
    val isRootSettings = selectedTab == PlayerSheetRoute.SETTINGS_HUB

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    ) {
        // The root hub spends no row on a title: it repeated what the sheet already is, and every
        // pixel here comes out of the video's remaining height. Sub pages keep theirs, because the
        // back arrow and the close button have no other entry point.
        if (!isRootSettings) {
            val pageTitle = stringResource(selectedTab.titleRes)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = SheetTextColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = pageTitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = SheetTextColor,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = SheetTextSecondaryColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                if (targetState == PlayerSheetRoute.SETTINGS_HUB) {
                    (slideInHorizontally(animationSpec = tween(180)) { -it } + fadeIn(animationSpec = tween(180)))
                        .togetherWith(slideOutHorizontally(animationSpec = tween(180)) { it } + fadeOut(animationSpec = tween(180)))
                } else {
                    (slideInHorizontally(animationSpec = tween(180)) { it } + fadeIn(animationSpec = tween(180)))
                        .togetherWith(slideOutHorizontally(animationSpec = tween(180)) { -it } + fadeOut(animationSpec = tween(180)))
                }
            },
            label = "SheetTabTransition"
        ) { currentTab ->
            when (currentTab) {
                PlayerSheetRoute.SETTINGS_HUB -> SettingsHubTabContent(
                    state = state,
                    callbacks = callbacks,
                    onNavigate = onSelectTab
                )
                PlayerSheetRoute.HWDEC -> HwdecSubTabContent(state, callbacks)
                PlayerSheetRoute.SHADERS -> ShadersSubTabContent(state, callbacks)
                PlayerSheetRoute.ASPECT -> AspectSubTabContent(state, callbacks)
                PlayerSheetRoute.COLOR -> ColorSubTabContent(state, callbacks)
                PlayerSheetRoute.MORE_PLAYBACK -> MorePlaybackTabContent(
                    state = state,
                    callbacks = callbacks,
                    onNavigate = onSelectTab
                )
                PlayerSheetRoute.AUDIO_TRACKS -> AudioTracksSubTabContent(state, callbacks)
                PlayerSheetRoute.AUDIO_BOOST -> AudioBoostSubTabContent(state, callbacks)
                PlayerSheetRoute.AUDIO_EFFECTS -> AudioEffectsTabContent(
                    state = state,
                    callbacks = callbacks
                )
                PlayerSheetRoute.AUDIO_DELAY -> DelaySubTabContent(
                    currentSec = state.audioDelaySec,
                    label = stringResource(R.string.sync_audio_delay),
                    onValueChange = callbacks.onSetAudioDelay
                )
                PlayerSheetRoute.SUBTITLE_TRACKS -> SubtitleTracksSubTabContent(state, callbacks)
                PlayerSheetRoute.SUBTITLE_DELAY -> DelaySubTabContent(
                    currentSec = state.subDelaySec,
                    label = stringResource(R.string.sync_sub_delay),
                    onValueChange = callbacks.onSetSubDelay
                )
                PlayerSheetRoute.SUBTITLE_STYLE -> SubtitleStyleSubTabContent(state, callbacks)
                PlayerSheetRoute.TIMER -> TimerTabContent(state, callbacks)
                PlayerSheetRoute.PLAYBACK_MODE -> PlaybackModeTabContent(state, callbacks)
                PlayerSheetRoute.SPEED -> SpeedSubTabContent(state, callbacks)
                PlayerSheetRoute.STATS_INFO -> MediaInfoStatsTabContent(state, callbacks)
                PlayerSheetRoute.PLAYLIST -> PlaylistTabContent(state, callbacks)
                PlayerSheetRoute.CHAPTERS -> ChaptersTabContent(state, callbacks)
            }
        }
    }
}
