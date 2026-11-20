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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.icon.GyroIcons
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.tabs.AudioSettingsTabContent
import dev.gyro.feature.player.component.sheet.tabs.ChaptersTabContent
import dev.gyro.feature.player.component.sheet.tabs.MediaInfoStatsTabContent
import dev.gyro.feature.player.component.sheet.tabs.PlaylistTabContent
import dev.gyro.feature.player.component.sheet.tabs.SettingsHubTabContent
import dev.gyro.feature.player.component.sheet.tabs.SpeedSettingsTabContent
import dev.gyro.feature.player.component.sheet.tabs.SubtitleSettingsTabContent
import dev.gyro.feature.player.component.sheet.tabs.VideoSettingsTabContent

private data class SheetNavFrame(
    val route: PlayerSheetRoute,
    val depth: Int,
)

/**
 * 无 Modal 容器的抽屉内容体（预览与测试可直接复用）。
 */
@Composable
fun PlayerSettingsSheetContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    initialTab: PlayerSheetRoute = PlayerSheetRoute.SETTINGS_HUB,
    onDismiss: () -> Unit = {},
) {
    val nav = rememberSheetNavState(initialTab)
    PlayerSettingsSheetContent(
        state = state,
        callbacks = callbacks,
        selectedTab = nav.current,
        backStackDepth = nav.depth,
        canNavigateBack = nav.canGoBack,
        onSelectTab = nav::push,
        onNavigateBack = { if (!nav.goBack()) onDismiss() },
        onDismiss = onDismiss,
    )
}

@Composable
fun PlayerSettingsSheetContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    selectedTab: PlayerSheetRoute,
    onSelectTab: (PlayerSheetRoute) -> Unit,
    onNavigateBack: () -> Unit,
    backStackDepth: Int = 1,
    canNavigateBack: Boolean = backStackDepth > 1,
    onDismiss: () -> Unit = {},
) {
    val isRootSettings = selectedTab == PlayerSheetRoute.SETTINGS_HUB

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        if (!isRootSettings) {
            SheetSubPageHeader(
                title = stringResource(selectedTab.titleRes),
                canNavigateBack = canNavigateBack,
                onNavigateBack = onNavigateBack,
                onDismiss = onDismiss,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        AnimatedContent(
            targetState = SheetNavFrame(route = selectedTab, depth = backStackDepth),
            transitionSpec = {
                if (isBackTransition(initialState.depth, targetState.depth)) {
                    (slideInHorizontally(animationSpec = tween(180)) { -it / 3 } + fadeIn(animationSpec = tween(180)))
                        .togetherWith(slideOutHorizontally(animationSpec = tween(160)) { it } + fadeOut(animationSpec = tween(140)))
                } else {
                    (slideInHorizontally(animationSpec = tween(180)) { it } + fadeIn(animationSpec = tween(180)))
                        .togetherWith(slideOutHorizontally(animationSpec = tween(160)) { -it / 3 } + fadeOut(animationSpec = tween(140)))
                }
            },
            label = "SheetTabTransition",
        ) { frame ->
            when (frame.route) {
                PlayerSheetRoute.SETTINGS_HUB -> SettingsHubTabContent(
                    state = state,
                    callbacks = callbacks,
                    onNavigate = onSelectTab,
                )
                PlayerSheetRoute.VIDEO -> VideoSettingsTabContent(state, callbacks)
                PlayerSheetRoute.AUDIO -> AudioSettingsTabContent(state, callbacks)
                PlayerSheetRoute.SUBTITLES -> SubtitleSettingsTabContent(state, callbacks)
                PlayerSheetRoute.SPEED -> SpeedSettingsTabContent(state, callbacks)
                PlayerSheetRoute.STATS_INFO -> MediaInfoStatsTabContent(state, callbacks)
                PlayerSheetRoute.PLAYLIST -> PlaylistTabContent(state, callbacks)
                PlayerSheetRoute.CHAPTERS -> ChaptersTabContent(state, callbacks)
            }
        }
    }
}

@Composable
private fun SheetSubPageHeader(
    title: String,
    canNavigateBack: Boolean,
    onNavigateBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (canNavigateBack) 8.dp else 16.dp,
                end = 8.dp,
                top = 6.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canNavigateBack) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = GyroIcons.Back,
                    contentDescription = stringResource(R.string.action_back),
                    tint = SheetTextColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = SheetTextColor,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (canNavigateBack) 4.dp else 0.dp),
        )

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = GyroIcons.Close,
                contentDescription = stringResource(R.string.action_cancel),
                tint = SheetTextSecondaryColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
