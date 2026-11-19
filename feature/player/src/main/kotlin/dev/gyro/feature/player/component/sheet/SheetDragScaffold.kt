package dev.gyro.feature.player.component.sheet

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 抽屉的两个锚点：0 为完全展开，Closed 处的位移即抽屉自身的宽（横屏）或高（竖屏）。 */
private val SHEET_SETTLE_SPEC = tween<Float>(durationMillis = 220)
private enum class DragAnchor { Open, Closed }

/**
 * 抽屉外壳：蒙层、横竖屏尺寸、拖拽跟手、下拉/右滑关闭、高度测量。
 *
 * 内容与路由状态留在调用方，这个 composable 只负责“怎么把一个面板拖出来 / 收回去”。
 *
 * 位移判定统一交给 [AnchoredDraggableState]（Material3 内部同款）：位置阈值、速度阈值、
 * fling 吸附和目标动画都由它负责，三个输入路径（可滚动内容的 nested scroll、非滚动区域的
 * 拖动、横屏的水平拖动）只调用 dispatchRawDelta / settle，不再各自维护阈值与回弹逻辑。
 *
 * 注意 1.12 的 [AnchoredDraggableState.positionalThreshold] 与 velocityThreshold 是可变字段，
 * 必须在构造后赋值——单参构造时它们是 null，调用 settle(velocity) 会直接抛异常。
 */
@OptIn(ExperimentalFoundationApi::class)
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

    val density = LocalDensity.current
    // 关闭所需位移：竖屏取抽屉高度，横屏取抽屉宽度，测量后写入。
    var dismissDistancePx by remember { mutableFloatStateOf(0f) }
    val dragScope = rememberCoroutineScope()

    val dragState = remember { AnchoredDraggableState(initialValue = DragAnchor.Open) }

    LaunchedEffect(dismissDistancePx) {
        if (dismissDistancePx <= 0f) return@LaunchedEffect
        dragState.updateAnchors(
            DraggableAnchors {
                DragAnchor.Open at 0f
                DragAnchor.Closed at dismissDistancePx
            }
        )
    }

    // 吸附到 Closed 即关闭；落回 Open 时由 AnchoredDraggable 自己弹回。
    LaunchedEffect(dragState) {
        snapshotFlow { dragState.settledValue }
            .filter { it == DragAnchor.Closed }
            .collect { onDismiss() }
    }

    // 视频层靠这个位移把画面回填到抽屉让出的空间里。
    LaunchedEffect(dragState) {
        snapshotFlow { dragState.offset }
            .filter { !it.isNaN() }
            .collect { onSheetDragOffsetChanged(it) }
    }

    // 只有竖屏内容可滚动时需要分流：列表吃剩的下拉量交给抽屉。横屏横向拖动与列表的
    // 纵向滚动不冲突，直接由 pointerInput 接管。
    val verticalDismissConnection = remember(isLandscape) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isLandscape || source != NestedScrollSource.UserInput) return Offset.Zero
                // 抽屉还没被拖起来时纵向手势全归列表。
                if (dragState.offset.isNaN() || dragState.offset <= 0f) return Offset.Zero
                return Offset(0f, dragState.dispatchRawDelta(available.y))
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (isLandscape || source != NestedScrollSource.UserInput) return Offset.Zero
                // available.y > 0 才是列表到顶后继续下拉的余量。
                if (available.y <= 0f || dragState.offset.isNaN()) return Offset.Zero
                return Offset(0f, dragState.dispatchRawDelta(available.y))
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (isLandscape) return Velocity.Zero
                val offset = dragState.offset
                // 抽屉没被拖动时，速度只是列表到顶的残余，不能据此关闭。
                if (offset.isNaN() || offset <= 0f) return Velocity.Zero
                dragState.settle(SHEET_SETTLE_SPEC)
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
                val dragged = dragState.offset.takeUnless { it.isNaN() } ?: 0f
                if (isLandscape) {
                    IntOffset(dragged.roundToInt(), 0)
                } else {
                    IntOffset(0, dragged.roundToInt())
                }
            }
            .nestedScroll(verticalDismissConnection)
            .pointerInput(isLandscape) {
                if (isLandscape) {
                    detectHorizontalDragGestures(
                        onDragEnd = { dragScope.launch { dragState.settle(SHEET_SETTLE_SPEC) } },
                        onDragCancel = { dragScope.launch { dragState.settle(SHEET_SETTLE_SPEC) } },
                        onHorizontalDrag = { change, dragAmount ->
                            if (dragState.offset.isNaN()) return@detectHorizontalDragGestures
                            // 只允许向右拖（抽屉在右侧），左拖交给内容。
                            if (dragAmount > 0f || dragState.offset > 0f) {
                                val consumed = dragState.dispatchRawDelta(dragAmount)
                                if (consumed != 0f) change.consume()
                            }
                        }
                    )
                }
                // 标题栏、把手等非滚动区域不会进入 nested scroll，保留独立检测器；
                // 可滚动内容会先消费指针事件，两者不会重复计数。
                else {
                    detectVerticalDragGestures(
                        onDragEnd = { dragScope.launch { dragState.settle(SHEET_SETTLE_SPEC) } },
                        onDragCancel = { dragScope.launch { dragState.settle(SHEET_SETTLE_SPEC) } },
                        onVerticalDrag = { change, dragAmount ->
                            if (dragState.offset.isNaN()) return@detectVerticalDragGestures
                            if (dragAmount > 0f || dragState.offset > 0f) {
                                val consumed = dragState.dispatchRawDelta(dragAmount)
                                if (consumed != 0f) change.consume()
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
                .onSizeChanged {
                    dismissDistancePx = if (isLandscape) it.width.toFloat() else it.height.toFloat()
                    if (!isLandscape) onSheetHeightChanged(it.height)
                }
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
                // 内容区关闭 overscroll：列表到顶后的继续下拉应直接进入 nested scroll 驱动抽屉，
                // 而不是先被拉伸/回弹动画吃掉，否则视觉上先是内容动、抽屉滞后。
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    content()
                }
            }
        }
    }
}
