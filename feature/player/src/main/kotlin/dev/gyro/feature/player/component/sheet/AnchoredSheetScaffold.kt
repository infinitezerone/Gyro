package dev.gyro.feature.player.component.sheet

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.gyro.feature.player.component.sheet.common.SheetBgColor
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class SheetAnchor { Open, Hidden }

internal const val SHEET_DISMISS_POSITION_FRACTION = 0.35f
internal val SHEET_DISMISS_VELOCITY_THRESHOLD = 350.dp
private const val SCRIM_MAX_ALPHA = 0.45f

// Material 3 官方推荐的 Bottom Sheet 入场/退场缓动曲线与时长
// https://m3.material.io/styles/motion/easing-and-duration/applying-easing-and-duration
private val M3_EMPHASIZED_DECELERATE = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val M3_EMPHASIZED_ACCELERATE = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
internal const val SHEET_ENTER_DURATION_MS = 400
internal const val SHEET_EXIT_DURATION_MS = 200
private const val SHEET_MIN_ENTER_SETTLE_MS = 120
private const val SHEET_MIN_EXIT_SETTLE_MS = 80

// Material 3 MotionScheme Standard 弹簧参数：仅在高速甩手松手时承接手势初速度，避免 tween 初速归零顿挫
private val M3_FAST_EXIT_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 3800f,
    visibilityThreshold = 0.5f,
)

private val M3_REBOUND_OPEN_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 700f,
    visibilityThreshold = 0.5f,
)

/**
 * Resolves the resting anchor after a drag or fling ends.
 *
 * Velocity takes precedence when strong enough to express directional intent, allowing short
 * flicks to dismiss or snap back open; otherwise the 35% positional threshold decides.
 */
internal fun resolveSheetTarget(
    offsetPx: Float,
    dismissDistancePx: Float,
    velocityPxPerSec: Float,
    positionalThresholdFraction: Float = SHEET_DISMISS_POSITION_FRACTION,
    velocityThresholdPxPerSec: Float,
): SheetAnchor {
    if (dismissDistancePx <= 0f || offsetPx.isNaN()) return SheetAnchor.Open
    if (velocityPxPerSec >= velocityThresholdPxPerSec) return SheetAnchor.Hidden
    if (velocityPxPerSec <= -velocityThresholdPxPerSec) return SheetAnchor.Open
    return if (offsetPx >= dismissDistancePx * positionalThresholdFraction) {
        SheetAnchor.Hidden
    } else {
        SheetAnchor.Open
    }
}

internal fun computeSheetOpenProgress(
    offsetPx: Float,
    dismissDistancePx: Float,
): Float {
    if (dismissDistancePx <= 0f || offsetPx.isNaN()) return 0f
    return (1f - offsetPx / dismissDistancePx).coerceIn(0f, 1f)
}

internal fun computeSheetVisibleHeightPx(
    offsetPx: Float,
    dismissDistancePx: Float,
    isLandscape: Boolean,
): Float {
    if (isLandscape || dismissDistancePx <= 0f || offsetPx.isNaN()) return 0f
    return (dismissDistancePx - offsetPx).coerceIn(0f, dismissDistancePx)
}

internal fun computeScaledSheetDurationMs(
    travelPx: Float,
    dismissDistancePx: Float,
    fullDurationMs: Int,
    minDurationMs: Int,
): Int {
    if (dismissDistancePx <= 0f || travelPx.isNaN()) return fullDurationMs
    val fraction = (abs(travelPx) / dismissDistancePx).coerceIn(0f, 1f)
    return (fullDurationMs * fraction).roundToInt().coerceIn(minDurationMs, fullDurationMs)
}

@OptIn(ExperimentalFoundationApi::class)
@Stable
internal class AnchoredSheetState(
    density: Density,
    initialIsLandscape: Boolean,
) {
    val anchoredState = AnchoredDraggableState(initialValue = SheetAnchor.Hidden)

    var dismissDistancePx by mutableFloatStateOf(0f)
        private set

    var isLandscape by mutableStateOf(initialIsLandscape)
        private set

    private var hasOpened = false
    private var isClosing = false
    private var nestedFlingJob: Job? = null

    private val velocityThresholdPx = with(density) { SHEET_DISMISS_VELOCITY_THRESHOLD.toPx() }

    private fun enterSpecFor(travelPx: Float): AnimationSpec<Float> = tween(
        durationMillis = computeScaledSheetDurationMs(
            travelPx = travelPx,
            dismissDistancePx = dismissDistancePx,
            fullDurationMs = SHEET_ENTER_DURATION_MS,
            minDurationMs = SHEET_MIN_ENTER_SETTLE_MS,
        ),
        easing = M3_EMPHASIZED_DECELERATE,
    )

    private fun exitSpecFor(travelPx: Float): AnimationSpec<Float> = tween(
        durationMillis = computeScaledSheetDurationMs(
            travelPx = travelPx,
            dismissDistancePx = dismissDistancePx,
            fullDurationMs = SHEET_EXIT_DURATION_MS,
            minDurationMs = SHEET_MIN_EXIT_SETTLE_MS,
        ),
        easing = M3_EMPHASIZED_ACCELERATE,
    )

    val flingBehavior: TargetedFlingBehavior = object : TargetedFlingBehavior {
        override suspend fun ScrollScope.performFling(
            initialVelocity: Float,
            onRemainingDistanceUpdated: (Float) -> Unit,
        ): Float {
            val currentOffset = anchoredState.offset
            val anchors = anchoredState.anchors
            if (currentOffset.isNaN() || anchors.size == 0) {
                onRemainingDistanceUpdated(0f)
                return 0f
            }
            val target = resolveSheetTarget(
                offsetPx = currentOffset,
                dismissDistancePx = dismissDistancePx,
                velocityPxPerSec = initialVelocity,
                velocityThresholdPxPerSec = velocityThresholdPx,
            )
            val targetOffset = anchors.positionOf(target)
            if (targetOffset.isNaN()) {
                onRemainingDistanceUpdated(0f)
                return 0f
            }
            val travel = targetOffset - currentOffset
            if (abs(travel) <= 0.5f) {
                if (travel != 0f) scrollBy(travel)
                onRemainingDistanceUpdated(0f)
                return 0f
            }
            val isFastDirectionalFlick = when (target) {
                SheetAnchor.Hidden -> initialVelocity >= velocityThresholdPx
                SheetAnchor.Open -> initialVelocity <= -velocityThresholdPx
            }
            val spec: AnimationSpec<Float> = when (target) {
                SheetAnchor.Hidden -> if (isFastDirectionalFlick) M3_FAST_EXIT_SPRING else exitSpecFor(travel)
                SheetAnchor.Open -> if (isFastDirectionalFlick) M3_REBOUND_OPEN_SPRING else enterSpecFor(travel)
            }
            val effectiveVelocity = if (isFastDirectionalFlick) initialVelocity else 0f
            var previousValue = currentOffset
            animate(
                initialValue = currentOffset,
                targetValue = targetOffset,
                initialVelocity = effectiveVelocity,
                animationSpec = spec,
            ) { value, _ ->
                val delta = value - previousValue
                val consumed = scrollBy(delta)
                previousValue += consumed
                onRemainingDistanceUpdated(targetOffset - previousValue)
            }
            val residual = targetOffset - anchoredState.offset
            if (!residual.isNaN() && abs(residual) <= 1f && residual != 0f) {
                scrollBy(residual)
            }
            onRemainingDistanceUpdated(0f)
            return 0f
        }
    }

    val openProgress: Float
        get() = computeSheetOpenProgress(anchoredState.offset, dismissDistancePx)

    val visibleHeightPx: Float
        get() = computeSheetVisibleHeightPx(anchoredState.offset, dismissDistancePx, isLandscape)

    val isDisplacedFromOpen: Boolean
        get() = anchoredState.offset.let { !it.isNaN() && it > 0.5f }

    fun setClosingTarget(closing: Boolean) {
        isClosing = closing
    }

    fun updateBounds(distancePx: Float, landscape: Boolean) {
        if (distancePx <= 0f) return
        // 退场阶段冻结锚点：避免竖屏恢复系统栏或外层重组触发 updateAnchors 抢锁瞬移/打断退场弹簧。
        if (isClosing && !anchoredState.offset.isNaN()) return
        val uninitialized = anchoredState.offset.isNaN()
        val orientationChanged = isLandscape != landscape
        val distanceChanged = abs(distancePx - dismissDistancePx) > 0.5f
        isLandscape = landscape
        dismissDistancePx = distancePx
        if (!uninitialized && !orientationChanged && !distanceChanged) return
        val newAnchors = DraggableAnchors {
            SheetAnchor.Open at 0f
            SheetAnchor.Hidden at distancePx
        }
        val newTarget = if (uninitialized || !hasOpened) {
            SheetAnchor.Hidden
        } else {
            SheetAnchor.Open
        }
        anchoredState.updateAnchors(newAnchors, newTarget)
    }

    fun dispatchNestedDelta(deltaY: Float): Float {
        if (anchoredState.offset.isNaN() || isClosing) return 0f
        nestedFlingJob?.cancel()
        nestedFlingJob = null
        return anchoredState.dispatchRawDelta(deltaY)
    }

    suspend fun performNestedFling(velocityY: Float): Float {
        if (anchoredState.offset.isNaN() || isClosing) return 0f
        nestedFlingJob = currentCoroutineContext()[Job]
        var leftoverVelocity = velocityY
        try {
            anchoredState.anchoredDrag { anchors ->
                val minPos = anchors.minPosition()
                val maxPos = anchors.maxPosition()
                val scrollScope = object : ScrollScope {
                    override fun scrollBy(pixels: Float): Float {
                        val current = anchoredState.offset
                        if (current.isNaN()) return 0f
                        val next = (current + pixels).coerceIn(minPos, maxPos)
                        val consumed = next - current
                        dragTo(next)
                        return consumed
                    }
                }
                with(flingBehavior) {
                    leftoverVelocity = scrollScope.performFling(velocityY)
                }
            }
        } finally {
            nestedFlingJob = null
        }
        return velocityY - leftoverVelocity
    }

    suspend fun open() {
        isClosing = false
        snapshotFlow { !anchoredState.offset.isNaN() && dismissDistancePx > 0f }.first { it }
        hasOpened = true
        try {
            animateToAnchor(SheetAnchor.Open, enterSpecFor(anchoredState.offset))
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }

    suspend fun close() {
        isClosing = true
        nestedFlingJob?.cancel()
        nestedFlingJob = null
        val startOffset = anchoredState.offset
        if (startOffset.isNaN() || dismissDistancePx <= 0f) return
        if (startOffset >= dismissDistancePx - 0.5f) return
        animateToAnchor(SheetAnchor.Hidden, exitSpecFor(dismissDistancePx - startOffset))
    }

    private suspend fun animateToAnchor(
        target: SheetAnchor,
        spec: AnimationSpec<Float>,
    ) {
        anchoredState.anchoredDrag(targetValue = target) { anchors, latestTarget ->
            val targetOffset = anchors.positionOf(latestTarget)
            val startOffset = anchoredState.offset
            if (!targetOffset.isNaN() && !startOffset.isNaN() && abs(targetOffset - startOffset) > 0.5f) {
                animate(
                    initialValue = startOffset,
                    targetValue = targetOffset,
                    initialVelocity = 0f,
                    animationSpec = spec,
                ) { value, velocity ->
                    dragTo(value, velocity)
                }
            }
            if (!targetOffset.isNaN()) {
                dragTo(targetOffset, 0f)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.anchoredSheetLayout(
    sheetState: AnchoredSheetState,
    isLandscape: Boolean,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val distance = if (isLandscape) placeable.width.toFloat() else placeable.height.toFloat()
    if (!isLookingAhead && distance > 0f) {
        sheetState.updateBounds(distance, isLandscape)
    }
    layout(placeable.width, placeable.height) {
        val offset = sheetState.anchoredState.offset
        val shift = if (offset.isNaN()) distance else offset
        if (isLandscape) {
            placeable.placeRelative(shift.roundToInt(), 0)
        } else {
            placeable.place(0, shift.roundToInt())
        }
    }
}

/**
 * 统一的自定义锚点抽屉外壳：基于 Compose 1.12 [AnchoredDraggableState] + [snapFlingBehavior]，
 * 统一接管入场/退场动画、跟手拖拽、速度敏感 Fling、蒙层透明度及竖屏视频避让联动。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun AnchoredSheetScaffold(
    visible: Boolean,
    isLandscape: Boolean,
    heightFraction: Float = 0.45f,
    onBindVisibleHeightProvider: (() -> Float) -> Unit = {},
    onUnbindVisibleHeightProvider: (() -> Float) -> Unit = {},
    onDismiss: () -> Unit,
    onHidden: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val density = LocalDensity.current

    val sheetState = remember(density) {
        AnchoredSheetState(density = density, initialIsLandscape = isLandscape)
    }
    sheetState.setClosingTarget(!visible)

    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestOnHidden by rememberUpdatedState(onHidden)

    val visibleHeightProvider: () -> Float = remember(sheetState) {
        { sheetState.visibleHeightPx }
    }
    DisposableEffect(visibleHeightProvider) {
        onBindVisibleHeightProvider(visibleHeightProvider)
        onDispose {
            onUnbindVisibleHeightProvider(visibleHeightProvider)
        }
    }

    LaunchedEffect(visible, sheetState) {
        if (visible) {
            sheetState.open()
            snapshotFlow {
                sheetState.anchoredState.settledValue == SheetAnchor.Hidden &&
                    sheetState.anchoredState.targetValue == SheetAnchor.Hidden &&
                    sheetState.openProgress <= 0.005f
            }.first { it }
            latestOnDismiss()
        } else {
            sheetState.close()
            latestOnHidden()
        }
    }

    // 竖屏下内容列表与抽屉共享纵向滑动：列表消费到顶后的余量下拉抽屉，
    // 抽屉离开 Open 后的一切上下拖拽与甩手都优先由抽屉消费。
    val nestedScrollConnection = remember(sheetState, isLandscape) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isLandscape || source != NestedScrollSource.UserInput) return Offset.Zero
                if (!sheetState.isDisplacedFromOpen) return Offset.Zero
                return Offset(0f, sheetState.dispatchNestedDelta(available.y))
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (isLandscape || source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y <= 0f) return Offset.Zero
                return Offset(0f, sheetState.dispatchNestedDelta(available.y))
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (isLandscape) return Velocity.Zero
                if (!sheetState.isDisplacedFromOpen || sheetState.openProgress <= 0.005f) {
                    return Velocity.Zero
                }
                sheetState.performNestedFling(available.y)
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (isLandscape) return Velocity.Zero
                if (!sheetState.isDisplacedFromOpen || sheetState.openProgress <= 0.005f) {
                    return Velocity.Zero
                }
                sheetState.performNestedFling(available.y)
                return available
            }
        }
    }

    val sheetModifier = if (isLandscape) {
        Modifier
            .fillMaxHeight()
            .width(360.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .heightIn(max = screenHeight * heightFraction)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 全屏蒙层：透明度直接跟随抽屉开合进度，零重组同步变化。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = SCRIM_MAX_ALPHA * sheetState.openProgress
                }
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = visible,
                    onClick = onDismiss,
                )
        )

        // anchoredDraggable 必须位于 anchoredSheetLayout 之前，保证触摸坐标系固定不抖动。
        Box(
            modifier = sheetModifier
                .align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter)
                .nestedScroll(nestedScrollConnection)
                .anchoredDraggable(
                    state = sheetState.anchoredState,
                    orientation = if (isLandscape) Orientation.Horizontal else Orientation.Vertical,
                    enabled = visible,
                    overscrollEffect = null,
                    flingBehavior = sheetState.flingBehavior,
                )
                .anchoredSheetLayout(
                    sheetState = sheetState,
                    isLandscape = isLandscape,
                )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isLandscape) Modifier.fillMaxHeight() else Modifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
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
                shadowElevation = 16.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isLandscape) {
                                Modifier.windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                            } else {
                                Modifier
                                    .windowInsetsPadding(
                                        WindowInsets.displayCutout
                                            .union(WindowInsets.navigationBars)
                                            .only(WindowInsetsSides.End)
                                    )
                                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                                    .padding(top = 8.dp)
                            }
                        )
                ) {
                    if (!isLandscape) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .background(
                                        color = Color.White.copy(alpha = 0.25f),
                                        shape = RoundedCornerShape(2.dp),
                                    )
                            )
                        }
                    }
                    // 关闭内容 overscroll：列表到顶后继续下拉直接进入 nestedScroll 驱动抽屉，
                    // 避免先触发拉伸回弹导致手感滞后。
                    CompositionLocalProvider(LocalOverscrollFactory provides null) {
                        content()
                    }
                }
            }
        }
    }
}
