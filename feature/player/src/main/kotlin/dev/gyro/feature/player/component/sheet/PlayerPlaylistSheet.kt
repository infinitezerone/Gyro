package dev.gyro.feature.player.component.sheet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroPillButton
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetBgColor
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.tabs.ChaptersTabContent
import dev.gyro.feature.player.component.sheet.tabs.PlaylistTabContent
import kotlin.math.roundToInt

/**
 * 独立的播放列表与选集抽屉 (PlayerPlaylistSheet)
 *
 * 专注播放内容导航（选集/连播/章节），彻底与参数设置大厅解耦：
 * - 全屏半透明蒙层覆盖，点击蒙层立即收起；
 * - 竖屏：底部滑出紧凑抽屉，圆角 22dp，跟手下滑手势关闭；
 * - 横屏：右侧滑出侧边抽屉，宽 360dp，跟手右滑手势关闭；
 * - 拦截系统返回键，优先关闭抽屉。
 */
@OptIn(ExperimentalAnimationApi::class)
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
        mutableStateOf(if (state.playlist.isNotEmpty()) PlayerSheetTab.PLAYLIST else PlayerSheetTab.CHAPTERS)
    }

    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp

    // 拖拽手势状态：横屏右滑距离，竖屏下滑距离
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 70.dp.toPx() }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    // 拦截返回键
    BackHandler(enabled = true) {
        onDismiss()
    }

    // 全屏遮罩与手势容器
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // 全屏半透明蒙层（点击原地关闭，不随抽屉位移）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.50f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )

        // 进出场平滑位移动画
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

        // 跟手拖拽关闭手势
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

        // 抽屉宿主根据横竖屏分别停靠右侧或底部
        val sheetModifier = if (isLandscape) {
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(360.dp)
                .statusBarsPadding()
                .navigationBarsPadding()
                .background(
                    color = SheetBgColor.copy(alpha = 0.98f),
                    shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
                )
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.65f)
                .navigationBarsPadding()
                .background(
                    color = SheetBgColor.copy(alpha = 0.98f),
                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                )
        }

        Box(
            modifier = Modifier
                .then(sheetModifier)
                .then(animationModifier)
                .then(dragModifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* 阻断点击透传到背后的蒙层 */ }
                )
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
}

@Composable
private fun PlaylistSheetContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks,
    selectedTab: PlayerSheetTab,
    onSelectTab: (PlayerSheetTab) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        // 顶部导航栏：选集 (X) / 章节 (Y) 切换 + 右侧关闭按钮
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
                    onClick = { onSelectTab(PlayerSheetTab.PLAYLIST) },
                    containerColor = if (selectedTab == PlayerSheetTab.PLAYLIST) {
                        SheetAccentBlue.copy(alpha = 0.22f)
                    } else {
                        SheetCardAltColor
                    },
                    contentColor = if (selectedTab == PlayerSheetTab.PLAYLIST) {
                        SheetAccentBlue
                    } else {
                        SheetTextColor
                    }
                )

                if (state.chapters.isNotEmpty()) {
                    GyroPillButton(
                        text = "${stringResource(R.string.sheet_tab_chapters)} (${state.chapters.size})",
                        onClick = { onSelectTab(PlayerSheetTab.CHAPTERS) },
                        containerColor = if (selectedTab == PlayerSheetTab.CHAPTERS) {
                            SheetAccentBlue.copy(alpha = 0.22f)
                        } else {
                            SheetCardAltColor
                        },
                        contentColor = if (selectedTab == PlayerSheetTab.CHAPTERS) {
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

        Spacer(modifier = Modifier.height(4.dp))

        // 选集 / 章节内容区域
        Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            if (selectedTab == PlayerSheetTab.CHAPTERS) {
                ChaptersTabContent(state, callbacks)
            } else {
                PlaylistTabContent(state, callbacks)
            }
        }
    }
}
