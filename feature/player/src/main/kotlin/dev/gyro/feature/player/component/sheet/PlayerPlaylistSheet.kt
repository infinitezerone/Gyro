package dev.gyro.feature.player.component.sheet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroPillButton
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.tabs.ChaptersTabContent
import dev.gyro.feature.player.component.sheet.tabs.PlaylistTabContent

/**
 * 独立的播放列表与选集抽屉 (PlayerPlaylistSheet)
 *
 * 专注播放内容导航（选集/连播/章节），彻底与参数设置大厅解耦。
 */
@Composable
fun PlayerPlaylistSheet(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    isLandscape: Boolean,
    onDismiss: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember(state.playlist.size, state.chapters.size) {
        mutableStateOf(if (state.playlist.isNotEmpty()) PlayerSheetRoute.PLAYLIST else PlayerSheetRoute.CHAPTERS)
    }

    // 拦截返回键
    BackHandler(enabled = true) {
        onDismiss()
    }

    PlaylistSheetScaffold(
        isLandscape = isLandscape,
        onDismiss = onDismiss,
        animatedVisibilityScope = animatedVisibilityScope,
        modifier = modifier
    ) {
        PlaylistSheetContent(
            state = state,
            callbacks = callbacks,
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun PlaylistSheetContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    selectedTab: PlayerSheetRoute,
    onSelectTab: (PlayerSheetRoute) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        PlaylistSheetHeader(
            state = state,
            selectedTab = selectedTab,
            onSelectTab = onSelectTab,
            onDismiss = onDismiss
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 选集 / 章节内容区域
        Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            if (selectedTab == PlayerSheetRoute.CHAPTERS) {
                ChaptersTabContent(state, callbacks)
            } else {
                PlaylistTabContent(state, callbacks)
            }
        }
    }
}

@Composable
private fun PlaylistSheetHeader(
    state: PlayerSheetUiState,
    selectedTab: PlayerSheetRoute,
    onSelectTab: (PlayerSheetRoute) -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val playlistTitle = if (state.playlist.isNotEmpty()) {
                "${stringResource(R.string.sheet_tab_playlist)} (${state.playlist.size})"
            } else {
                stringResource(R.string.sheet_tab_playlist)
            }

            GyroPillButton(
                text = playlistTitle,
                onClick = { onSelectTab(PlayerSheetRoute.PLAYLIST) },
                containerColor = if (selectedTab == PlayerSheetRoute.PLAYLIST) {
                    SheetAccentBlue.copy(alpha = 0.22f)
                } else {
                    SheetCardAltColor
                },
                contentColor = if (selectedTab == PlayerSheetRoute.PLAYLIST) {
                    SheetAccentBlue
                } else {
                    SheetTextColor
                }
            )

            if (state.chapters.isNotEmpty()) {
                GyroPillButton(
                    text = "${stringResource(R.string.sheet_tab_chapters)} (${state.chapters.size})",
                    onClick = { onSelectTab(PlayerSheetRoute.CHAPTERS) },
                    containerColor = if (selectedTab == PlayerSheetRoute.CHAPTERS) {
                        SheetAccentBlue.copy(alpha = 0.22f)
                    } else {
                        SheetCardAltColor
                    },
                    contentColor = if (selectedTab == PlayerSheetRoute.CHAPTERS) {
                        SheetAccentBlue
                    } else {
                        SheetTextColor
                    }
                )
            }
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.action_cancel),
                tint = SheetTextSecondaryColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
