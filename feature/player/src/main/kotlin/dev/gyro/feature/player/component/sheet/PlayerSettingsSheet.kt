package dev.gyro.feature.player.component.sheet

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
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
import dev.gyro.feature.player.component.sheet.tabs.AudioBoostSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.AudioTabContent
import dev.gyro.feature.player.component.sheet.tabs.AudioTracksSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.AspectSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.AspectTabContent
import dev.gyro.feature.player.component.sheet.tabs.ColorSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.DelaySubTabContent
import dev.gyro.feature.player.component.sheet.tabs.HdrToneMappingSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.HwdecSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.ShadersSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.SpeedSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.SubtitleStyleSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.SubtitleTracksSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.VideoScaleSubTabContent
import dev.gyro.feature.player.component.sheet.tabs.VideoTabContent
import dev.gyro.feature.player.component.sheet.tabs.ChaptersTabContent
import dev.gyro.feature.player.component.sheet.tabs.MediaInfoStatsTabContent
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
 * 横屏为高质感右侧抽屉，竖屏为紧凑底部抽屉；手势支持右滑/下滑跟手关闭。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun PlayerSettingsSheet(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    initialTab: PlayerSheetTab = PlayerSheetTab.SETTINGS_HUB,
    isLandscape: Boolean = false,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onDismiss: () -> Unit
) {
    var selectedTab by remember(initialTab) { mutableStateOf(initialTab) }
    val isPlaylistScene = initialTab == PlayerSheetTab.PLAYLIST || initialTab == PlayerSheetTab.CHAPTERS
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp

    // 拖拽手势状态：横屏右滑距离，竖屏下滑距离
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 70.dp.toPx() }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    // 竖屏下抽屉钉在底部，高度限制提升至 65%；横屏下在右侧以侧边栏展示，宽度为 360dp 提升平铺利用率
    val sheetModifier = if (isLandscape) {
        Modifier
            .fillMaxHeight()
            .width(360.dp)
            .statusBarsPadding()
            .navigationBarsPadding()
    } else {
        Modifier
            .fillMaxWidth()
            .heightIn(max = screenHeight * 0.65f)
            .navigationBarsPadding()
    }

    // 物理返回按键/返回手势拦截：二级页退回主设置，主设置直接收起抽屉
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
        // 全屏半透明蒙层（原地淡入淡出，绝对不随抽屉平移，避免横扫全屏 Bug）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )

        val animationModifier = if (animatedVisibilityScope != null) {
            with(animatedVisibilityScope) {
                Modifier.animateEnterExit(
                    enter = if (isLandscape) {
                        slideInHorizontally(animationSpec = tween(220)) { it }
                    } else {
                        slideInVertically(animationSpec = tween(220)) { it }
                    },
                    exit = if (isLandscape) {
                        slideOutHorizontally(animationSpec = tween(180)) { it }
                    } else {
                        slideOutVertically(animationSpec = tween(180)) { it }
                    }
                )
            }
        } else {
            Modifier
        }

        val dragModifier = Modifier
            .offset {
                if (isLandscape) {
                    IntOffset(dragOffsetX.roundToInt(), 0)
                } else {
                    IntOffset(0, dragOffsetY.roundToInt())
                }
            }
            .pointerInput(isLandscape) {
                if (isLandscape) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dragOffsetX > dismissThresholdPx) {
                                onDismiss()
                            }
                            dragOffsetX = 0f
                        },
                        onDragCancel = { dragOffsetX = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            if (dragAmount > 0 || dragOffsetX > 0) {
                                change.consume()
                                dragOffsetX = (dragOffsetX + dragAmount).coerceAtLeast(0f)
                            }
                        }
                    )
                } else {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragOffsetY > dismissThresholdPx) {
                                onDismiss()
                            }
                            dragOffsetY = 0f
                        },
                        onDragCancel = { dragOffsetY = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            if (dragAmount > 0 || dragOffsetY > 0) {
                                change.consume()
                                dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                            }
                        }
                    )
                }
            }

        Surface(
            modifier = sheetModifier
                .align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(animationModifier)
                .then(dragModifier)
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
                // 顶部小横条拖拽指示把手 (仅竖屏展示，支持拖动手势下划收起)
                if (!isLandscape) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .background(
                                    color = Color.White.copy(alpha = 0.25f),
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
                    initialTab = initialTab,
                    onDismiss = onDismiss
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
    initialTab: PlayerSheetTab = PlayerSheetTab.SETTINGS_HUB,
    onDismiss: () -> Unit = {}
) {
    var selectedTab by remember(initialTab) { mutableStateOf(initialTab) }
    PlayerSettingsSheetContent(
        state = state,
        callbacks = callbacks,
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        initialTab = initialTab,
        onDismiss = onDismiss
    )
}

@Composable
fun PlayerSettingsSheetContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    selectedTab: PlayerSheetTab,
    onSelectTab: (PlayerSheetTab) -> Unit,
    initialTab: PlayerSheetTab = PlayerSheetTab.SETTINGS_HUB,
    onDismiss: () -> Unit = {}
) {
    val isRootSettings = selectedTab == PlayerSheetTab.SETTINGS_HUB

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    ) {
        // 顶部导航栏：根设置显示标题与关闭按钮；二级设置显示返回键、当前二级标题与关闭按钮
        if (isRootSettings) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = SheetTextColor
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
        } else {
            val pageTitle = when (selectedTab) {
                PlayerSheetTab.HWDEC -> stringResource(R.string.stats_decoder)
                PlayerSheetTab.SHADERS -> stringResource(R.string.page_shaders)
                PlayerSheetTab.ASPECT -> stringResource(R.string.aspect_title)
                PlayerSheetTab.COLOR -> stringResource(R.string.settings_color_calibration)
                PlayerSheetTab.HDR_TONE_MAPPING -> stringResource(R.string.settings_hdr_tone_mapping)
                PlayerSheetTab.VIDEO_SCALE -> stringResource(R.string.settings_video_scale)
                PlayerSheetTab.AUDIO_TRACKS, PlayerSheetTab.AUDIO -> stringResource(R.string.settings_audio_track)
                PlayerSheetTab.AUDIO_BOOST -> stringResource(R.string.settings_audio_boost)
                PlayerSheetTab.AUDIO_DELAY -> stringResource(R.string.sync_audio_delay)
                PlayerSheetTab.SUBTITLE_TRACKS, PlayerSheetTab.SUBTITLES -> stringResource(R.string.settings_subtitle_track)
                PlayerSheetTab.SUBTITLE_DELAY -> stringResource(R.string.sync_sub_delay)
                PlayerSheetTab.SUBTITLE_STYLE -> stringResource(R.string.settings_subtitle_style)
                PlayerSheetTab.SPEED -> stringResource(R.string.settings_playback_speed)
                PlayerSheetTab.STATS_INFO, PlayerSheetTab.STATS, PlayerSheetTab.INFO -> stringResource(R.string.page_stats_info)
                PlayerSheetTab.PLAYLIST -> stringResource(R.string.page_playlist)
                PlayerSheetTab.CHAPTERS -> stringResource(R.string.page_chapters)
                PlayerSheetTab.VIDEO -> stringResource(R.string.page_video_settings)
                PlayerSheetTab.SYNC -> stringResource(R.string.page_av_sync)
                PlayerSheetTab.AUDIOVISUAL -> stringResource(R.string.page_audiovisual)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
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

        when (selectedTab) {
            PlayerSheetTab.SETTINGS_HUB -> SettingsHubTabContent(
                state = state,
                callbacks = callbacks,
                onNavigate = onSelectTab
            )
            PlayerSheetTab.HWDEC -> HwdecSubTabContent(state, callbacks)
            PlayerSheetTab.SHADERS -> ShadersSubTabContent(state, callbacks)
            PlayerSheetTab.ASPECT -> AspectSubTabContent(state, callbacks)
            PlayerSheetTab.COLOR -> ColorSubTabContent(state, callbacks)
            PlayerSheetTab.HDR_TONE_MAPPING -> HdrToneMappingSubTabContent(state, callbacks)
            PlayerSheetTab.VIDEO_SCALE -> VideoScaleSubTabContent(state, callbacks)
            PlayerSheetTab.AUDIO_TRACKS, PlayerSheetTab.AUDIO -> AudioTracksSubTabContent(state, callbacks)
            PlayerSheetTab.AUDIO_BOOST -> AudioBoostSubTabContent(state, callbacks)
            PlayerSheetTab.AUDIO_DELAY -> DelaySubTabContent(
                currentSec = state.audioDelaySec,
                label = stringResource(R.string.sync_audio_delay),
                onValueChange = callbacks.onSetAudioDelay
            )
            PlayerSheetTab.SUBTITLE_TRACKS, PlayerSheetTab.SUBTITLES -> SubtitleTracksSubTabContent(state, callbacks)
            PlayerSheetTab.SUBTITLE_DELAY -> DelaySubTabContent(
                currentSec = state.subDelaySec,
                label = stringResource(R.string.sync_sub_delay),
                onValueChange = callbacks.onSetSubDelay
            )
            PlayerSheetTab.SUBTITLE_STYLE -> SubtitleStyleSubTabContent(state, callbacks)
            PlayerSheetTab.SPEED -> SpeedSubTabContent(state, callbacks)
            PlayerSheetTab.STATS_INFO, PlayerSheetTab.STATS, PlayerSheetTab.INFO -> MediaInfoStatsTabContent(state, callbacks)
            PlayerSheetTab.PLAYLIST -> PlaylistTabContent(state, callbacks)
            PlayerSheetTab.CHAPTERS -> ChaptersTabContent(state, callbacks)
            PlayerSheetTab.VIDEO -> VideoTabContent(state, callbacks)
            PlayerSheetTab.SYNC -> SyncTabContent(state, callbacks)
            PlayerSheetTab.AUDIOVISUAL -> VideoTabContent(state, callbacks)
        }
    }
}
