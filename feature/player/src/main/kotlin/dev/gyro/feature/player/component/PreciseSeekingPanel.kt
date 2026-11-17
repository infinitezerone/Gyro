package dev.gyro.feature.player.component

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.model.ChapterInfo
import dev.gyro.core.model.time.formatDuration
import dev.gyro.feature.player.PlayerScrubState

/**
 * YouTube 风格精准选帧胶片微调面板 (PreciseSeekingPanel)
 *
 * 纯声明式 Compose UI 组件：
 * - 绝不在此处执行 I/O、解码、节流计时或句柄创建，纯粹只响应状态并派发用户事件；
 * - 拖动进度条或滑动缩略图胶卷时，派发 [onPreciseSeek] 事件给业务层（ViewModel）调度；
 * - 缩略图由业务层通过 [loadThumbnail] 提供。
 */
@Composable
fun PreciseSeekingPanel(
    scrubState: PlayerScrubState,
    currentTimeMs: Long,
    durationMs: Long,
    bufferedPosMs: Long,
    chapters: List<ChapterInfo>,
    fallbackBitmap: Bitmap?,
    onSeekToFraction: (Float) -> Unit,
    onPreciseSeek: (targetMs: Long, isScrubbing: Boolean) -> Unit,
    loadThumbnail: (suspend (timeMs: Long) -> Bitmap?)? = null,
    onCancelRevert: () -> Unit = {},
    onDismiss: () -> Unit = {},
    videoAspectRatio: Float = 16f / 9f,
    abLoopASeconds: Double = -1.0,
    abLoopBSeconds: Double = -1.0,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = scrubState.isPreciseSeeking,
        enter = fadeIn(animationSpec = tween(180)) + slideInVertically(animationSpec = tween(220)) { it },
        exit = fadeOut(animationSpec = tween(150)) + slideOutVertically(animationSpec = tween(180)) { it },
        modifier = modifier
    ) {
        val coroutineScope = rememberCoroutineScope()
        val currentOnDismiss by rememberUpdatedState(onDismiss)
        val pullDownThresholdPx = with(LocalDensity.current) { 36.dp.toPx() }

        // 关键帧自适应分档：契合常规视频 3~5s 关键帧间隔，大幅减少相同重复帧并实现极速渲染
        val stepMs = remember(durationMs) {
            when {
                durationMs <= 15_000L -> 1_000L       // <= 15s 短片: 1s 一张
                durationMs <= 300_000L -> 3_000L      // <= 5 min: 3s 一张
                durationMs <= 1_800_000L -> 5_000L    // 5 ~ 30 min: 5s 一张
                else -> 10_000L                       // > 30 min: 10s 一张
            }
        }
        val totalSteps = remember(durationMs, stepMs) {
            if (durationMs <= 0L) 1 else (durationMs / stepMs).toInt() + 1
        }

        val itemHeightDp = 70.dp
        val effectiveAspect = if (videoAspectRatio > 0f) videoAspectRatio.coerceIn(0.5f, 2.5f) else (16f / 9f)
        val itemWidthDp = (itemHeightDp.value * effectiveAspect).dp
        val slotWidthPx = with(LocalDensity.current) { itemWidthDp.toPx() }
        val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
        val contentPaddingHorizontal = (screenWidthDp / 2 - itemWidthDp / 2).coerceAtLeast(0.dp)

        val initialIndex = remember {
            if (stepMs > 0L) (currentTimeMs / stepMs).toInt().coerceIn(0, (totalSteps - 1).coerceAtLeast(0)) else 0
        }
        val initialOffset = remember {
            if (stepMs > 0L) ((currentTimeMs % stepMs).toFloat() / stepMs * slotWidthPx).toInt() else 0
        }
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = initialIndex,
            initialFirstVisibleItemScrollOffset = initialOffset
        )

        // 首次进入精准微调时，滚动对齐至当前播放位置
        LaunchedEffect(scrubState.isPreciseSeeking) {
            if (scrubState.isPreciseSeeking && durationMs > 0L) {
                val targetIndex = (currentTimeMs / stepMs).toInt().coerceIn(0, totalSteps - 1)
                val offsetInsideStep = ((currentTimeMs % stepMs).toFloat() / stepMs * slotWidthPx).toInt()
                listState.scrollToItem(targetIndex, offsetInsideStep)
            }
        }

        // 1. 拖动上方进度条时，联动水平滚动缩略图胶卷条
        LaunchedEffect(scrubState.fraction, scrubState.isActive) {
            if (scrubState.isActive && !listState.isScrollInProgress && durationMs > 0L) {
                val targetMs = (scrubState.fraction * durationMs).toLong()
                val targetIndex = (targetMs / stepMs).toInt().coerceIn(0, totalSteps - 1)
                val offsetInsideStep = ((targetMs % stepMs).toFloat() / stepMs * slotWidthPx).toInt()
                listState.scrollToItem(targetIndex, offsetInsideStep)
            }
        }

        // 2. 缩略图胶卷条当前指示的时间（中心游标对齐的时间）
        val currentScrollTimeMs by remember {
            derivedStateOf {
                val firstIdx = listState.firstVisibleItemIndex
                val firstOffset = listState.firstVisibleItemScrollOffset
                val totalScrollPx = firstIdx * slotWidthPx + firstOffset
                ((totalScrollPx / slotWidthPx) * stepMs).toLong().coerceIn(0L, durationMs)
            }
        }

        // 面板当前生效的展示时间：
        // - 拖动上方进度条时由 scrubState.fraction 驱动
        // - 滑动或静止在胶卷时由胶卷当前对齐的中心时间 currentScrollTimeMs 驱动
        val effectiveTimeMs = if (scrubState.isActive && durationMs > 0L) {
            (scrubState.fraction * durationMs).toLong()
        } else {
            currentScrollTimeMs
        }

        val activeChapter = remember(chapters, effectiveTimeMs) {
            chapters.lastOrNull { it.timeMs <= effectiveTimeMs }
        }

        // 滑动胶卷条时派发精准 Seek 事件给业务层统一调度（速度自适应抑制 + 停稳精准 Seek）
        LaunchedEffect(currentScrollTimeMs, listState.isScrollInProgress) {
            if (!scrubState.isActive && durationMs > 0L) {
                val frac = (currentScrollTimeMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                scrubState.update(frac)
                onPreciseSeek(currentScrollTimeMs, listState.isScrollInProgress)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.50f),
                            Color.Black.copy(alpha = 0.85f)
                        )
                    )
                )
                .pointerInput(Unit) {
                    var totalDragY = 0f
                    detectVerticalDragGestures(
                        onDragStart = { totalDragY = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            totalDragY += dragAmount
                            // 在胶卷面板上向下滑动超过阈值时收起微调面板
                            if (totalDragY >= pullDownThresholdPx) {
                                change.consume()
                                totalDragY = 0f
                                currentOnDismiss()
                            }
                        }
                    )
                }
                .displayCutoutPadding()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            // 1. 顶行：章节提示（若有） + 精准时间码 + 取消/关闭按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activeChapter != null) {
                    Text(
                        text = activeChapter.title,
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF64B5F6),
                            shadow = Shadow(color = Color.Black, blurRadius = 4f)
                        ),
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // 精准时间码
                Text(
                    text = formatDuration(effectiveTimeMs, padHours = (durationMs >= 3600_000L)),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFeatureSettings = "tnum",
                        shadow = Shadow(color = Color.Black, blurRadius = 6f)
                    )
                )
                Text(
                    text = " / " + formatDuration(durationMs, padHours = (durationMs >= 3600_000L)),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color.White.copy(alpha = 0.65f),
                        fontFeatureSettings = "tnum",
                        shadow = Shadow(color = Color.Black, blurRadius = 6f)
                    ),
                    modifier = Modifier.padding(end = 6.dp)
                )

                IconButton(
                    onClick = onCancelRevert,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // 2. 进度条直接位于缩略图胶卷的正上方（0 间距紧贴胶卷）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .offset(y = 2.dp)
            ) {
                PlayerScrubBar(
                    timePosMs = effectiveTimeMs,
                    durationMs = durationMs,
                    bufferedPosMs = bufferedPosMs,
                    scrubState = scrubState,
                    onSeekToFraction = { frac ->
                        onSeekToFraction(frac)
                        if (durationMs > 0L) {
                            val targetMs = (frac * durationMs).toLong()
                            val targetIndex = (targetMs / stepMs).toInt().coerceIn(0, totalSteps - 1)
                            val offsetInsideStep = ((targetMs % stepMs).toFloat() / stepMs * slotWidthPx).toInt()
                            coroutineScope.launch {
                                listState.scrollToItem(targetIndex, offsetInsideStep)
                            }
                        }
                    },
                    chapters = chapters,
                    abLoopASeconds = abLoopASeconds,
                    abLoopBSeconds = abLoopBSeconds,
                    onDismiss = currentOnDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 3. 一排连续可横向滚动的无缝缩略图胶卷带 (Filmstrip，0 间距连续无缝拼接)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeightDp),
                contentAlignment = Alignment.Center
            ) {
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = contentPaddingHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(totalSteps) { idx ->
                        val itemTimeMs = (idx * stepMs).coerceIn(0L, durationMs)

                        FilmstripThumbnailCard(
                            timeMs = itemTimeMs,
                            fallbackBitmap = fallbackBitmap,
                            cardWidth = itemWidthDp,
                            cardHeight = itemHeightDp,
                            loadThumbnail = loadThumbnail,
                            onClick = {
                                coroutineScope.launch {
                                    listState.scrollToItem(idx, 0)
                                }
                                onPreciseSeek(itemTimeMs, false)
                            }
                        )
                    }
                }

                // 中心高亮标尺指针线（固定在屏幕正中，游标对准帧即为当前画面）
                Canvas(
                    modifier = Modifier
                        .width(3.dp)
                        .height(itemHeightDp + 6.dp)
                ) {
                    drawLine(
                        color = Color(0xFF64B5F6),
                        start = Offset(size.width / 2, 0f),
                        end = Offset(size.width / 2, size.height),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

/**
 * 胶卷单张缩略图纯 UI 展示卡片（0 边距连续无缝拼接，无多余边框）
 */
@Composable
private fun FilmstripThumbnailCard(
    timeMs: Long,
    fallbackBitmap: Bitmap?,
    cardWidth: Dp,
    cardHeight: Dp = 70.dp,
    loadThumbnail: (suspend (timeMs: Long) -> Bitmap?)?,
    onClick: () -> Unit
) {
    val frameBitmap by produceState<Bitmap?>(initialValue = fallbackBitmap, key1 = timeMs, key2 = loadThumbnail) {
        if (loadThumbnail != null) {
            value = loadThumbnail(timeMs) ?: fallbackBitmap
        }
    }

    Box(
        modifier = Modifier
            .size(width = cardWidth, height = cardHeight)
            .background(Color.Black)
            .clickable(onClick = onClick)
    ) {
        val bitmap = frameBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit, // 严格按照视频原本宽高比完整呈现，绝不裁剪失真
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E1F29)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
