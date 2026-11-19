package dev.gyro.feature.player.component.sheet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/**
 * 播放器设置 Bottom Sheet 宿主 (PlayerSettingsSheet)。
 * 横屏为右侧抽屉，竖屏为底部抽屉；拖拽/关闭外壳在 [SheetDragScaffold]。
 */
@Composable
fun PlayerSettingsSheet(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    initialTab: PlayerSheetRoute = PlayerSheetRoute.SETTINGS_HUB,
    isLandscape: Boolean = false,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onSheetHeightChanged: (Int) -> Unit = {},
    onSheetDragOffsetChanged: (Float) -> Unit = {},
    onDismiss: () -> Unit
) {
    val tabBackStack = remember(initialTab) { mutableStateListOf(initialTab) }
    val selectedTab = tabBackStack.lastOrNull() ?: PlayerSheetRoute.SETTINGS_HUB
    // 根页保持紧凑；长列表子页给更高上限，避免用户在小抽屉里反复滚动。
    val heightFraction = when (selectedTab) {
        PlayerSheetRoute.SETTINGS_HUB,
        PlayerSheetRoute.PLAYLIST,
        PlayerSheetRoute.CHAPTERS -> 0.45f
        PlayerSheetRoute.MORE_PLAYBACK,
        PlayerSheetRoute.AUDIO_EFFECTS,
        PlayerSheetRoute.SUBTITLE_STYLE -> 0.65f
        else -> 0.55f
    }

    // 物理返回按键/返回手势拦截：多级返回逐级弹出，最后一级收起抽屉
    BackHandler(enabled = true) {
        if (tabBackStack.size > 1) {
            tabBackStack.removeAt(tabBackStack.lastIndex)
        } else {
            onDismiss()
        }
    }

    SheetDragScaffold(
        isLandscape = isLandscape,
        heightFraction = heightFraction,
        animatedVisibilityScope = animatedVisibilityScope,
        onSheetHeightChanged = onSheetHeightChanged,
        onSheetDragOffsetChanged = onSheetDragOffsetChanged,
        onDismiss = onDismiss
    ) {
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
}
