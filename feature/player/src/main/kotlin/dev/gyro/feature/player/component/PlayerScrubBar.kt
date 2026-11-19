package dev.gyro.feature.player.component

import dev.gyro.feature.player.PlayerScrubState
import dev.gyro.feature.player.component.rememberChapterHaptics

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import dev.gyro.core.model.ChapterInfo
import kotlin.math.abs

/**
 * 独立叶子级通栏自适应弹性进度条 (PlayerScrubBar)
 * - 常态：纯白 3.5dp 细轨 + 无描边 10dp 纯白圆球；章节采用 2dp 断口分节；
 * - 拖拽 / 精准微调态：圆球融隐、轨道弹簧胀大为 11dp 粗纯白长条胶囊；
 * - 尺寸热区恒定（20dp），支持上滑进入 YouTube 风格精准选帧胶片模式。
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
    val isExpanded = isHoveredOrScrubbing || isPreciseSeeking

    val barHeight by animateDpAsState(
        targetValue = if (isExpanded) 11.dp else 3.5.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "barHeight"
    )
    val thumbAlpha by animateFloatAsState(
        targetValue = if (isExpanded) 0f else 1f,
        animationSpec = tween(durationMillis = if (isExpanded) 110 else 160),
        label = "thumbAlpha"
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

    val currentTimePosMs by rememberUpdatedState(timePosMs)
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

                    // Whether the finger landed while the precise panel was already open. Inside the
                    // panel a drag that ends in an upward flick must keep the position the user just
                    // scrubbed to; the pull-up branch below exists to undo an accidental horizontal
                    // drag that turns into "open precise seeking", which cannot happen once it is open.
                    val startedInsidePreciseSeeking = scrubState.isPreciseSeeking

                    val currentPlaybackFraction = if (durationMs > 0L) {
                        (currentTimePosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
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
                                if (!startedInsidePreciseSeeking && deltaY <= -pullUpThresholdPx) {
                                    isDragging = true
                                    hasTriggeredPreciseSeek = true
                                    change.consume()
                                    scrubState.begin(currentPlaybackFraction)
                                    currentHaptics.tick()
                                    currentOnEnterPreciseSeeking(currentTimePosMs)
                                } else if (abs(deltaX) >= touchSlopPx) {
                                    isDragging = true
                                    change.consume()
                                    val fraction = (curX / width).coerceIn(0f, 1f)
                                    scrubState.begin(fraction)
                                }
                            }

                            if (isDragging) {
                                change.consume()

                                // 无论上滑前是否不小心横向滑动了进度条，只要判定为上滑激活微调，一律恢复至原始播放进度。
                                // 但面板已经打开时不成立：那时用户是在面板内拖动，上移不应把刚拖到的位置打回起点。
                                if (!hasTriggeredPreciseSeek &&
                                    !startedInsidePreciseSeeking &&
                                    deltaY <= -pullUpThresholdPx
                                ) {
                                    hasTriggeredPreciseSeek = true
                                    currentHaptics.tick()
                                    scrubState.update(currentPlaybackFraction)
                                    currentOnEnterPreciseSeeking(currentTimePosMs)
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
        val segments = remember(chapterFractions) { buildScrubBarSegments(chapterFractions) }
        val segmentPath = remember { Path() }
        val abFractions = remember(durationMs, abLoopASeconds, abLoopBSeconds) {
            if (durationMs <= 0L) {
                emptyList()
            } else {
                listOfNotNull(
                    if (abLoopASeconds >= 0.0) (abLoopASeconds * 1000.0 / durationMs).toFloat().coerceIn(0f, 1f) else null,
                    if (abLoopBSeconds >= 0.0) (abLoopBSeconds * 1000.0 / durationMs).toFloat().coerceIn(0f, 1f) else null
                )
            }
        }
        val abMinFrac = if (abFractions.size == 2) minOf(abFractions[0], abFractions[1]) else null
        val abMaxFrac = if (abFractions.size == 2) maxOf(abFractions[0], abFractions[1]) else null

        // 1. 分节胶囊纯白轨道：常态 3.5dp，拖拽时丝滑胀大为 11dp 粗胶囊矩形，章节处保留 2dp 断口
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val gapHalfPx = 1.dp.toPx()
            val outerR = CornerRadius(h / 2f)
            val innerR = CornerRadius(minOf(h / 2f, 1.5.dp.toPx()))

            for ((segIndex, segment) in segments.withIndex()) {
                val (startFrac, endFrac) = segment
                val leftPx = if (segIndex == 0) 0f else startFrac * w + gapHalfPx
                val rightPx = if (segIndex == segments.lastIndex) w else endFrac * w - gapHalfPx
                if (rightPx <= leftPx) continue

                val leftRadius = if (segIndex == 0) outerR else innerR
                val rightRadius = if (segIndex == segments.lastIndex) outerR else innerR

                segmentPath.reset()
                segmentPath.addRoundRect(
                    RoundRect(
                        left = leftPx,
                        top = 0f,
                        right = rightPx,
                        bottom = h,
                        topLeftCornerRadius = leftRadius,
                        bottomLeftCornerRadius = leftRadius,
                        topRightCornerRadius = rightRadius,
                        bottomRightCornerRadius = rightRadius
                    )
                )

                clipPath(segmentPath) {
                    // 底槽
                    drawRect(
                        color = Color.White.copy(alpha = 0.24f),
                        topLeft = Offset(leftPx, 0f),
                        size = Size(rightPx - leftPx, h)
                    )

                    // 网络缓冲进度
                    val bufRight = (w * bufferedRatio).coerceIn(leftPx, rightPx)
                    if (bufRight > leftPx) {
                        drawRect(
                            color = Color.White.copy(alpha = 0.44f),
                            topLeft = Offset(leftPx, 0f),
                            size = Size(bufRight - leftPx, h)
                        )
                    }

                    // 纯白已播进度（在胶囊内保持平直垂直前沿，配合拖拽粗条感）
                    val playRight = (w * playedRatio).coerceIn(leftPx, rightPx)
                    if (playRight > leftPx) {
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(leftPx, 0f),
                            size = Size(playRight - leftPx, h)
                        )
                    }

                    // A-B 循环高亮区间（保持克制琥珀色）
                    if (abMinFrac != null && abMaxFrac != null) {
                        val abLeft = (w * abMinFrac).coerceIn(leftPx, rightPx)
                        val abRight = (w * abMaxFrac).coerceIn(leftPx, rightPx)
                        if (abRight > abLeft) {
                            drawRect(
                                color = Color(0xFFFFB74D).copy(alpha = 0.35f),
                                topLeft = Offset(abLeft, 0f),
                                size = Size(abRight - abLeft, h)
                            )
                        }
                    }
                }
            }
        }

        // 2. A-B 循环端点琥珀标记
        if (abFractions.isNotEmpty() && totalWidth > 2.5.dp) {
            abFractions.forEach { frac ->
                val tickOffset = (totalWidth * frac - 1.25.dp)
                    .coerceIn(0.dp, (totalWidth - 2.5.dp).coerceAtLeast(0.dp))
                Canvas(
                    modifier = Modifier
                        .offset(x = tickOffset)
                        .width(2.5.dp)
                        .height(barHeight + 4.dp)
                ) {
                    drawLine(
                        color = Color(0xFFFFB74D),
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // 3. 无描边纯白小圆球（仅常态显示，拖拽胀大时微缩融隐进粗胶囊条中）
        val thumbDiameter = 10.dp
        if (thumbAlpha > 0.01f && totalWidth > thumbDiameter) {
            val thumbOffset = (totalWidth * playedRatio - thumbDiameter / 2)
                .coerceIn(0.dp, (totalWidth - thumbDiameter).coerceAtLeast(0.dp))
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(thumbDiameter)
                    .graphicsLayer {
                        alpha = thumbAlpha
                        val scale = 0.55f + 0.45f * thumbAlpha
                        scaleX = scale
                        scaleY = scale
                    }
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 3.dp,
                    modifier = Modifier.fillMaxSize()
                ) {}
            }
        }
    }
}

internal fun buildScrubBarSegments(chapterFractions: List<Float>): List<Pair<Float, Float>> {
    val splits = mutableListOf<Float>()
    for (frac in chapterFractions.sorted()) {
        if (frac <= 0.001f || frac >= 0.999f) continue
        if (splits.isEmpty() || frac - splits.last() >= 0.005f) {
            splits.add(frac)
        }
    }
    if (splits.isEmpty()) return listOf(0f to 1f)
    return buildList(splits.size + 1) {
        var prev = 0f
        for (split in splits) {
            add(prev to split)
            prev = split
        }
        add(prev to 1f)
    }
}
