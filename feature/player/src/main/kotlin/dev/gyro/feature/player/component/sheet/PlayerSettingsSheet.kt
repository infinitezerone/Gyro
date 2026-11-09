package dev.gyro.feature.player.component.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalConfiguration
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
@Composable
fun PlayerSettingsSheet(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    initialTab: PlayerSheetTab = PlayerSheetTab.SUBTITLES,
    isLandscape: Boolean = false,
    onDismiss: () -> Unit
) {
    var selectedTab by remember(initialTab) { mutableStateOf(initialTab) }
    val isPlaylistScene = initialTab == PlayerSheetTab.PLAYLIST || initialTab == PlayerSheetTab.CHAPTERS
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp

    // 竖屏下抽屉严格钉在屏幕底部（Alignment.BottomCenter），高度限制为屏幕的 52%；横屏下在右侧以侧边栏展示，宽度限制为 380dp
    val sheetModifier = if (isLandscape) {
        Modifier
            .fillMaxHeight()
            .widthIn(max = 380.dp)
            .statusBarsPadding()
            .navigationBarsPadding()
    } else {
        Modifier
            .fillMaxWidth()
            .heightIn(max = screenHeight * 0.52f)
            .navigationBarsPadding()
    }

    // 物理返回按键/返回手势拦截：若处于二级设置页，优先返回设置大厅；若在设置大厅或单场景，收起抽屉
    BackHandler(enabled = true) {
        if (selectedTab != PlayerSheetTab.SETTINGS_HUB && !isPlaylistScene) {
            selectedTab = PlayerSheetTab.SETTINGS_HUB
        } else {
            onDismiss()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 半透明微遮罩，点击上半部视频空白区域立刻收起抽屉
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.20f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )

        Surface(
            modifier = sheetModifier
                .align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = if (isLandscape) {
                RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            } else {
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            },
            color = SheetContainerColor,
            contentColor = SheetContentColor,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // 顶部小横条拖拽指示把手 (仅竖屏展示)
                if (!isLandscape) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .background(
                                    color = Color.Gray.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
                PlayerSettingsSheetContent(
                    state = state,
                    callbacks = callbacks,
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    initialTab = initialTab
                )
            }
        }
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
    var selectedTab by remember(initialTab) { mutableStateOf(initialTab) }
    PlayerSettingsSheetContent(
        state = state,
        callbacks = callbacks,
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        initialTab = initialTab
    )
}

@Composable
fun PlayerSettingsSheetContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    selectedTab: PlayerSheetTab,
    onSelectTab: (PlayerSheetTab) -> Unit,
    initialTab: PlayerSheetTab = PlayerSheetTab.SUBTITLES
) {
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
                        onClick = { onSelectTab(PlayerSheetTab.PLAYLIST) },
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
                            onClick = { onSelectTab(PlayerSheetTab.CHAPTERS) },
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
                        .padding(start = 24.dp, end = 20.dp, top = 6.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = SheetTextColor
                    )
                    dev.gyro.feature.player.component.sheet.common.FilledTonalAction(
                        onClick = callbacks.onOpenFullSettings,
                        containerColor = SheetCardAltColor,
                        contentColor = SheetAccentBlue,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "全局设置",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            else -> {
                // C. 各子设置场景：清晰返回导航头 + 标题 + 全局设置入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onSelectTab(PlayerSheetTab.SETTINGS_HUB) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = SheetTextColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(selectedTab.titleRes),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = SheetTextColor
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    dev.gyro.feature.player.component.sheet.common.FilledTonalAction(
                        onClick = callbacks.onOpenFullSettings,
                        containerColor = SheetCardAltColor,
                        contentColor = SheetTextSecondaryColor,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "全局设置",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (selectedTab) {
            PlayerSheetTab.SETTINGS_HUB -> SettingsHubTabContent(
                state = state,
                onNavigate = onSelectTab
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
