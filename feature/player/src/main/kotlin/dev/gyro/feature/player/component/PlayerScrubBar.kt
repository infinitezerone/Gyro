package dev.gyro.feature.player.component

import dev.gyro.feature.player.PlayerScrubState

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.gyro.core.model.ChapterInfo

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
    chapters: List<ChapterInfo> = emptyList(),
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

    val chapterFractions = remember(chapters, durationMs) {
        if (durationMs <= 0L || chapters.isEmpty()) {
            emptyList()
        } else {
            val durFloat = durationMs.toFloat()
            chapters.mapNotNull { ch ->
                if (ch.timeMs > 0L && ch.timeMs < durationMs) {
                    (ch.timeMs.toFloat() / durFloat).coerceIn(0f, 1f)
                } else null
            }
        }
    }

    val playedRatio = if (scrubState.isActive) {
        scrubState.fraction
    } else if (durationMs > 0L) {
        (timePosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val bufferedRatio = if (durationMs > 0L) {
        (bufferedPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(durationMs) {
                if (durationMs <= 0L) return@pointerInput
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
        val totalWidth = maxWidth

        // 1. 底轨 (Background Track)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(Color.White.copy(alpha = 0.22f))
        )

        // 2. 缓冲进度 (Buffered Track) - 大于 0 时才绘制，避免 0 宽度圆角锯齿
        if (bufferedRatio > 0f) {
            Box(
                modifier = Modifier
                    .width(totalWidth * bufferedRatio)
                    .height(barHeight)
                    .clip(RoundedCornerShape(barHeight / 2))
                    .background(Color.White.copy(alpha = 0.45f))
            )
        }

        // 3. 播放进度 (Active Played Track) - 大于 0 时才绘制，避免 0 进度条显示为白点或异常挤压
        if (playedRatio > 0f) {
            Box(
                modifier = Modifier
                    .width(totalWidth * playedRatio)
                    .height(barHeight)
                    .clip(RoundedCornerShape(barHeight / 2))
                    .background(Color(0xFF64B5F6))
            )
        }

        // 4. 微光章节刻度标线 (Chapter Demarcation Micro-Ticks)
        if (chapterFractions.isNotEmpty()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight + 2.dp)
            ) {
                val w = size.width
                val h = size.height
                if (w <= 0f) return@Canvas
                val coreWidth = 2.dp.toPx()
                val glowWidth = 4.dp.toPx()

                for (frac in chapterFractions) {
                    val x = frac * w
                    // 柔和微光外晕 (Subtle Cyan Glow Aura)
                    drawLine(
                        color = Color(0xFF64B5F6).copy(alpha = 0.55f),
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = glowWidth,
                        cap = StrokeCap.Round
                    )
                    // 锐利高对比度核心标线 (Crisp Core Tick)
                    drawLine(
                        color = Color.White,
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = coreWidth,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // 5. 动态滑块 (Thumb)
        // 沿轨道平滑运动：在 0 时对齐 0dp（完整处于左端圆角内，不向负坐标凸出越界或被裁剪）；在 1 时对齐 (totalWidth - thumbSize)
        if (thumbSize > 0.dp && totalWidth > thumbSize) {
            val thumbOffset = ((totalWidth - thumbSize) * playedRatio).coerceIn(0.dp, totalWidth - thumbSize)
            Surface(
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 4.dp,
                border = BorderStroke(2.dp, Color(0xFF1976D2)),
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(thumbSize)
            ) {}
        }
    }
}
