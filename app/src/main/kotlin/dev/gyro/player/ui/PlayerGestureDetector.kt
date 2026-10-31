package dev.gyro.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class DoubleTapSide {
    LEFT,
    RIGHT
}

sealed class GestureHudState {
    data object Idle : GestureHudState()
    data class Volume(val percent: Int) : GestureHudState()
    data class Brightness(val percent: Int) : GestureHudState()
    data class Seek(val targetMs: Long, val deltaMs: Long, val totalMs: Long) : GestureHudState()
    data class DoubleTap(val side: DoubleTapSide, val seconds: Int) : GestureHudState()
    data class Speed(val speed: Float) : GestureHudState()
}

/**
 * High-performance gesture detector inspired by MX Player, Next Player, and MiniBgm.
 * - Single source of truth for scrubbing with leaf-down reading
 * - Debounced accumulation for double-tap seek (YouTube style)
 * - Android 14/15 system gesture edge exclusion (24dp)
 * - Touch lock support
 */
@Composable
fun Modifier.playerGestures(
    context: Context,
    isLocked: Boolean,
    isPlaying: Boolean,
    currentPosMs: () -> Long,
    durationMs: () -> Long,
    scrubState: PlayerScrubState,
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
    var accumulatedSeconds by remember { mutableIntStateOf(0) }
    var doubleTapSide by remember { mutableStateOf<DoubleTapSide?>(null) }

    // Drag mode tracking
    var dragMode by remember { mutableStateOf<DragMode?>(null) }
    var dragStartPosMs by remember { mutableLongStateOf(0L) }
    var dragAccumulatedX by remember { mutableFloatStateOf(0f) }
    var dragAccumulatedY by remember { mutableFloatStateOf(0f) }

    return this
        // Layer 1: Tap & Long-Press
        .pointerInput(isLocked) {
            if (isLocked) {
                detectTapGestures(onTap = { onSingleTap() })
                return@pointerInput
            }

            detectTapGestures(
                onTap = {
                    onSingleTap()
                },
                onDoubleTap = { offset ->
                    val screenWidth = size.width
                    if (offset.x < screenWidth * 0.4f) {
                        // Left double-tap (Rewind)
                        if (doubleTapSide == DoubleTapSide.LEFT) {
                            accumulatedSeconds += 10
                        } else {
                            accumulatedSeconds = 10
                            doubleTapSide = DoubleTapSide.LEFT
                        }
                        onHudStateChange(GestureHudState.DoubleTap(DoubleTapSide.LEFT, accumulatedSeconds))

                        doubleTapJob?.cancel()
                        doubleTapJob = scope.launch {
                            delay(650L) // Wait for user to stop tapping
                            onDoubleTapSeek(-accumulatedSeconds * 1000L)
                            accumulatedSeconds = 0
                            doubleTapSide = null
                            delay(400L)
                            onHudStateChange(GestureHudState.Idle)
                        }
                    } else if (offset.x > screenWidth * 0.6f) {
                        // Right double-tap (Fast-forward)
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
                },
                onPress = {
                    val pressStart = System.currentTimeMillis()
                    val released = tryAwaitRelease()
                    val duration = System.currentTimeMillis() - pressStart
                    if (duration >= 450L) {
                        // Long press ended
                        onFastForwardEnd()
                        onHudStateChange(GestureHudState.Idle)
                    }
                }
            )
        }
        // Layer 2: Directional Drags (Brightness, Volume, Horizontal Seek)
        .pointerInput(isLocked) {
            if (isLocked) return@pointerInput

            detectDragGestures(
                onDragStart = { offset ->
                    // System edge gesture protection
                    if (offset.x < edgeThresholdPx || offset.x > size.width - edgeThresholdPx) {
                        dragMode = null
                        return@detectDragGestures
                    }
                    dragMode = DragMode.UNDETERMINED
                    dragAccumulatedX = 0f
                    dragAccumulatedY = 0f
                    dragStartPosMs = currentPosMs()
                },
                onDrag = { change, dragAmount ->
                    if (dragMode == null) return@detectDragGestures

                    dragAccumulatedX += dragAmount.x
                    dragAccumulatedY += dragAmount.y

                    // Determine gesture orientation if undetermined
                    if (dragMode == DragMode.UNDETERMINED) {
                        val absX = abs(dragAccumulatedX)
                        val absY = abs(dragAccumulatedY)
                        if (absX > 25f && absX > absY * 1.5f) {
                            dragMode = DragMode.HORIZONTAL_SEEK
                            val currentFraction = (dragStartPosMs.toFloat() / durationMs().coerceAtLeast(1L)).coerceIn(0f, 1f)
                            scrubState.begin(currentFraction)
                        } else if (absY > 25f && absY > absX * 1.5f) {
                            dragMode = if (change.position.x < size.width / 2f) {
                                DragMode.VERTICAL_BRIGHTNESS
                            } else {
                                DragMode.VERTICAL_VOLUME
                            }
                        }
                    }

                    // Handle active drag mode
                    when (dragMode) {
                        DragMode.VERTICAL_BRIGHTNESS -> {
                            val delta = -dragAmount.y / 400f
                            activity?.let { act ->
                                val lp = act.window.attributes
                                var cur = lp.screenBrightness
                                if (cur < 0) cur = 0.5f
                                val newBrightness = (cur + delta).coerceIn(0.01f, 1.0f)
                                lp.screenBrightness = newBrightness
                                act.window.attributes = lp
                                onHudStateChange(GestureHudState.Brightness((newBrightness * 100).toInt()))
                            }
                        }
                        DragMode.VERTICAL_VOLUME -> {
                            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val step = if (-dragAmount.y > 0) 1 else -1
                            val newVol = (curVol + step).coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            val pct = (newVol * 100 / maxVolume).coerceIn(0, 100)
                            onHudStateChange(GestureHudState.Volume(pct))
                        }
                        DragMode.HORIZONTAL_SEEK -> {
                            val totalDuration = durationMs()
                            if (totalDuration > 0L) {
                                // Scale: full screen swipe = 10% of video duration, clamped between 60s and 15m
                                val seekSpan = (totalDuration / 10L).coerceIn(60_000L, 900_000L)
                                val deltaRatio = dragAccumulatedX / size.width
                                val deltaMs = (deltaRatio * seekSpan).toLong()
                                val targetMs = (dragStartPosMs + deltaMs).coerceIn(0L, totalDuration)
                                val fraction = targetMs.toFloat() / totalDuration.toFloat()
                                scrubState.update(fraction)
                                onHudStateChange(GestureHudState.Seek(targetMs, deltaMs, totalDuration))
                            }
                        }
                        else -> {}
                    }
                },
                onDragEnd = {
                    if (dragMode == DragMode.HORIZONTAL_SEEK) {
                        val totalDuration = durationMs()
                        val finalMs = (scrubState.fraction * totalDuration).toLong()
                        scrubState.end(scrubState.fraction)
                        onDoubleTapSeek(finalMs - currentPosMs())
                    }
                    dragMode = null
                    scope.launch {
                        delay(600L)
                        onHudStateChange(GestureHudState.Idle)
                    }
                },
                onDragCancel = {
                    if (dragMode == DragMode.HORIZONTAL_SEEK) {
                        scrubState.cancel()
                    }
                    dragMode = null
                    onHudStateChange(GestureHudState.Idle)
                }
            )
        }
}

private enum class DragMode {
    UNDETERMINED,
    VERTICAL_BRIGHTNESS,
    VERTICAL_VOLUME,
    HORIZONTAL_SEEK
}
