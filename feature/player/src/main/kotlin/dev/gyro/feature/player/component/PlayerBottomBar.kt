package dev.gyro.feature.player.component

import dev.gyro.feature.player.PlayerScrubState
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroCircleActionButton
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.model.ChapterInfo
import dev.gyro.engine.model.ResizeMode
import dev.gyro.feature.player.component.sheet.PlayerSheetTab

/**
 * 主流沉浸式底部控制栏 (PlayerBottomBar)
 *
 * 经典两行平铺架构（无生硬外层胶囊，通透沉浸）：
 * 1. 行 1：通栏自适应长进度条（大触控热区、浅白网络缓冲与微光章节刻度）；
 * 2. 行 2：核心操作行（高度 44dp）：
 *    - 左侧：[播放/暂停/重播] + [上一集/下一集] + [当前时间 / 总时长]；
 *    - 右侧：[AB循环] + [字幕切换] + [选集] + [倍速] + [画面比例] + 【全屏/退出全屏】。
 */
@Composable
fun PlayerBottomBar(
    isVisible: Boolean,
    isLandscape: Boolean,
    isPlaying: Boolean,
    timePosMs: () -> Long,
    durationMs: () -> Long,
    bufferedPosMs: () -> Long,
    subtitleEnabled: Boolean = true,
    onToggleSubtitle: () -> Unit = {},
    abLoopASeconds: Double = -1.0,
    abLoopBSeconds: Double = -1.0,
    onCycleAbLoop: () -> Unit = {},
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .displayCutoutPadding()
            .navigationBarsPadding()
            .padding(bottom = 6.dp)
    ) {
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

        // 行 1：通栏自适应长进度条 (大触控热区，左右边距 14dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        ) {
            PlayerScrubBar(
                timePosMs = displayPos,
                durationMs = totalMs,
                bufferedPosMs = bufferedPosMs(),
                scrubState = scrubState,
                onSeekToFraction = onSeekToFraction,
                chapters = chapters,
                abLoopASeconds = abLoopASeconds,
                abLoopBSeconds = abLoopBSeconds,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 时间显示 [05:20 / 45:30] 或 [05:20 / -40:10]（点击切换总时长/剩余时间）
        var showRemainingTime by remember { mutableStateOf(false) }
        val secondaryTimeText = if (showRemainingTime && totalMs > 0L) {
            val remainingMs = (totalMs - displayPos).coerceAtLeast(0L)
            " / -${formatTime(remainingMs)}"
        } else {
            " / ${formatTime(totalMs)}"
        }

        val timeHubContent: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { showRemainingTime = !showRemainingTime }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formatTime(displayPos),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFeatureSettings = "tnum",
                        shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), blurRadius = 6f)
                    )
                )
                Text(
                    text = secondaryTimeText,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.65f),
                        fontFeatureSettings = "tnum",
                        shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), blurRadius = 6f)
                    )
                )
            }
        }

        // 行 2：操作控制行
        // 拖动屏幕改变进度时：除了底部的时间hub，只显示进度条（彻底隐藏播放/暂停、倍速、选集、全屏等按钮）；
        // 平时控制层可见时：展示完整的核心操作行；
        // 其余情况（不可见且非拖动）：44dp 占位保证尺寸稳定不发生下坠位移。
        if (scrubState.isActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                timeHubContent()
            }
        } else if (isVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. 播放 / 暂停 / 重播
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
                    iconSize = 24.dp,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    border = null
                )

                Spacer(modifier = Modifier.width(6.dp))

                // 2. 时间显示（时间hub）
                timeHubContent()

                Spacer(modifier = Modifier.weight(1f))

                // 3. AB 循环按钮（仅在激活状态或横屏时展示）
                if (isLandscape && abLoopASeconds >= 0) {
                    val abStateLabel = when {
                        abLoopASeconds >= 0 && abLoopBSeconds >= 0 -> "循环中"
                        abLoopASeconds >= 0 -> "A✓ 设B"
                        else -> "AB"
                    }
                    val abColor = if (abLoopASeconds >= 0) GyroColors.AccentOrange else Color.White.copy(alpha = 0.85f)
                    PlayerTextActionButton(
                        text = abStateLabel,
                        onClick = onCycleAbLoop,
                        color = abColor
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // 4. 字幕快开钮（横屏时展示；竖屏收纳于顶部菜单或面板）
                if (isLandscape) {
                    GyroCircleActionButton(
                        onClick = onToggleSubtitle,
                        onLongClick = { onOpenSheetTab(PlayerSheetTab.SUBTITLES) },
                        icon = Icons.Default.ClosedCaption,
                        contentDescription = stringResource(R.string.sheet_tab_subtitles),
                        size = 38.dp,
                        iconSize = 22.dp,
                        containerColor = Color.Transparent,
                        contentColor = if (subtitleEnabled) GyroColors.PrimaryLight else Color.White.copy(alpha = 0.50f),
                        border = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // 5. 画面比例（横屏展示；竖屏收纳于设置面板，避免挤占）
                if (isLandscape) {
                    val aspectLabel = when (resizeMode) {
                        ResizeMode.FIT -> "适屏"
                        ResizeMode.CROP -> "填充"
                        ResizeMode.STRETCH -> "拉伸"
                        ResizeMode.ASPECT_16_9 -> "16:9"
                        ResizeMode.ASPECT_21_9 -> "21:9"
                    }
                    PlayerTextActionButton(
                        text = aspectLabel,
                        onClick = { onOpenSheetTab(PlayerSheetTab.ASPECT) }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // 6. 播放列表 / 选集 (始终常驻，支持查看分集与章节)
                PlayerTextActionButton(
                    text = stringResource(R.string.cd_playlist),
                    onClick = { onOpenSheetTab(PlayerSheetTab.PLAYLIST) }
                )
                Spacer(modifier = Modifier.width(6.dp))

                // 7. 倍速（竖屏横屏均保留）
                PlayerTextActionButton(
                    text = "${speed}x",
                    onClick = { onOpenSheetTab(PlayerSheetTab.SPEED) }
                )

                Spacer(modifier = Modifier.width(4.dp))

                // 8. 【全屏 / 退出全屏】（主流播放器标准右下角入口）
                GyroCircleActionButton(
                    onClick = onToggleOrientation,
                    icon = if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = stringResource(if (isLandscape) R.string.cd_exit_fullscreen else R.string.cd_enter_fullscreen),
                    size = 38.dp,
                    iconSize = 24.dp,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    border = null
                )
            }
        } else {
            // 拖动屏幕 Seek 时仅显示进度条，不显示任何其他底部控件；
            // 44dp 占位保持底部栏整体高度不变，使进度条垂直位置与控制层进度条 100% 严丝合缝一致
            Spacer(modifier = Modifier.height(44.dp))
        }
    }
}

/**
 * 纯平通透文字功能按键 (PlayerTextActionButton)
 * - 纯文字呈现，杜绝外嵌厚重胶囊和实线描边；
 * - 携带底层文字阴影保证高光背景下依然清晰；
 * - 支持平滑水波触控反馈。
 */
@Composable
private fun PlayerTextActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.95f),
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.SemiBold
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = color,
                fontFeatureSettings = "tnum",
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.85f),
                    blurRadius = 6f
                )
            )
        )
    }
}
