package dev.gyro.player.ui

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

enum class DragMode {
    UNDETERMINED,
    VERTICAL_BRIGHTNESS,
    VERTICAL_VOLUME,
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
            val touchSlop = viewConfiguration.touchSlop
            val isEdgeExclusion = downPosition.x < config.edgeExclusionPx ||
                    downPosition.x > size.width - config.edgeExclusionPx

            var isMultiTouch = false
            var isLongPressing = false
            var hasPassedTouchSlop = false
            var dragMode = DragMode.UNDETERMINED
            var dragStartPosMs = callbacks.getCurrentPositionMs()
            var lastSeekTargetMs = dragStartPosMs

            // Start long press detection
            var longPressJob: Job? = if (config.isLongPressSpeedEnabled) {
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

                        if (config.isZoomEnabled && callbacks.getZoomScale() > 1.05f) {
                            // Zoomed in: single finger drag smoothly pans the zoomed video content
                            dragMode = DragMode.PAN_ZOOM
                            callbacks.onZoomPan(1.0f, dragDelta, pointer.position, false)
                        } else if (!isEdgeExclusion) {
                            if (abs(dragDelta.x) > abs(dragDelta.y) * 1.5f && config.isSeekEnabled) {
                                dragMode = DragMode.HORIZONTAL_SEEK
                                dragStartPosMs = callbacks.getCurrentPositionMs()
                                lastSeekTargetMs = dragStartPosMs
                                callbacks.onSeekStart(dragStartPosMs)
                            } else if (abs(dragDelta.y) > abs(dragDelta.x) * 1.5f) {
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
                                val seekSpan = (totalDur / 10L).coerceIn(60_000L, 900_000L)
                                val deltaRatio = deltaX / size.width
                                val deltaMs = (deltaRatio * seekSpan).toLong()
                                val targetMs = (dragStartPosMs + deltaMs).coerceIn(0L, totalDur)
                                lastSeekTargetMs = targetMs
                                callbacks.onSeekUpdate(targetMs, deltaMs, totalDur)
                            }
                        }
                        DragMode.VERTICAL_BRIGHTNESS -> {
                            pointer.consume()
                            val dragSpan = (size.height * 0.75f).coerceAtLeast(100f)
                            val deltaY = downPosition.y - pointer.position.y
                            val deltaRatio = (deltaY / dragSpan) * config.brightnessSensitivity
                            callbacks.onBrightnessUpdate(deltaRatio)
                        }
                        DragMode.VERTICAL_VOLUME -> {
                            pointer.consume()
                            val dragSpan = (size.height * 0.75f).coerceAtLeast(100f)
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
                    delay(600L)
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

/**
 * Attaches MX Player style customizable gesture handling to the modifier chain via Modifier.Node.
 */
fun Modifier.playerGestures(
    config: PlayerGestureConfig,
    callbacks: PlayerGestureCallbacks
): Modifier = this.then(PlayerGestureElement(config, callbacks))
