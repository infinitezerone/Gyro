package dev.gyro.player.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.engine.model.ResizeMode

@Composable
fun BoxScope.PlayerControlsOverlay(
    isVisible: Boolean,
    isLocked: Boolean,
    title: String,
    isPlaying: Boolean,
    timePosMs: Long,
    durationMs: Long,
    bufferedPosMs: Long,
    hwdec: String,
    fps: Double,
    droppedFrames: Long,
    powerMetrics: PowerMetrics,
    resizeMode: ResizeMode,
    speed: Float,
    scrubState: PlayerScrubState,
    onTogglePlayPause: () -> Unit,
    onToggleLock: () -> Unit,
    onSeekToFraction: (Float) -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCycleResizeMode: () -> Unit,
    onOpenTracksSheet: () -> Unit,
    onEnterPiP: () -> Unit,
    onToggleOrientation: () -> Unit,
    onBack: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 1. Lock Screen Floating Button
    // When locked: Dedicated floating unlock capsule/circle
    // When unlocked: In landscape, floating on left edge; in portrait, placed safely in bottom control row
    AnimatedVisibility(
        visible = isLocked || (isVisible && isLandscape),
        enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.85f),
        exit = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.85f),
        modifier = Modifier
            .align(if (isLandscape) Alignment.CenterStart else Alignment.BottomStart)
            .displayCutoutPadding()
            .padding(
                start = 20.dp,
                bottom = if (isLandscape) 0.dp else 40.dp
            )
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.7f),
            border = BorderStroke(0.8.dp, if (isLocked) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.25f)),
            shadowElevation = 8.dp,
            modifier = Modifier.size(44.dp)
        ) {
            IconButton(onClick = onToggleLock) {
                Icon(
                    imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (isLocked) "Unlock Screen" else "Lock Screen",
                    tint = if (isLocked) Color(0xFFFFB74D) else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }

    if (isLocked) return

    // 2. Top Bar (Title + PiP + Subtitles + Settings)
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(300)),
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                    )
                )
                .displayCutoutPadding()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = title.ifBlank { "Gyro Player" },
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = onEnterPiP) {
                    Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                }

                IconButton(onClick = onOpenTracksSheet) {
                    Icon(Icons.Default.Subtitles, contentDescription = "Subtitles & Audio", tint = Color.White)
                }

                IconButton(onClick = onOpenTracksSheet) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Settings", tint = Color.White)
                }
            }
        }
    }

    // 3. Bottom Bar:
    // When isVisible == true: Full Dual-Row bottom bar with buttons
    // When isVisible == false && scrubState.isActive: ONLY the progress bar and time display (reused for screen drag seek)!
    val showBottomBar = isVisible || scrubState.isActive
    AnimatedVisibility(
        visible = showBottomBar,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(350)),
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                    )
                )
                .displayCutoutPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val isScreenScrubOnly = !isVisible && scrubState.isActive

            if (isScreenScrubOnly) {
                // 拖动屏幕滑动手势时：只显示复用的进度条和时间，极简纯粹，不展示其他按钮
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ExpandingScrubBar(
                        timePosMs = timePosMs,
                        durationMs = durationMs,
                        bufferedPosMs = bufferedPosMs,
                        scrubState = scrubState,
                        onSeekToFraction = onSeekToFraction
                    )
                    val displayPos = (scrubState.fraction * durationMs.coerceAtLeast(1L)).toLong()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(displayPos),
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64B5F6),
                                fontFeatureSettings = "tnum"
                            )
                        )
                        Text(
                            text = formatTime(durationMs),
                            style = TextStyle(
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.7f),
                                fontFeatureSettings = "tnum"
                            )
                        )
                    }
                }
            } else {
                // 标杆播放器现代标准双行布局 (Dual-Row Layout - 横屏与竖屏统一高标准)
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Row 1: 全宽通栏自适应进度条（最大化微调行程，手指跟手极佳）
                    ExpandingScrubBar(
                        timePosMs = timePosMs,
                        durationMs = durationMs,
                        bufferedPosMs = bufferedPosMs,
                        scrubState = scrubState,
                        onSeekToFraction = onSeekToFraction
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Row 2: 人体工学控制行 (40dp 高度)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 播放/暂停键
                        IconButton(
                            onClick = onTogglePlayPause,
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color.White.copy(alpha = 0.18f), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // 快退 10s
                        IconButton(
                            onClick = onRewind10,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "Rewind 10s",
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // 快进 10s
                        IconButton(
                            onClick = onForward10,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "Forward 10s",
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // 时间戳 (05:32 / 24:10)
                        val displayPos = if (scrubState.isActive) {
                            (scrubState.fraction * durationMs.coerceAtLeast(1L)).toLong()
                        } else {
                            timePosMs
                        }
                        Text(
                            text = "${formatTime(displayPos)} / ${formatTime(durationMs)}",
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.95f),
                                fontFeatureSettings = "tnum"
                            )
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        // 竖屏专属：锁屏按键收纳于右侧快捷区（避免悬浮于左侧虎口处造成误触）
                        if (!isLandscape) {
                            IconButton(
                                onClick = onToggleLock,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LockOpen,
                                    contentDescription = "Lock",
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(2.dp))
                        }

                        // 倍速快捷胶囊 (点击即时切换: 0.75x -> 1.0x -> 1.25x -> 1.5x -> 2.0x)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White.copy(alpha = 0.15f),
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onCycleSpeed() }
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${speed}x",
                                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // 画幅快捷胶囊 (点击即时切换: FIT -> CROP -> STRETCH -> 16:9 -> 21:9)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White.copy(alpha = 0.15f),
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onCycleResizeMode() }
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = resizeMode.label,
                                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                )
                            }
                        }

                        // 横屏下展示额外的轨道快捷入口
                        if (isLandscape) {
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(onClick = onOpenTracksSheet, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Subtitles, contentDescription = "Tracks", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // 全屏 / 旋转切换按键
                        IconButton(onClick = onToggleOrientation, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (isLandscape) "Exit Fullscreen" else "Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 现代自适应弹性进度条 (Expanding Scrub Bar)
 */
@Composable
private fun ExpandingScrubBar(
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
        targetValue = if (isHoveredOrScrubbing) 14.dp else 0.dp,
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
        // 1. 背景底轨 (Background Track)
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
