package dev.gyro.feature.player.component.sheet

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.animate
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.gyro.feature.player.component.sheet.common.SheetBgColor
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 抽屉外壳：蒙层、横竖屏尺寸、拖拽跟手、下拉/右滑关闭、高度测量。
 *
 * 内容与路由状态留在调用方，这个 composable 只负责“怎么把一个面板拖出来 / 收回去”。
 */
@Composable
internal fun SheetDragScaffold(
    isLandscape: Boolean,
    heightFraction: Float = 0.45f,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    onSheetHeightChanged: (Int) -> Unit,
    onSheetDragOffsetChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp

    // 拖拽手势状态：横屏右滑距离，竖屏下滑距离
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 70.dp.toPx() }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val dragScope = rememberCoroutineScope()

    // Scrolling the content to the top and pulling further has to keep dragging the sheet itself,
    // otherwise the list swallows the rest of the dismiss gesture and the sheet cannot be closed
    // from content that is already at its top.
    //
    // The two callbacks split the gesture by ownership: onPostScroll picks up the pull that the
    // content could not consume (list already at its top), and once the sheet has started moving
    // onPreScroll claims every further downward pull so the list stops scrolling underneath a sheet
    // that is being dragged away - otherwise both move at once after the handover.
    val verticalDismissConnection = remember(isLandscape, dismissThresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isLandscape || dragOffsetY <= 0f) return Offset.Zero
                // Momentum belongs to the list. Only a finger moves the sheet.
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                // Already dragging: the sheet owns the vertical axis until it is back at rest.
                // Pulling up first returns the sheet to open, and only the remainder reaches the list.
                val delta = if (available.y > 0f) available.y else (-dragOffsetY).coerceAtLeast(available.y)
                dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f)
                onSheetDragOffsetChanged(dragOffsetY)
                return Offset(0f, delta)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (isLandscape || available.y <= 0f) return Offset.Zero
                // Same rule for the handover: a fling that runs out of list must not tug the sheet
                // down by itself, otherwise tossing the list to the top ends in a half-dismissed
                // sheet. The sheet starts following only when the finger keeps pulling.
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                dragOffsetY = (dragOffsetY + available.y).coerceAtLeast(0f)
                onSheetDragOffsetChanged(dragOffsetY)
                return Offset(0f, available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (isLandscape || dragOffsetY <= 0f) return Velocity.Zero
                if (dragOffsetY > dismissThresholdPx) {
                    onDismiss()
                } else {
                    val from = dragOffsetY
                    animate(
                        initialValue = from,
                        targetValue = 0f,
                        animationSpec = tween(200),
                    ) { value, _ ->
                        dragOffsetY = value
                        onSheetDragOffsetChanged(value)
                    }
                }
                return Velocity.Zero
            }
        }
    }

    // 竖屏下抽屉钉在底部，高度上限 45%：再高就会盖住画面，而视频在这之上还要完整放下。
    // 横屏下在右侧以侧边栏展示，宽度为 360dp 提升平铺利用率
    val sheetModifier = if (isLandscape) {
        Modifier
            .fillMaxHeight()
            .width(360.dp)
            .statusBarsPadding()
            .navigationBarsPadding()
    } else {
        Modifier
            .fillMaxWidth()
            .heightIn(max = screenHeight * heightFraction)
            .navigationBarsPadding()
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
            .nestedScroll(verticalDismissConnection)
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
                }
                // The header and other non-scrolling parts of the sheet never reach the nested
                // scroll, so they keep their own detector. It cannot double-count with
                // verticalDismissConnection: a scrollable child consumes the pointer events first,
                // and a consumed drag never reaches this detector.
                else {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragOffsetY > dismissThresholdPx) {
                                // Keep both offsets: resetting here snaps the sheet back to fully
                                // open for a frame before the slide-out starts, reading as a bounce.
                                onDismiss()
                            } else {
                                val from = dragOffsetY
                                dragScope.launch {
                                    animate(
                                        initialValue = from,
                                        targetValue = 0f,
                                        animationSpec = tween(200),
                                    ) { value, _ ->
                                        dragOffsetY = value
                                        onSheetDragOffsetChanged(value)
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            onSheetDragOffsetChanged(0f)
                            dragOffsetY = 0f
                        },
                        onVerticalDrag = { change, dragAmount ->
                            if (dragAmount > 0 || dragOffsetY > 0) {
                                change.consume()
                                dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                                onSheetDragOffsetChanged(dragOffsetY)
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
                .onSizeChanged { if (!isLandscape) onSheetHeightChanged(it.height) }
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
            color = if (isLandscape) {
                SheetBgColor.copy(alpha = 0.85f)
            } else {
                SheetBgColor
            },
            contentColor = SheetTextColor,
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
                content()
            }
        }
    }
}
