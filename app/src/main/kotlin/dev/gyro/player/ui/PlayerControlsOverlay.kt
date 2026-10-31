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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
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
    onOpenTracksSheet: () -> Unit,
    onEnterPiP: () -> Unit,
    onToggleOrientation: () -> Unit,
    onBack: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 1. Lock Screen Floating Button (Accessible when controls are shown or screen is locked)
    AnimatedVisibility(
        visible = isVisible || isLocked,
        enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.85f),
        exit = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.85f),
        modifier = Modifier
            .align(Alignment.CenterStart)
            .displayCutoutPadding()
            .padding(start = 20.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.65f),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f)),
            shadowElevation = 6.dp,
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

    // 2. Top Bar (Clean Title + Actions, visible when controls are toggled)
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

                IconButton(onClick = onToggleOrientation) {
                    Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate", tint = Color.White)
                }

                IconButton(onClick = onOpenTracksSheet) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Settings", tint = Color.White)
                }
            }
        }
    }

    // 3. Bottom Bar:
    // When isVisible == true: Full bottom bar with buttons
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
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            val isScreenScrubOnly = !isVisible && scrubState.isActive

            if (isScreenScrubOnly) {
                // 拖动屏幕时：只显示进度条和时间，其他的不显示，极简纯粹！
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
            } else if (isLandscape) {
                // 横屏成熟双行布局 (Dual-Row Layout)
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Row 1: 全宽通栏自适应进度条
                    ExpandingScrubBar(
                        timePosMs = timePosMs,
                        durationMs = durationMs,
                        bufferedPosMs = bufferedPosMs,
                        scrubState = scrubState,
                        onSeekToFraction = onSeekToFraction
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Row 2: 控制行 (40dp 高度)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 播放/暂停
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

                        Spacer(modifier = Modifier.width(12.dp))

                        // 时间戳 (05:32 / 24:10)
                        val displayPos = if (scrubState.isActive) {
                            (scrubState.fraction * durationMs.coerceAtLeast(1L)).toLong()
                        } else {
                            timePosMs
                        }
                        Text(
                            text = "${formatTime(displayPos)} / ${formatTime(durationMs)}",
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.95f),
                                fontFeatureSettings = "tnum"
                            )
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        // 右侧动作区：倍速 + 画幅 + 轨道
                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenTracksSheet() }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${speed}x",
                                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenTracksSheet() }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = resizeMode.label,
                                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(onClick = onOpenTracksSheet, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.Subtitles, contentDescription = "Tracks", tint = Color.White, modifier = Modifier.size(20.dp))
                        }

                        IconButton(onClick = onToggleOrientation, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            } else {
                // 竖屏经典单行内嵌布局 (Single-Row Inline Layout)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.White.copy(alpha = 0.18f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    val displayPos = if (scrubState.isActive) {
                        (scrubState.fraction * durationMs.coerceAtLeast(1L)).toLong()
                    } else {
                        timePosMs
                    }
                    Text(
                        text = formatTime(displayPos),
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White, fontFeatureSettings = "tnum")
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // 居中自适应进度条
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        ExpandingScrubBar(
                            timePosMs = timePosMs,
                            durationMs = durationMs,
                            bufferedPosMs = bufferedPosMs,
                            scrubState = scrubState,
                            onSeekToFraction = onSeekToFraction
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = formatTime(durationMs),
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.7f), fontFeatureSettings = "tnum")
                    )

                    IconButton(onClick = onToggleOrientation, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * Expanding streamline scrub bar:
 * - Idle state: 3dp whisper line
 * - Active / Dragging state: Smoothly expands to 6dp with glowing thumb circle
 * - Multi-layer: Base dark track -> Buffered position (30% white) -> Played progress (Cyan)
 */
@Composable
private fun ExpandingScrubBar(
    timePosMs: Long,
    durationMs: Long,
    bufferedPosMs: Long,
    scrubState: PlayerScrubState,
    onSeekToFraction: (Float) -> Unit
) {
    val total = durationMs.coerceAtLeast(1L)
    val playedFraction = if (scrubState.isActive) {
        scrubState.fraction
    } else {
        (timePosMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
    val bufferedFraction = (bufferedPosMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    val trackHeight by animateDpAsState(
        targetValue = if (scrubState.isActive) 6.dp else 3.dp,
        animationSpec = tween(durationMillis = 180),
        label = "trackHeight"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(total) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val initialFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                    scrubState.begin(initialFraction)

                    var currentFraction = initialFraction
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) {
                            pointer.consume()
                            break
                        }
                        pointer.consume()
                        currentFraction = (pointer.position.x / size.width).coerceIn(0f, 1f)
                        scrubState.update(currentFraction)
                    }

                    scrubState.end(currentFraction)
                    onSeekToFraction(currentFraction)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // 1. Background Inactive Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.22f))
        )

        // 2. Buffered Progress Track
        Box(
            modifier = Modifier
                .fillMaxWidth(bufferedFraction)
                .height(trackHeight)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.35f))
        )

        // 3. Played Progress Track with Glowing Thumb
        Box(
            modifier = Modifier
                .fillMaxWidth(playedFraction)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF64B5F6))
            )

            // Expanding thumb dot when active or dragging
            if (scrubState.isActive) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(Color.White, CircleShape)
                )
            }
        }
    }
}
