package dev.gyro.feature.player.component

import dev.gyro.feature.player.PlayerScrubState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroCircleActionButton
import dev.gyro.core.designsystem.component.GyroGhostIconButton
import dev.gyro.core.designsystem.component.GyroPillButton
import dev.gyro.core.model.ChapterInfo
import dev.gyro.engine.model.ResizeMode

/**
 * 底部控制栏统一组件 (PlayerBottomBar)
 *
 * 减法与人机工学升级：
 * 1. 采用 BgmPlus 微交互动作按键原语（GyroCircleActionButton / GyroPillButton / GyroGhostIconButton），
 *    所有可点元素严格具备 ≥48dp 隐式靶心判定与物理弹簧微按压动效；
 * 2. 彻底移除杂乱低频的画幅比例（Aspect）文本，收归二级设置；
 * 3. 竖屏模式下将全屏切换移至更容易触及的屏幕右侧浮动区，底栏免受系统手势条挤压；
 * 4. 采用 4 段非线性平滑暗角衰减，消除生硬黑阶。
 */
@Composable
fun PlayerBottomBar(
    isVisible: Boolean,
    isLandscape: Boolean,
    isPlaying: Boolean,
    timePosMs: () -> Long,
    durationMs: () -> Long,
    bufferedPosMs: () -> Long,
    speed: Float,
    resizeMode: ResizeMode,
    scrubState: PlayerScrubState,
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onSeekToFraction: (Float) -> Unit,
    onOpenSheetTab: (PlayerSheetTab) -> Unit,
    onToggleOrientation: () -> Unit,
    chapters: List<ChapterInfo> = emptyList(),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.20f),
                        Color.Black.copy(alpha = 0.55f),
                        Color.Black.copy(alpha = 0.85f),
                    )
                )
            )
            .displayCutoutPadding()
            .navigationBarsPadding()
            .padding(bottom = 8.dp)
    ) {
        val isScreenScrubOnly = !isVisible && scrubState.isActive
        val totalMs = durationMs()
        val rawPos = if (scrubState.isActive) {
            if (totalMs > 0L) (scrubState.fraction * totalMs).toLong() else 0L
        } else {
            timePosMs()
        }
        val displayPos = if (totalMs > 0L && (rawPos >= totalMs - 800L || (!isPlaying && rawPos >= totalMs - 1500L))) {
            totalMs
        } else {
            rawPos
        }

        if (isScreenScrubOnly) {
            // A. 拖拽屏幕 Seek 时的轻量反馈条
            Column(modifier = Modifier.fillMaxWidth()) {
                PlayerScrubBar(
                    timePosMs = displayPos,
                    durationMs = totalMs,
                    bufferedPosMs = bufferedPosMs(),
                    scrubState = scrubState,
                    onSeekToFraction = onSeekToFraction,
                    chapters = chapters,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
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
                        text = formatTime(totalMs),
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            fontFeatureSettings = "tnum"
                        )
                    )
                }
            }
        } else {
            // B. 现代通透控制栏
            Column(modifier = Modifier.fillMaxWidth()) {
                // Row 1: 舒适安全间距与防误触缓冲的通栏进度条
                PlayerScrubBar(
                    timePosMs = displayPos,
                    durationMs = totalMs,
                    bufferedPosMs = bufferedPosMs(),
                    scrubState = scrubState,
                    onSeekToFraction = onSeekToFraction,
                    chapters = chapters,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Row 2: 黄金触达控制行 (44dp 高度)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 上一集 (仅有上一集时显示)
                    if (hasPrevious) {
                        GyroGhostIconButton(
                            onClick = onPlayPrevious,
                            icon = Icons.Default.SkipPrevious,
                            contentDescription = stringResource(R.string.cd_prev_video),
                            iconSize = 22.dp
                        )
                    }

                    // 播放 / 暂停 / 重播（采用 GyroCircleActionButton 微磨砂圆钮）
                    val isEnded = !isPlaying && totalMs > 0L && displayPos >= totalMs - 500L
                    GyroCircleActionButton(
                        onClick = onTogglePlayPause,
                        icon = when {
                            isPlaying -> Icons.Default.Pause
                            isEnded -> Icons.Default.Refresh
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = if (isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                        size = 38.dp,
                        iconSize = 22.dp,
                        containerColor = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White,
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.28f))
                    )

                    // 下一集 (仅有下一集时显示)
                    if (hasNext) {
                        GyroGhostIconButton(
                            onClick = onPlayNext,
                            icon = Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.cd_next_video),
                            iconSize = 22.dp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 紧跟播放键的时间显示：当前时间 / 总时长
                    Text(
                        text = "${formatTime(displayPos)} / ${formatTime(totalMs)}",
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.90f),
                            fontFeatureSettings = "tnum"
                        )
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // 播放列表胶囊 (若有连播列表时呈现)
                    if (hasPrevious || hasNext) {
                        GyroPillButton(
                            text = stringResource(R.string.cd_playlist),
                            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            onClick = { onOpenSheetTab(PlayerSheetTab.PLAYLIST) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // 倍速胶囊 (高反差圆角药丸键)
                    GyroPillButton(
                        text = "${speed}x",
                        onClick = { onOpenSheetTab(PlayerSheetTab.SPEED) }
                    )

                    // 横屏模式下，底栏最右侧提供退出全屏按键；竖屏模式下已解耦移出底部，由右侧黄金触达区承载
                    if (isLandscape) {
                        Spacer(modifier = Modifier.width(4.dp))
                        GyroGhostIconButton(
                            onClick = onToggleOrientation,
                            icon = Icons.Default.FullscreenExit,
                            contentDescription = stringResource(R.string.cd_exit_fullscreen),
                            iconSize = 22.dp
                        )
                    }
                }
            }
        }
    }
}
