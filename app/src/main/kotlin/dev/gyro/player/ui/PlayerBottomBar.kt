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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.engine.model.ResizeMode
import dev.gyro.player.R

/**
 * 底部控制栏统一组件 (PlayerBottomBar)
 *
 * 规范：
 * 1. 进度条真正通栏 (Edge-to-Edge)：零左右 padding 限制，全宽铺满屏幕两端；
 * 2. 纯悬浮无背景设计：零遮挡色块，纯白矢量与等宽字体；
 * 3. 去重与极简：横竖屏一致采用 [播放/时间] + [倍速/画幅/全屏]，杜绝与顶部栏重复。
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
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                )
            )
            .displayCutoutPadding()
            .navigationBarsPadding()
            .padding(bottom = 6.dp)
    ) {
        val isScreenScrubOnly = !isVisible && scrubState.isActive
        val displayPos = if (scrubState.isActive) {
            (scrubState.fraction * durationMs.coerceAtLeast(1L)).toLong()
        } else {
            timePosMs
        }

        val aspectLabel = when (resizeMode) {
            ResizeMode.FIT -> stringResource(R.string.resize_fit)
            ResizeMode.CROP -> stringResource(R.string.resize_crop)
            ResizeMode.STRETCH -> stringResource(R.string.resize_stretch)
            ResizeMode.ASPECT_16_9 -> stringResource(R.string.resize_16_9)
            ResizeMode.ASPECT_21_9 -> stringResource(R.string.resize_21_9)
        }

        if (isScreenScrubOnly) {
            // A. 拖拽屏幕 Seek 时：两侧留出安全间距的进度条与时间反馈
            Column(modifier = Modifier.fillMaxWidth()) {
                PlayerScrubBar(
                    timePosMs = timePosMs,
                    durationMs = durationMs,
                    bufferedPosMs = bufferedPosMs,
                    scrubState = scrubState,
                    onSeekToFraction = onSeekToFraction,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
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
            // B. 现代双行布局（竖屏与横屏统一架构，零功能重复）
            Column(modifier = Modifier.fillMaxWidth()) {
                // Row 1: 两侧保留 16dp 舒适呼吸间距与防误触缓冲的进度条
                PlayerScrubBar(
                    timePosMs = timePosMs,
                    durationMs = durationMs,
                    bufferedPosMs = bufferedPosMs,
                    scrubState = scrubState,
                    onSeekToFraction = onSeekToFraction,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Row 2: 黄金触达纯悬浮控制行 (40dp 高度)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧：纯悬浮 播放/暂停
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 紧跟播放键的当前时间 / 总时长
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

                    // 右侧：纯悬浮 倍速
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

                    // 纯悬浮 画面比例 (中文规范)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenSheetTab(PlayerSheetTab.ASPECT) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = aspectLabel,
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // 全屏切换 / 退出全屏按键
                    IconButton(onClick = onToggleOrientation, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (isLandscape) stringResource(R.string.cd_exit_fullscreen) else stringResource(R.string.cd_fullscreen),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
