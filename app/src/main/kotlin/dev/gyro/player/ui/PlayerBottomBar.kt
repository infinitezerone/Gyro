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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * 底部控制栏统一组件 (PlayerBottomBar)
 *
 * 所有按键均采用【纯悬浮无背景】设计风格，零任何遮挡色块与药丸底色，
 * 视觉极致清透无干扰，与视频画面浑然一体。
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
    onOpenSheetTab: (PlayerSheetTab) -> Unit,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                )
            )
            .displayCutoutPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        val isScreenScrubOnly = !isVisible && scrubState.isActive
        val displayPos = if (scrubState.isActive) {
            (scrubState.fraction * durationMs.coerceAtLeast(1L)).toLong()
        } else {
            timePosMs
        }

        if (isScreenScrubOnly) {
            // A. 拖拽屏幕 Seek 时：仅展示复用进度条与时间反馈，极简纯粹
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
            // B. 现代竖屏双行布局 (Portrait Dual-Row Layout)
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

                // Row 2: 纯悬浮无背景控制行 (40dp 高度)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧：纯悬浮无背景 播放/暂停按键
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 紧跟播放键的当前时间 / 总时长（等宽字体防抖动）
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

                    // 右侧：纯悬浮无背景 倍速
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenSheetTab(PlayerSheetTab.SPEED) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${speed}x",
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // 纯悬浮无背景 画面比例
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenSheetTab(PlayerSheetTab.ASPECT) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = resizeMode.label,
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // 全屏切换按键
                    IconButton(onClick = onToggleOrientation, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        } else {
            // C. 现代横屏双行布局 (Landscape Dual-Row Layout)
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

                // Row 2: 横屏纯悬浮无背景控制行 (40dp 高度)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧：纯悬浮无背景 播放/暂停
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 紧跟播放键的时间
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

                    // 右侧：纯悬浮无背景 倍速
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenSheetTab(PlayerSheetTab.SPEED) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${speed}x",
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // 纯悬浮无背景 画幅
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenSheetTab(PlayerSheetTab.ASPECT) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = resizeMode.label,
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // 音画校准快捷入口 (Tune)
                    IconButton(onClick = { onOpenSheetTab(PlayerSheetTab.SYNC) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Tune, contentDescription = "Sync", tint = Color.White, modifier = Modifier.size(22.dp))
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    // 轨道与字幕快捷入口
                    IconButton(onClick = { onOpenSheetTab(PlayerSheetTab.SUBTITLES) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Subtitles, contentDescription = "Tracks", tint = Color.White, modifier = Modifier.size(22.dp))
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    // 退出全屏 / 返回竖屏
                    IconButton(onClick = onToggleOrientation, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = "Exit Fullscreen", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}
