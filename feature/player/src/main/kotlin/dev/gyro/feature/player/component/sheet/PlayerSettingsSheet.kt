package dev.gyro.feature.player.component.sheet

import androidx.compose.runtime.Composable

private const val PORTRAIT_SHEET_HEIGHT_FRACTION = 0.62f

/**
 * 播放器设置抽屉宿主 (PlayerSettingsSheet)。
 * 横屏为右侧抽屉，竖屏为底部抽屉；拖拽/动画外壳统一由 [AnchoredSheetScaffold] 承担。
 * 内部页的返回栈由 [SheetNavState] 持有，返回键处理统一在 [dev.gyro.feature.player.PlayerSheetsHost]。
 */
@Composable
fun PlayerSettingsSheet(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    nav: SheetNavState,
    visible: Boolean = true,
    isLandscape: Boolean = false,
    onBindVisibleHeightProvider: (() -> Float) -> Unit = {},
    onUnbindVisibleHeightProvider: (() -> Float) -> Unit = {},
    onDismiss: () -> Unit,
    onHidden: () -> Unit = {},
) {
    AnchoredSheetScaffold(
        visible = visible,
        isLandscape = isLandscape,
        heightFraction = PORTRAIT_SHEET_HEIGHT_FRACTION,
        onBindVisibleHeightProvider = onBindVisibleHeightProvider,
        onUnbindVisibleHeightProvider = onUnbindVisibleHeightProvider,
        onDismiss = onDismiss,
        onHidden = onHidden,
    ) {
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
}
