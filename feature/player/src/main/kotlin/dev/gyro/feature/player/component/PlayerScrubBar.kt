package dev.gyro.feature.player.component

import dev.gyro.feature.player.PlayerScrubState
import dev.gyro.feature.player.component.rememberChapterHaptics

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import dev.gyro.core.model.ChapterInfo
import kotlin.math.abs

/**
 * 独立叶子级通栏自适应弹性进度条 (PlayerScrubBar)
 * - 支持常规线性拖拽（无任何强制磁吸），保持线性丝滑手感；
 * - 支持 YouTube 风格按住进度条向上滑动触发精准微调模式；
 * - 尺寸恒定（20dp），避免手势过程中高度形变导致坐标突变与反复横跳；
 * - 浅白网络缓冲条与微光章节刻度；
 * - 进度读取与拖拽状态封装于叶子内，拖拽时仅自身局部重组。
 */
@Composable
fun PlayerScrubBar(
    timePosMs: Long,
    durationMs: Long,
    bufferedPosMs: Long,
    scrubState: PlayerScrubState,
    onSeekToFraction: (Float) -> Unit,
    chapters: List<ChapterInfo> = emptyList(),
    abLoopASeconds: Double = -1.0,
    abLoopBSeconds: Double = -1.0,
    onEnterPreciseSeeking: (Long) -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isHoveredOrScrubbing = scrubState.isActive
    val isPreciseSeeking = scrubState.isPreciseSeeking

    val barHeight by animateDpAsState(
        targetValue = if (isPreciseSeeking) 6.dp else if (isHoveredOrScrubbing) 6.dp else 3.dp,
        animationSpec = tween(150),
        label = "barHeight"
    )
    val thumbSize by animateDpAsState(
        targetValue = if (isPreciseSeeking) 16.dp else if (isHoveredOrScrubbing) 16.dp else 10.dp,
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

    val density = LocalDensity.current
    val pullUpThresholdPx = with(density) { 48.dp.toPx() } // 调高上滑阈值（从 28dp 提升至 48dp），防止横向拖拽时因手指自然微弧弧度导致误触发
    val pullDownThresholdPx = with(density) { 36.dp.toPx() } // 精准面板内下滑收起阈值
    val touchSlopPx = LocalViewConfiguration.current.touchSlop
    val haptics = rememberChapterHaptics()

    val currentOnSeekToFraction by rememberUpdatedState(onSeekToFraction)
    val currentOnEnterPreciseSeeking by rememberUpdatedState(onEnterPreciseSeeking)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentHaptics by rememberUpdatedState(haptics)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp) // 保持恒定触控高度，根除布局抖动反馈环
            .pointerInput(durationMs) {
                if (durationMs <= 0L) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val width = size.width.toFloat()
                    if (width <= 0f) return@awaitEachGesture

                    val startX = down.position.x
                    val startY = down.position.y
                    var isDragging = false
                    var hasTriggeredPreciseSeek = false

                    val currentPlaybackFraction = if (durationMs > 0L) {
                        (timePosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            val curX = change.position.x
                            val curY = change.position.y
                            val deltaX = curX - startX
                            val deltaY = curY - startY

                            // 处于精准微调面板中时，在进度条上向下滑动直接收起面板
                            if (scrubState.isPreciseSeeking && deltaY >= pullDownThresholdPx && deltaY > abs(deltaX)) {
                                change.consume()
                                currentHaptics.tick()
                                currentOnDismiss()
                                break
                            }

                            // 仅当手指产生明确位移时才激活：
                            // 1. 向上轻推：激活精准选帧胶卷，以当前播放进度为基准展开，绝不瞬移跳转到碰触的 X 坐标
                            // 2. 水平拖动超过 TouchSlop：用户意图拖拽快进/快退
                            if (!isDragging) {
                                if (deltaY <= -pullUpThresholdPx) {
                                    isDragging = true
                                    hasTriggeredPreciseSeek = true
                                    change.consume()
                                    scrubState.begin(currentPlaybackFraction)
                                    currentHaptics.tick()
                                    currentOnEnterPreciseSeeking(timePosMs)
                                } else if (abs(deltaX) >= touchSlopPx) {
                                    isDragging = true
                                    change.consume()
                                    val fraction = (curX / width).coerceIn(0f, 1f)
                                    scrubState.begin(fraction)
                                }
                            }

                            if (isDragging) {
                                change.consume()

                                // 拖拽过程中向上滑动进入精准选帧面板（以当前拖拽到的时间为起点展开）
                                if (!hasTriggeredPreciseSeek && deltaY <= -pullUpThresholdPx) {
                                    hasTriggeredPreciseSeek = true
                                    currentHaptics.tick()
                                    val targetMs = if (durationMs > 0L) (scrubState.fraction * durationMs).toLong() else timePosMs
                                    currentOnEnterPreciseSeeking(targetMs)
                                }

                                if (!hasTriggeredPreciseSeek) {
                                    val rawFraction = (curX / width).coerceIn(0f, 1f)
                                    scrubState.update(rawFraction)
                                }
                            }
                        } else {
                            if (isDragging) {
                                change.consume()
                                val finalFraction = scrubState.fraction
                                scrubState.end(finalFraction)
                                // 呼出精准选帧面板的手势结束时仅停留保持面板，不触发盲目跳转
                                if (!hasTriggeredPreciseSeek) {
                                    currentOnSeekToFraction(finalFraction)
                                }
                            } else if (!hasTriggeredPreciseSeek) {
                                // 原地轻点/点击进度条（未发生拖拽且未呼出精准微调），在抬手时触发跳转
                                change.consume()
                                val tapFraction = (startX / width).coerceIn(0f, 1f)
                                currentHaptics.tick()
                                scrubState.begin(tapFraction)
                                scrubState.end(tapFraction)
                                currentOnSeekToFraction(tapFraction)
                            }
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

        // 2. 缓冲进度 (Buffered Track)
        if (bufferedRatio > 0f) {
            Box(
                modifier = Modifier
                    .width(totalWidth * bufferedRatio)
                    .height(barHeight)
                    .clip(RoundedCornerShape(barHeight / 2))
                    .background(Color.White.copy(alpha = 0.45f))
            )
        }

        // 3. 播放进度 (Active Played Track)
        if (playedRatio > 0f) {
            Box(
                modifier = Modifier
                    .width(totalWidth * playedRatio)
                    .height(barHeight)
                    .clip(RoundedCornerShape(barHeight / 2))
                    .background(Color(0xFF64B5F6))
            )
        }

        // 4. 微光章节刻度标线 (仅视觉区分章节，绝不进行物理磁吸)
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
                    drawLine(
                        color = Color(0xFF64B5F6).copy(alpha = 0.55f),
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = glowWidth,
                        cap = StrokeCap.Round
                    )
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
        if (thumbSize > 0.dp && totalWidth > thumbSize) {
            val halfThumb = thumbSize / 2
            val thumbOffset = totalWidth * playedRatio - halfThumb
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

        // 6. A-B 循环标记与循环区间高亮（琥珀色，与章节刻度区分）
        if (durationMs > 0L && (abLoopASeconds >= 0.0 || abLoopBSeconds >= 0.0)) {
            val aFraction = if (abLoopASeconds >= 0.0) {
                (abLoopASeconds * 1000.0 / durationMs).toFloat().coerceIn(0f, 1f)
            } else {
                null
            }
            val bFraction = if (abLoopBSeconds >= 0.0) {
                (abLoopBSeconds * 1000.0 / durationMs).toFloat().coerceIn(0f, 1f)
            } else {
                null
            }
            if (aFraction != null && bFraction != null) {
                Box(
                    modifier = Modifier
                        .offset(x = totalWidth * minOf(aFraction, bFraction))
                        .width(totalWidth * kotlin.math.abs(aFraction - bFraction))
                        .height(barHeight)
                        .clip(RoundedCornerShape(barHeight / 2))
                        .background(Color(0xFFFFB74D).copy(alpha = 0.30f))
                )
            }
            listOfNotNull(aFraction, bFraction).forEach { frac ->
                Canvas(
                    modifier = Modifier
                        .offset(x = totalWidth * frac)
                        .width(3.dp)
                        .height(barHeight + 4.dp)
                ) {
                    drawLine(
                        color = Color(0xFFFFB74D),
                        start = Offset(size.width / 2, 0f),
                        end = Offset(size.width / 2, size.height),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
