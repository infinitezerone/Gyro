package dev.gyro.feature.player.gesture

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import dev.gyro.core.model.PlayerGestureConfig

enum class DragMode {
    UNDETERMINED,
    VERTICAL_BRIGHTNESS,
    VERTICAL_VOLUME,
    VERTICAL_SWITCH_VIDEO,
    HORIZONTAL_SEEK,
    PAN_ZOOM
}

/**
 * Modern Modifier.Node implementation for player gestures.
 * Provides high-performance gesture discrimination without lambda recomposition churn,
 * supporting MX Player style customization (configurable sensitivity, swapping, toggles).
 */
class PlayerGestureNode(
    var config: PlayerGestureConfig,
    var callbacks: PlayerGestureCallbacks
) : DelegatingNode(), PointerInputModifierNode {

    // Double-tap accumulator state
    private var doubleTapJob: Job? = null
    private var singleTapJob: Job? = null
    private var accumulatedSeconds: Int = 0
    private var doubleTapSide: DoubleTapSide? = null
    private var lastTapTimestamp: Long = 0L
    private var lastTapPosition: Offset = Offset.Zero

    private val pointerInputNode = delegate(
        SuspendingPointerInputModifierNode {
            detectGestures()
        }
    )

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize
    ) {
        pointerInputNode.onPointerEvent(pointerEvent, pass, bounds)
    }

    override fun onCancelPointerInput() {
        pointerInputNode.onCancelPointerInput()
    }

    fun update(newConfig: PlayerGestureConfig, newCallbacks: PlayerGestureCallbacks) {
        val needsReset = this.config.isLocked != newConfig.isLocked
        this.config = newConfig
        this.callbacks = newCallbacks
        if (needsReset) {
            pointerInputNode.resetPointerInputHandler()
        }
    }

    override fun onDetach() {
        cancelTapTimers()
        super.onDetach()
    }

    private fun cancelTapTimers() {
        lastTapTimestamp = 0L
        lastTapPosition = Offset.Zero
        singleTapJob?.cancel()
        singleTapJob = null
        doubleTapJob?.cancel()
        doubleTapJob = null
    }

    private suspend fun PointerInputScope.detectGestures() {
        awaitEachGesture {
            if (config.isLocked) {
                // In locked mode, only single tap to toggle controls / unlock button
                awaitFirstDown(requireUnconsumed = true)
                val up = waitForUpOrCancellation()
                if (up != null) {
                    callbacks.onSingleTap()
                }
                return@awaitEachGesture
            }

            // Only capture unconsumed touches so on-screen controls (scrub bar, buttons) take priority
            val down = awaitFirstDown(requireUnconsumed = true)
            val downPosition = down.position
            var lastPointerPosition = downPosition
            val touchSlop = viewConfiguration.touchSlop
            val isEdgeExclusion = downPosition.x < config.edgeExclusionPx ||
                    downPosition.x > size.width - config.edgeExclusionPx

            val isPortrait = size.width < size.height

            // 竖屏手势安全区约束（防系统边缘误触）：
            // 仅排除顶部状态栏/下拉通知栏区域 (48dp) 与底部全面屏手势横条区域 (56dp)，
            // 画面主体及上下黑边区域（即用户单手握持时大拇指自然落点的舒适区）均全面开放手势支持。
            val isOutsidePortraitActiveZone = if (isPortrait) {
                val topExclusion = with(this) { 48.dp.toPx() }
                val bottomExclusion = with(this) { 56.dp.toPx() }
                downPosition.y < topExclusion || downPosition.y > size.height - bottomExclusion
            } else {
                false
            }

            var isMultiTouch = false
            var isLongPressing = false
            var hasPassedTouchSlop = false
            var dragMode = DragMode.UNDETERMINED
            var dragStartPosMs = callbacks.getCurrentPositionMs()
            var lastSeekTargetMs = dragStartPosMs

            // Start long press detection
            var longPressJob: Job? = if (config.isLongPressSpeedEnabled && !isOutsidePortraitActiveZone) {
                coroutineScope.launch {
                    delay(config.longPressTimeoutMs)
                    if (!hasPassedTouchSlop && !isMultiTouch && callbacks.getZoomScale() <= 1.05f) {
                        isLongPressing = true
                        callbacks.onFastForwardStart(config.longPressSpeed)
                        callbacks.onHudStateChange(GestureHudState.Speed(config.longPressSpeed))
                    }
                }
            } else null

            while (true) {
                val event = awaitPointerEvent()
                val pressedPointers = event.changes.filter { it.pressed && !it.isConsumed }

                if (pressedPointers.isEmpty()) {
                    // All fingers lifted or consumed by overlay
                    break
                }

                // 1. Two-finger Pinch-to-Zoom & Pan Priority
                if (pressedPointers.size >= 2) {
                    if (!isMultiTouch) {
                        isMultiTouch = true
                        cancelTapTimers()
                        longPressJob?.cancel()
                        longPressJob = null
                        if (isLongPressing) {
                            isLongPressing = false
                            callbacks.onFastForwardEnd()
                            callbacks.onHudStateChange(GestureHudState.Idle)
                        }
                        if (dragMode == DragMode.HORIZONTAL_SEEK) {
                            callbacks.onHudStateChange(GestureHudState.Idle)
                        }
                        dragMode = DragMode.UNDETERMINED
                    }

                    if (config.isZoomEnabled) {
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = false)

                        if (zoomChange != 1f || panChange != Offset.Zero) {
                            val effectiveCentroid = if (centroid != Offset.Unspecified) {
                                centroid
                            } else {
                                Offset(size.width / 2f, size.height / 2f)
                            }
                            callbacks.onZoomPan(zoomChange, panChange, effectiveCentroid, true)
                        }
                    }

                    event.changes.forEach {
                        if (it.positionChanged()) {
                            it.consume()
                        }
                    }
                    continue
                }

                // 2. If multi-touch was active, stay locked in multi-touch mode until all fingers release
                if (isMultiTouch) {
                    event.changes.forEach {
                        if (it.positionChanged()) {
                            it.consume()
                        }
                    }
                    continue
                }

                // 3. Single Pointer Handling
                val pointer = pressedPointers.firstOrNull() ?: break
                lastPointerPosition = pointer.position
                if (isLongPressing) {
                    pointer.consume()
                    continue
                }

                val dragDelta = pointer.position - downPosition
                val dist = hypot(dragDelta.x, dragDelta.y)

                if (!hasPassedTouchSlop) {
                    if (dist > touchSlop) {
                        hasPassedTouchSlop = true
                        cancelTapTimers()
                        longPressJob?.cancel()
                        longPressJob = null

                        if (config.isZoomEnabled && callbacks.getZoomScale() > 1.05f && !isOutsidePortraitActiveZone) {
                            // Zoomed in: single finger drag smoothly pans the zoomed video content
                            dragMode = DragMode.PAN_ZOOM
                            callbacks.onZoomPan(1.0f, dragDelta, pointer.position, false)
                        } else if (!isEdgeExclusion && !isOutsidePortraitActiveZone) {
                            if (abs(dragDelta.x) > abs(dragDelta.y) * 1.5f && config.isSeekEnabled) {
                                dragMode = DragMode.HORIZONTAL_SEEK
                                dragStartPosMs = callbacks.getCurrentPositionMs()
                                lastSeekTargetMs = dragStartPosMs
                                callbacks.onSeekStart(dragStartPosMs)
                            } else if (abs(dragDelta.y) > abs(dragDelta.x) * 1.5f) {
                                if (config.verticalSwipeMode == dev.gyro.core.model.VerticalSwipeMode.SWITCH_VIDEO) {
                                    // Yield vertical drag to parent VerticalPager for native TikTok feed scrolling
                                    cancelTapTimers()
                                    longPressJob?.cancel()
                                    longPressJob = null
                                    return@awaitEachGesture
                                } else {
                                    val isLeft = downPosition.x < size.width / 2f
                                    val isBrightnessTarget = if (config.swapVolumeAndBrightness) !isLeft else isLeft
                                    if (isBrightnessTarget && config.isBrightnessEnabled) {
                                        dragMode = DragMode.VERTICAL_BRIGHTNESS
                                        callbacks.onBrightnessStart()
                                    } else if (!isBrightnessTarget && config.isVolumeEnabled) {
                                        dragMode = DragMode.VERTICAL_VOLUME
                                        callbacks.onVolumeStart()
                                    }
                                }
                            }
                        }
                    }
                }

                if (hasPassedTouchSlop) {
                    val posChange = pointer.positionChange()
                    when (dragMode) {
                        DragMode.PAN_ZOOM -> {
                            pointer.consume()
                            if (posChange != Offset.Zero) {
                                callbacks.onZoomPan(1.0f, posChange, pointer.position, false)
                            }
                        }
                        DragMode.HORIZONTAL_SEEK -> {
                            pointer.consume()
                            val totalDur = callbacks.getDurationMs()
                            if (totalDur > 0L) {
                                val deltaX = pointer.position.x - downPosition.x
                                val deltaRatio = (deltaX / size.width).coerceIn(-1f, 1f)
                                // 动态高响应手势滑动跨度 (seekSpan)：
                                // 彻底解决原 totalDur / 10 导致滑动距离过小、手指大幅横滑仅前进十几秒的吃力问题：
                                // 1. 短视频 (<= 3分钟)：全屏滑动可跨越整段视频 (100%)，自然单指轻滑约 1 分钟；
                                // 2. 中短视频 (3 - 20分钟)：全屏滑动跨越约 75% 时长（保底 3 分钟），单指轻滑约 2~3 分钟；
                                // 3. 剧集 / 番剧 (20 - 60分钟)：全屏滑动跨越约 60% 时长（保底 15 分钟），轻松单指划过片头片尾；
                                // 4. 电影 / 长篇 (1 - 2.5小时)：全屏滑动跨越约 45% 时长（保底 36 分钟），单指轻滑跨越 10~15 分钟；
                                // 5. 超长篇 (> 2.5小时)：全屏滑动跨越约 35% 时长（保底 60 分钟），支持超长视频快速检索。
                                val seekSpan = when {
                                    totalDur <= 180_000L -> totalDur.toFloat()
                                    totalDur <= 1_200_000L -> maxOf(totalDur * 0.75f, 180_000f)
                                    totalDur <= 3_600_000L -> maxOf(totalDur * 0.60f, 900_000f)
                                    totalDur <= 9_000_000L -> maxOf(totalDur * 0.45f, 2_160_000f)
                                    else -> maxOf(totalDur * 0.35f, 4_050_000f)
                                }
                                val deltaMs = (deltaRatio * seekSpan * config.seekSensitivity).toLong()
                                val targetMs = (dragStartPosMs + deltaMs).coerceIn(0L, totalDur)
                                lastSeekTargetMs = targetMs
                                callbacks.onSeekUpdate(targetMs, deltaMs, totalDur)
                            }
                        }
                        DragMode.VERTICAL_BRIGHTNESS -> {
                            pointer.consume()
                            // 垂直亮度滑动手势行程校准：
                            // 横屏取高度 40% (约 160dp)，竖屏取高度 25% (约 220dp)，契合单手大拇指 120-180dp 自然触控行程，
                            // 避免原 75% 屏幕高度行程过长导致单次滑动调节量过小的迟钝感。
                            val dragSpan = if (size.width > size.height) {
                                (size.height * 0.40f).coerceAtLeast(100f)
                            } else {
                                (size.height * 0.25f).coerceAtLeast(100f)
                            }
                            val deltaY = downPosition.y - pointer.position.y
                            val deltaRatio = (deltaY / dragSpan) * config.brightnessSensitivity
                            callbacks.onBrightnessUpdate(deltaRatio)
                        }
                        DragMode.VERTICAL_VOLUME -> {
                            pointer.consume()
                            // 垂直音量滑动手势行程校准：
                            // 一段行程 (100% 满额) 约需 160-220dp 划程，单次大幅向上滑即可自然跨入二段软件超驱动 (101%~200%)
                            val dragSpan = if (size.width > size.height) {
                                (size.height * 0.40f).coerceAtLeast(100f)
                            } else {
                                (size.height * 0.25f).coerceAtLeast(100f)
                            }
                            val deltaY = downPosition.y - pointer.position.y
                            val deltaRatio = (deltaY / dragSpan) * config.volumeSensitivity
                            callbacks.onVolumeUpdate(deltaRatio)
                        }
                        else -> {
                            // Ignored edge drag
                        }
                    }
                }
            }

            // Gesture Ended (All pointers up)
            longPressJob?.cancel()
            longPressJob = null

            if (isLongPressing) {
                callbacks.onFastForwardEnd()
                callbacks.onHudStateChange(GestureHudState.Idle)
                return@awaitEachGesture
            }

            if (isMultiTouch) {
                // Pinch-to-zoom ended cleanly. Reset tap state so it never false-triggers double tap.
                cancelTapTimers()
                if (config.isZoomEnabled) {
                    if (callbacks.getZoomScale() < 1.0f) {
                        callbacks.onResetZoom()
                    } else {
                        callbacks.onSettleZoom()
                    }
                }
                return@awaitEachGesture
            }

            if (hasPassedTouchSlop) {
                cancelTapTimers()
                if (dragMode == DragMode.HORIZONTAL_SEEK) {
                    callbacks.onSeekEnd(lastSeekTargetMs)
                }
                dragMode = DragMode.UNDETERMINED
                coroutineScope.launch {
                    delay(700L)
                    callbacks.onHudStateChange(GestureHudState.Idle)
                }
                return@awaitEachGesture
            }

            // Tap & Double-Tap Processing (Only for clean single-finger taps without movement)
            val now = SystemClock.uptimeMillis()
            val timeDelta = now - lastTapTimestamp
            val tapDist = hypot(downPosition.x - lastTapPosition.x, downPosition.y - lastTapPosition.y)
            val isDoubleTapAccumulating = doubleTapJob?.isActive == true && doubleTapSide != null

            if (timeDelta in 40L..config.doubleTapTimeoutMs && tapDist < touchSlop * 3.5f) {
                // Detected Double-Tap!
                singleTapJob?.cancel()
                singleTapJob = null
                lastTapTimestamp = 0L

                if (config.isZoomEnabled && callbacks.getZoomScale() > 1.05f) {
                    // Zoomed in: smoothly reset scale & pan to 1.0x
                    callbacks.onResetZoom()
                    return@awaitEachGesture
                }

                if (isOutsidePortraitActiveZone) {
                    // 竖屏黑边区域双击不触发快进快退，保持画面静止
                    return@awaitEachGesture
                }

                val thirdW = size.width / 3f
                if (downPosition.x < thirdW && config.isDoubleTapSeekEnabled) {
                    // Left double-tap (Seek Backward)
                    val stepSeconds = config.doubleTapSeekSeconds
                    if (isDoubleTapAccumulating && doubleTapSide == DoubleTapSide.LEFT) {
                        accumulatedSeconds += stepSeconds
                    } else {
                        accumulatedSeconds = stepSeconds
                        doubleTapSide = DoubleTapSide.LEFT
                    }
                    callbacks.onHudStateChange(GestureHudState.DoubleTap(DoubleTapSide.LEFT, accumulatedSeconds))

                    doubleTapJob?.cancel()
                    doubleTapJob = coroutineScope.launch {
                        delay(650L)
                        callbacks.onDoubleTapSeek(-accumulatedSeconds * 1000L)
                        accumulatedSeconds = 0
                        doubleTapSide = null
                        delay(400L)
                        callbacks.onHudStateChange(GestureHudState.Idle)
                    }
                } else if (downPosition.x > size.width - thirdW && config.isDoubleTapSeekEnabled) {
                    // Right double-tap (Seek Forward)
                    val stepSeconds = config.doubleTapSeekSeconds
                    if (isDoubleTapAccumulating && doubleTapSide == DoubleTapSide.RIGHT) {
                        accumulatedSeconds += stepSeconds
                    } else {
                        accumulatedSeconds = stepSeconds
                        doubleTapSide = DoubleTapSide.RIGHT
                    }
                    callbacks.onHudStateChange(GestureHudState.DoubleTap(DoubleTapSide.RIGHT, accumulatedSeconds))

                    doubleTapJob?.cancel()
                    doubleTapJob = coroutineScope.launch {
                        delay(650L)
                        callbacks.onDoubleTapSeek(accumulatedSeconds * 1000L)
                        accumulatedSeconds = 0
                        doubleTapSide = null
                        delay(400L)
                        callbacks.onHudStateChange(GestureHudState.Idle)
                    }
                } else if (config.isDoubleTapPlayPauseEnabled) {
                    // Center double-tap (Play/Pause)
                    callbacks.onDoubleTapPlayPause()
                }
            } else {
                // First tap
                lastTapTimestamp = now
                lastTapPosition = downPosition
                singleTapJob?.cancel()
                singleTapJob = coroutineScope.launch {
                    delay(config.singleTapDelayMs)
                    callbacks.onSingleTap()
                }
            }
        }
    }
}

/**
 * ModifierNodeElement that attaches [PlayerGestureNode] into the Compose layout node hierarchy.
 */
data class PlayerGestureElement(
    val config: PlayerGestureConfig,
    val callbacks: PlayerGestureCallbacks
) : ModifierNodeElement<PlayerGestureNode>() {

    override fun create(): PlayerGestureNode = PlayerGestureNode(config, callbacks)

    override fun update(node: PlayerGestureNode) {
        node.update(config, callbacks)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "playerGestures"
        properties["config"] = config
    }
}


fun Modifier.playerGestures(
    config: PlayerGestureConfig,
    callbacks: PlayerGestureCallbacks
): Modifier = this.then(PlayerGestureElement(config, callbacks))
