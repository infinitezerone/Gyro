package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.runtime.Composable
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState

/**
 * 视听调节（已拆分为 VideoTab 与 AudioTab，保留本入口转发至 VideoTabContent 以确保兼容）
 */
@Composable
internal fun AudiovisualTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    VideoTabContent(state, callbacks)
}
