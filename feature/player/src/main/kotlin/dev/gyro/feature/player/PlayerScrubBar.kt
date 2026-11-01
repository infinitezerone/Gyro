package dev.gyro.feature.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 独立叶子级通栏自适应弹性进度条 (PlayerScrubBar)
 * 具备静止态 (3dp) 到拖拽态 (6dp) 的平滑膨胀动画与高响应度触摸判定。
 * 进度读取与拖拽状态封装于叶子内，拖拽时仅自身重组，杜绝波及外层控制栏。
 */
@Composable
fun PlayerScrubBar(
    timePosMs: Long,
    durationMs: Long,
    bufferedPosMs: Long,
    scrubState: PlayerScrubState,
    onSeekToFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val isHoveredOrScrubbing = scrubState.isActive
    val barHeight by animateDpAsState(
        targetValue = if (isHoveredOrScrubbing) 6.dp else 3.dp,
        animationSpec = tween(150),
        label = "barHeight"
    )
    val thumbSize by animateDpAsState(
        targetValue = if (isHoveredOrScrubbing) 16.dp else 10.dp,
        animationSpec = tween(150),
        label = "thumbSize"
    )

    val validDuration = durationMs.coerceAtLeast(1L)
    val playedRatio = if (scrubState.isActive) {
        scrubState.fraction
    } else {
        (timePosMs.toFloat() / validDuration.toFloat()).coerceIn(0f, 1f)
    }
    val bufferedRatio = (bufferedPosMs.toFloat() / validDuration.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val width = size.width.toFloat()
                    if (width > 0f) {
                        val fraction = (down.position.x / width).coerceIn(0f, 1f)
                        scrubState.begin(fraction)
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            change.consume()
                            val fraction = (change.position.x / width).coerceIn(0f, 1f)
                            scrubState.update(fraction)
                        } else {
                            change.consume()
                            val finalFraction = scrubState.fraction
                            scrubState.end(finalFraction)
                            onSeekToFraction(finalFraction)
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // 1. 底轨 (Background Track)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(Color.White.copy(alpha = 0.22f))
        )

        // 2. 缓冲进度 (Buffered Track)
        Box(
            modifier = Modifier
                .fillMaxWidth(bufferedRatio)
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(Color.White.copy(alpha = 0.45f))
        )

        // 3. 播放进度 (Active Played Track)
        Box(
            modifier = Modifier
                .fillMaxWidth(playedRatio)
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(Color(0xFF64B5F6))
        )

        // 4. 动态滑块 (Thumb)
        if (thumbSize > 0.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(playedRatio)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterEnd
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 4.dp,
                    border = BorderStroke(2.dp, Color(0xFF1976D2)),
                    modifier = Modifier.size(thumbSize)
                ) {}
            }
        }
    }
}
