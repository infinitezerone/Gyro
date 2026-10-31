package dev.gyro.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.engine.model.ResizeMode

/**
 * 底部控制栏容器组件 (PlayerBottomBar)
 * 1. 拖动屏幕 Seek 时：仅展示进度条与时间，其他不显示；
 * 2. 竖屏模式：经典单行内嵌（播放键 + 开始时间 + 居中自适应进度条 + 总时间 + 全屏切换），画面零遮挡；
 * 3. 横屏模式：成熟双行布局（Row 1 全宽通栏进度条，Row 2 双手功能集群）。
 */
@Composable
fun PlayerBottomBar(
    isVisible: Boolean,
    isLandscape: Boolean,
    isPlaying: Boolean,
    timePosMs: Long,
    durationMs: Long,
    bufferedPosMs: Long,
    speed: Float,
    resizeMode: ResizeMode,
    scrubState: PlayerScrubState,
    onTogglePlayPause: () -> Unit,
    onSeekToFraction: (Float) -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    onToggleLock: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCycleResizeMode: () -> Unit,
    onOpenTracksSheet: () -> Unit,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                )
            )
            .displayCutoutPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        val isScreenScrubOnly = !isVisible && scrubState.isActive

        if (isScreenScrubOnly) {
            // A. 拖拽屏幕 Seek 时：只显示复用进度条与时间，极简纯粹
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PlayerScrubBar(
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
        } else if (!isLandscape) {
            // B. 竖屏经典单行内嵌布局 (Single-Row Inline Layout) - 纯净舒适、零画面遮挡
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 播放/暂停键
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
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        fontFeatureSettings = "tnum"
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 居中自适应撑满进度条
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    PlayerScrubBar(
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
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.7f),
                        fontFeatureSettings = "tnum"
                    )
                )

                Spacer(modifier = Modifier.width(4.dp))

                // 全屏/旋转切换
                IconButton(onClick = onToggleOrientation, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        } else {
            // C. 横屏成熟双行布局 (Dual-Row Layout)
            Column(modifier = Modifier.fillMaxWidth()) {
                // Row 1: 全宽通栏自适应进度条
                PlayerScrubBar(
                    timePosMs = timePosMs,
                    durationMs = durationMs,
                    bufferedPosMs = bufferedPosMs,
                    scrubState = scrubState,
                    onSeekToFraction = onSeekToFraction
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Row 2: 控制行 (40dp 高度)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
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

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(onClick = onRewind10, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
                    }

                    IconButton(onClick = onForward10, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
                    }

                    Spacer(modifier = Modifier.width(6.dp))

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

                    // 倍速快捷胶囊
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier
                            .height(30.dp)
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

                    // 画幅快捷胶囊
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier
                            .height(30.dp)
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

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(onClick = onOpenTracksSheet, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Subtitles, contentDescription = "Tracks", tint = Color.White, modifier = Modifier.size(19.dp))
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    IconButton(onClick = onToggleOrientation, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = "Exit Fullscreen", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}
