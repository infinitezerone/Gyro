package dev.gyro.feature.player.component.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroPillButton
import dev.gyro.feature.player.component.sheet.common.SheetBgColor
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.tabs.AudioTabContent
import dev.gyro.feature.player.component.sheet.tabs.AspectTabContent
import dev.gyro.feature.player.component.sheet.tabs.AudiovisualTabContent
import dev.gyro.feature.player.component.sheet.tabs.ChaptersTabContent
import dev.gyro.feature.player.component.sheet.tabs.MediaInfoTabContent
import dev.gyro.feature.player.component.sheet.tabs.PlaylistTabContent
import dev.gyro.feature.player.component.sheet.tabs.SettingsHubTabContent
import dev.gyro.feature.player.component.sheet.tabs.ShadersTabContent
import dev.gyro.feature.player.component.sheet.tabs.SpeedTabContent
import dev.gyro.feature.player.component.sheet.tabs.StatsTabContent
import dev.gyro.feature.player.component.sheet.tabs.SubtitlesTabContent
import dev.gyro.feature.player.component.sheet.tabs.SyncTabContent

private val SheetContainerColor = SheetBgColor
private val SheetContentColor = SheetTextColor
private val SheetAccentBlue = dev.gyro.feature.player.component.sheet.common.SheetAccentBlue

/**
 * 播放器设置 Bottom Sheet 宿主 (PlayerSettingsSheet)。
 * ModalBottomSheet 容器 + 三种头部模式 + tab 路由；内容一律读取 [state]、回传 [callbacks]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    initialTab: PlayerSheetTab = PlayerSheetTab.SUBTITLES,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetContainerColor,
        contentColor = SheetContentColor
    ) {
        PlayerSettingsSheetContent(
            state = state,
            callbacks = callbacks,
            initialTab = initialTab
        )
    }
}

/**
 * 无 Modal 容器的内容体（预览与测试可直接复用）。
 */
@Composable
fun PlayerSettingsSheetContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    initialTab: PlayerSheetTab = PlayerSheetTab.SUBTITLES
) {
    var selectedTab by remember { mutableStateOf(initialTab) }

    val isPlaylistScene = initialTab == PlayerSheetTab.PLAYLIST || initialTab == PlayerSheetTab.CHAPTERS

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp)
    ) {
        when {
            isPlaylistScene -> {
                // A. 选集/章节场景：极简双胶囊导航
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GyroPillButton(
                        text = "${stringResource(R.string.sheet_tab_playlist)} (${state.playlist.size})",
                        onClick = { selectedTab = PlayerSheetTab.PLAYLIST },
                        containerColor = if (selectedTab == PlayerSheetTab.PLAYLIST) {
                            SheetAccentBlue.copy(alpha = 0.25f)
                        } else {
                            dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
                        },
                        contentColor = if (selectedTab == PlayerSheetTab.PLAYLIST) {
                            SheetAccentBlue
                        } else {
                            dev.gyro.feature.player.component.sheet.common.SheetTextColor
                        }
                    )
                    if (state.chapters.isNotEmpty()) {
                        GyroPillButton(
                            text = "${stringResource(R.string.sheet_tab_chapters)} (${state.chapters.size})",
                            onClick = { selectedTab = PlayerSheetTab.CHAPTERS },
                            containerColor = if (selectedTab == PlayerSheetTab.CHAPTERS) {
                                SheetAccentBlue.copy(alpha = 0.25f)
                            } else {
                                dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
                            },
                            contentColor = if (selectedTab == PlayerSheetTab.CHAPTERS) {
                                SheetAccentBlue
                            } else {
                                dev.gyro.feature.player.component.sheet.common.SheetTextColor
                            }
                        )
                    }
                }
            }
            selectedTab == PlayerSheetTab.SETTINGS_HUB -> {
                // B. 设置总览大厅场景
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = SheetTextColor
                    )
                    IconButton(
                        onClick = callbacks.onOpenFullSettings,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                            tint = SheetAccentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            else -> {
                // C. 各子设置场景：清晰返回导航头
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { selectedTab = PlayerSheetTab.SETTINGS_HUB },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = SheetTextColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(selectedTab.titleRes),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = SheetTextColor
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = callbacks.onOpenFullSettings,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                            tint = dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (selectedTab) {
            PlayerSheetTab.SETTINGS_HUB -> SettingsHubTabContent(
                state = state,
                onNavigate = { selectedTab = it }
            )
            PlayerSheetTab.PLAYLIST -> PlaylistTabContent(state, callbacks)
            PlayerSheetTab.CHAPTERS -> ChaptersTabContent(state, callbacks)
            PlayerSheetTab.SUBTITLES -> SubtitlesTabContent(state, callbacks)
            PlayerSheetTab.AUDIO -> AudioTabContent(state, callbacks)
            PlayerSheetTab.SHADERS -> ShadersTabContent(state, callbacks)
            PlayerSheetTab.AUDIOVISUAL -> AudiovisualTabContent(state, callbacks)
            PlayerSheetTab.SYNC -> SyncTabContent(state, callbacks)
            PlayerSheetTab.SPEED -> SpeedTabContent(state, callbacks)
            PlayerSheetTab.ASPECT -> AspectTabContent(state, callbacks)
            PlayerSheetTab.STATS -> StatsTabContent(state, callbacks)
            PlayerSheetTab.INFO -> MediaInfoTabContent(state)
        }
    }
}
