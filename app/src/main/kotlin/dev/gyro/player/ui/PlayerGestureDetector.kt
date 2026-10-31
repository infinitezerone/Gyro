package dev.gyro.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

enum class DoubleTapSide {
    LEFT,
    RIGHT
}

sealed class GestureHudState {
    data object Idle : GestureHudState()
    data class Volume(val percent: Int, val volumeRatio: Float) : GestureHudState()
    data class Brightness(val percent: Int, val brightnessRatio: Float) : GestureHudState()
    data class Seek(val targetMs: Long, val deltaMs: Long, val totalMs: Long) : GestureHudState()
    data class DoubleTap(val side: DoubleTapSide, val seconds: Int) : GestureHudState()
    data class Speed(val speed: Float) : GestureHudState()
}

/**
 * Benchmark-aligned player gesture detector with unified pointer processing:
 * 1. Two-finger pinch-to-zoom (0.5x - 4.0x) and pan (zero pointer conflict with single-touch)
 * 2. Left vertical swipe: Brightness with edge vertical slider
 * 3. Right vertical swipe: Volume with edge vertical slider
 * 4. Horizontal swipe: Central delta seek card with PlayerScrubState
 * 5. Double-tap left/right: 10s accumulated leap with YouTube pills
 * 6. Double-tap when zoomed: Resets zoom to 1.0x
 * 7. Long-press: 2.0x temporary fast-forward
 * 8. System edge gesture exclusion (24dp)
 * 9. Touch lock support
 */
@Composable
fun Modifier.playerGestures(
    context: Context,
    isLocked: Boolean,
    isPlaying: Boolean,
    currentPosMs: () -> Long,
    durationMs: () -> Long,
    scrubState: PlayerScrubState,
    zoomScale: Float,
    onZoomChange: (Float, Offset) -> Unit,
    onResetZoom: () -> Unit,
    onSingleTap: () -> Unit,
    onDoubleTapSeek: (Long) -> Unit,
    onDoubleTapPlayPause: () -> Unit,
    onFastForwardStart: () -> Unit,
    onFastForwardEnd: () -> Unit,
    onHudStateChange: (GestureHudState) -> Unit
): Modifier {
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    val density = LocalDensity.current
    val edgeThresholdPx = with(density) { 24.dp.toPx() }
    val scope = rememberCoroutineScope()

    // Double-tap accumulator state
    var doubleTapJob by remember { mutableStateOf<Job?>(null) }
    var singleTapJob by remember { mutableStateOf<Job?>(null) }
    var accumulatedSeconds by remember { mutableIntStateOf(0) }
    var doubleTapSide by remember { mutableStateOf<DoubleTapSide?>(null) }
    var lastTapTimestamp by remember { mutableLongStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }

    return this.pointerInput(isLocked, zoomScale) {
        awaitEachGesture {
            if (isLocked) {
                // In locked mode, only single tap to toggle controls / unlock button
                awaitFirstDown(requireUnconsumed = false)
                val up = waitForUpOrCancellation()
                if (up != null) {
                    onSingleTap()
                }
                return@awaitEachGesture
            }

            val down = awaitFirstDown(requireUnconsumed = false)
            val downPosition = down.position
            val touchSlop = viewConfiguration.touchSlop
            val isEdgeExclusion = downPosition.x < edgeThresholdPx || downPosition.x > size.width - edgeThresholdPx

            var isMultiTouch = false
            var isLongPressing = false
            var hasPassedTouchSlop = false
            var dragMode = DragMode.UNDETERMINED
            var dragStartPosMs = currentPosMs()
            var accumulatedVolumeDragY = 0f

            // Start long press detection (450ms)
            var longPressJob: Job? = scope.launch {
                delay(450L)
                if (!hasPassedTouchSlop && !isMultiTouch) {
                    isLongPressing = true
                    onFastForwardStart()
                    onHudStateChange(GestureHudState.Speed(2.0f))
                }
            }

            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }

                if (pressed.isEmpty()) {
                    // All fingers lifted
                    break
                }

                // 1. Two-finger Pinch-to-Zoom & Pan Priority
                if (pressed.size >= 2) {
                    if (!isMultiTouch) {
                        isMultiTouch = true
                        longPressJob?.cancel()
                        longPressJob = null
                        if (isLongPressing) {
                            isLongPressing = false
                            onFastForwardEnd()
                            onHudStateChange(GestureHudState.Idle)
                        }
                        if (dragMode == DragMode.HORIZONTAL_SEEK) {
                            scrubState.cancel()
                            onHudStateChange(GestureHudState.Idle)
                        }
                        dragMode = DragMode.UNDETERMINED
                    }

                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoom != 1f || pan != Offset.Zero) {
                        onZoomChange(zoom, pan)
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
                val pointer = pressed[0]
                if (isLongPressing) {
                    // Long press is active: consume drag events without seek/volume/brightness
                    pointer.consume()
                    continue
                }

                val dragDelta = pointer.position - downPosition
                val dist = hypot(dragDelta.x, dragDelta.y)

                if (!hasPassedTouchSlop) {
                    if (dist > touchSlop) {
                        hasPassedTouchSlop = true
                        longPressJob?.cancel()
                        longPressJob = null

                        if (!isEdgeExclusion) {
                            if (abs(dragDelta.x) > abs(dragDelta.y) * 1.5f) {
                                dragMode = DragMode.HORIZONTAL_SEEK
                                dragStartPosMs = currentPosMs()
                                val totalDur = durationMs()
                                val fraction = (dragStartPosMs.toFloat() / totalDur.coerceAtLeast(1L)).coerceIn(0f, 1f)
                                scrubState.begin(fraction)
                            } else if (abs(dragDelta.y) > abs(dragDelta.x) * 1.5f) {
                                dragMode = if (downPosition.x < size.width / 2f) {
                                    DragMode.VERTICAL_BRIGHTNESS
                                } else {
                                    DragMode.VERTICAL_VOLUME
                                }
                            }
                        }
                    }
                }

                if (hasPassedTouchSlop) {
                    val posChange = pointer.positionChange()
                    when (dragMode) {
                        DragMode.HORIZONTAL_SEEK -> {
                            pointer.consume()
                            val totalDur = durationMs()
                            if (totalDur > 0L) {
                                val deltaX = pointer.position.x - downPosition.x
                                val seekSpan = (totalDur / 10L).coerceIn(60_000L, 900_000L)
                                val deltaRatio = deltaX / size.width
                                val deltaMs = (deltaRatio * seekSpan).toLong()
                                val targetMs = (dragStartPosMs + deltaMs).coerceIn(0L, totalDur)
                                val fraction = targetMs.toFloat() / totalDur.toFloat()
                                scrubState.update(fraction)
                                onHudStateChange(GestureHudState.Seek(targetMs, deltaMs, totalDur))
                            }
                        }
                        DragMode.VERTICAL_BRIGHTNESS -> {
                            pointer.consume()
                            val deltaY = posChange.y
                            val deltaBrightness = -deltaY / 400f
                            activity?.let { act ->
                                val lp = act.window.attributes
                                var cur = lp.screenBrightness
                                if (cur < 0) cur = 0.5f
                                val newBrightness = (cur + deltaBrightness).coerceIn(0.01f, 1.0f)
                                lp.screenBrightness = newBrightness
                                act.window.attributes = lp
                                val pct = (newBrightness * 100).toInt()
                                onHudStateChange(GestureHudState.Brightness(pct, newBrightness))
                            }
                        }
                        DragMode.VERTICAL_VOLUME -> {
                            pointer.consume()
                            val deltaY = posChange.y
                            accumulatedVolumeDragY += -deltaY
                            val volumeThreshold = 30f
                            if (abs(accumulatedVolumeDragY) >= volumeThreshold) {
                                val steps = (accumulatedVolumeDragY / volumeThreshold).toInt()
                                accumulatedVolumeDragY -= steps * volumeThreshold
                                val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val newVol = (curVol + steps).coerceIn(0, maxVolume)
                                if (newVol != curVol) {
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                }
                                val ratio = newVol.toFloat() / maxVolume.toFloat()
                                val pct = (ratio * 100).toInt()
                                onHudStateChange(GestureHudState.Volume(pct, ratio))
                            }
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
                onFastForwardEnd()
                onHudStateChange(GestureHudState.Idle)
                return@awaitEachGesture
            }

            if (isMultiTouch) {
                // Pinch-to-zoom ended cleanly
                return@awaitEachGesture
            }

            if (hasPassedTouchSlop) {
                if (dragMode == DragMode.HORIZONTAL_SEEK) {
                    val totalDur = durationMs()
                    val finalMs = (scrubState.fraction * totalDur).toLong()
                    scrubState.end(scrubState.fraction)
                    onDoubleTapSeek(finalMs - currentPosMs())
                }
                dragMode = DragMode.UNDETERMINED
                scope.launch {
                    delay(600L)
                    onHudStateChange(GestureHudState.Idle)
                }
                return@awaitEachGesture
            }

            // Tap & Double-Tap Processing
            val now = SystemClock.uptimeMillis()
            val timeDelta = now - lastTapTimestamp
            val tapDist = hypot(downPosition.x - lastTapPosition.x, downPosition.y - lastTapPosition.y)
            val isDoubleTapAccumulating = doubleTapJob?.isActive == true && doubleTapSide != null

            if ((timeDelta < 350L && tapDist < 120f) || isDoubleTapAccumulating) {
                singleTapJob?.cancel()
                singleTapJob = null
                lastTapTimestamp = 0L

                if (zoomScale > 1.05f) {
                    // Zoomed in: reset scale & pan to 1.0x
                    onResetZoom()
                    accumulatedSeconds = 0
                    doubleTapSide = null
                    doubleTapJob?.cancel()
                    onHudStateChange(GestureHudState.Idle)
                } else {
                    val screenWidth = size.width
                    if (downPosition.x < screenWidth * 0.4f) {
                        // Left double-tap (Rewind 10s accumulated)
                        if (doubleTapSide == DoubleTapSide.LEFT) {
                            accumulatedSeconds += 10
                        } else {
                            accumulatedSeconds = 10
                            doubleTapSide = DoubleTapSide.LEFT
                        }
                        onHudStateChange(GestureHudState.DoubleTap(DoubleTapSide.LEFT, accumulatedSeconds))

                        doubleTapJob?.cancel()
                        doubleTapJob = scope.launch {
                            delay(650L)
                            onDoubleTapSeek(-accumulatedSeconds * 1000L)
                            accumulatedSeconds = 0
                            doubleTapSide = null
                            delay(400L)
                            onHudStateChange(GestureHudState.Idle)
                        }
                    } else if (downPosition.x > screenWidth * 0.6f) {
                        // Right double-tap (Fast-forward 10s accumulated)
                        if (doubleTapSide == DoubleTapSide.RIGHT) {
                            accumulatedSeconds += 10
                        } else {
                            accumulatedSeconds = 10
                            doubleTapSide = DoubleTapSide.RIGHT
                        }
                        onHudStateChange(GestureHudState.DoubleTap(DoubleTapSide.RIGHT, accumulatedSeconds))

                        doubleTapJob?.cancel()
                        doubleTapJob = scope.launch {
                            delay(650L)
                            onDoubleTapSeek(accumulatedSeconds * 1000L)
                            accumulatedSeconds = 0
                            doubleTapSide = null
                            delay(400L)
                            onHudStateChange(GestureHudState.Idle)
                        }
                    } else {
                        // Center double-tap (Play/Pause)
                        onDoubleTapPlayPause()
                    }
                }
            } else {
                // First tap
                lastTapTimestamp = now
                lastTapPosition = downPosition
                singleTapJob?.cancel()
                singleTapJob = scope.launch {
                    delay(260L)
                    onSingleTap()
                }
            }
        }
    }
}

private enum class DragMode {
    UNDETERMINED,
    VERTICAL_BRIGHTNESS,
    VERTICAL_VOLUME,
    HORIZONTAL_SEEK
}
