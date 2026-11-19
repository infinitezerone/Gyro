package dev.gyro.feature.player.component.sheet

import androidx.compose.animation.AnimatedVisibilityScope
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.gyro.feature.player.component.sheet.common.SheetBgColor
import kotlin.math.roundToInt

/**
 * 选集/章节抽屉的外壳：蒙层、横竖屏停靠、跟手拖拽关闭。
 */
@Composable
internal fun PlaylistSheetScaffold(
    isLandscape: Boolean,
    onDismiss: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp

    // 拖拽手势状态：横屏右滑距离，竖屏下滑距离
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 70.dp.toPx() }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

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
                    color = SheetBgColor.copy(alpha = 0.85f),
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
            content()
        }
    }
}
