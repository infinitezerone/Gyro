package dev.gyro.feature.player.component

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroCircleActionButton
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.model.ChapterInfo
import dev.gyro.core.model.time.formatDuration
import dev.gyro.engine.model.ResizeMode
import dev.gyro.feature.player.PlayerScrubState
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import dev.gyro.feature.player.component.sheet.common.displayLabel

/**
 * 主流沉浸式底部控制栏 (PlayerBottomBar)
 *
 * 经典两行平铺架构（无生硬外层胶囊，通透沉浸）：
 * 1. 行 1：通栏自适应长进度条（大触控热区、浅白网络缓冲与微光章节刻度）；
 *    - 支持按住进度条向上滑动进入 YouTube 风格精准选帧胶片微调模式；
 * 2. 行 2：核心操作行（高度 44dp）：
 *    - 左侧：[播放/暂停/重播] + [当前时间 / 总时长]；
 *    - 右侧：[AB循环] + [字幕切换] + [选集] + [倍速] + [画面比例] + 【全屏/退出全屏】。
 */
@Composable
fun PlayerBottomBar(
    isVisible: Boolean,
    isLandscape: Boolean = false,
    isPlaying: Boolean,
    timePosMs: () -> Long,
    durationMs: () -> Long,
    bufferedPosMs: () -> Long,
    subtitleEnabled: Boolean = true,
    onToggleSubtitle: () -> Unit = {},
    abLoopASeconds: Double = -1.0,
    abLoopBSeconds: Double = -1.0,
    onCycleAbLoop: () -> Unit = {},
    onClearAbLoop: () -> Unit = {},
    speed: Float,
    resizeMode: ResizeMode,
    scrubState: PlayerScrubState,
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onSeekToFraction: (Float) -> Unit,
    onOpenSheetTab: (PlayerSheetRoute) -> Unit,
    onToggleOrientation: () -> Unit,
    chapters: List<ChapterInfo> = emptyList(),
    ambientColor: Color = Color.Transparent,
    onEnterPreciseSeeking: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 竖屏手指更好按，整体放大并抬高；横屏本来空间充足，保持紧凑避免遮挡画面。
    val barBottomPadding = if (isLandscape) 6.dp else 20.dp
    val controlRowHeight = if (isLandscape) 44.dp else 48.dp
    val actionSize = if (isLandscape) 38.dp else 42.dp
    val actionIconSize = if (isLandscape) 22.dp else 26.dp
    val timeFontSize = if (isLandscape) 14.sp else 15.sp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .displayCutoutPadding()
            .navigationBarsPadding()
            .padding(bottom = barBottomPadding)
            .then(
                if (ambientColor != Color.Transparent) {
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, ambientColor.copy(alpha = 0.28f))
                        )
                    )
                } else Modifier
            )
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

        // 竖屏：全屏切换悬浮在进度条右上方（类似抖音侧边按钮），不占底部操作行。
        if (!isLandscape && isVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FloatingFullscreenButton(
                    onClick = onToggleOrientation,
                    contentDescription = stringResource(R.string.cd_enter_fullscreen)
                )
            }
        }

        // 时间放在进度条左右两端，两侧等宽，进度条保持视觉居中。
        var showRemainingTime by remember { mutableStateOf(false) }
        val timeLabelWidth = if (totalMs >= 3_600_000L) 64.dp else 46.dp
        val rightTimeText = if (showRemainingTime && totalMs > 0L) {
            "-" + formatDuration((totalMs - displayPos).coerceAtLeast(0L), padHours = true)
        } else {
            formatDuration(totalMs, padHours = true)
        }

        // 行 1：时间 + 通栏进度条 + 时间
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatDuration(displayPos, padHours = true),
                style = TextStyle(
                    fontSize = timeFontSize,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontFeatureSettings = "tnum",
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), blurRadius = 6f)
                ),
                maxLines = 1,
                textAlign = TextAlign.Start,
                modifier = Modifier.width(timeLabelWidth)
            )

            PlayerScrubBar(
                timePosMs = displayPos,
                durationMs = totalMs,
                bufferedPosMs = bufferedPosMs(),
                scrubState = scrubState,
                onSeekToFraction = onSeekToFraction,
                chapters = chapters,
                abLoopASeconds = abLoopASeconds,
                abLoopBSeconds = abLoopBSeconds,
                onEnterPreciseSeeking = onEnterPreciseSeeking,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
            )

            Text(
                text = rightTimeText,
                style = TextStyle(
                    fontSize = timeFontSize,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.65f),
                    fontFeatureSettings = "tnum",
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), blurRadius = 6f)
                ),
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .width(timeLabelWidth)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { showRemainingTime = !showRemainingTime }
                    .padding(vertical = 2.dp)
            )
        }

        // 行 2：操作控制行
        // 拖拽进度条时：保留操作行高度占位，进度条不会上下跳动；
        // 控制层可见时：展示完整的核心操作行；
        // 其余情况（不可见且非拖动）：同样占位保持尺寸稳定。
        if (!isVisible) {
            Spacer(modifier = Modifier.height(controlRowHeight))
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(controlRowHeight)
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
                    size = actionSize,
                    iconSize = actionIconSize,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    border = null
                )

                Spacer(modifier = Modifier.weight(1f))

                // 3. A-B 循环：常驻入口，点击依次设 A → 设 B → 清除，长按直接清除。
                // 抽屉里的 AB 页已移除，精细设点统一走精准选帧面板。
                val abLoopActive = abLoopASeconds >= 0.0
                val abLoopLabel = when {
                    abLoopASeconds >= 0.0 && abLoopBSeconds >= 0.0 -> "A-B"
                    abLoopASeconds >= 0.0 -> "A-"
                    else -> "A-B"
                }
                PlayerTextActionButton(
                    text = abLoopLabel,
                    onClick = onCycleAbLoop,
                    onLongClick = onClearAbLoop,
                    color = if (abLoopActive) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(2.dp))

                // 4. 字幕快开钮（横屏展示；长按打开字幕设置面板）
                if (isLandscape) {
                    GyroCircleActionButton(
                        onClick = onToggleSubtitle,
                        onLongClick = { onOpenSheetTab(PlayerSheetRoute.SUBTITLE_TRACKS) },
                        icon = Icons.Default.ClosedCaption,
                        contentDescription = stringResource(R.string.sheet_tab_subtitles),
                        size = actionSize,
                        iconSize = actionIconSize,
                        containerColor = Color.Transparent,
                        contentColor = if (subtitleEnabled) GyroColors.PrimaryLight else Color.White.copy(alpha = 0.50f),
                        border = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // 5. 画面比例（横屏展示）
                if (isLandscape) {
                    val aspectLabel = resizeMode.displayLabel()
                    PlayerTextActionButton(
                        text = aspectLabel,
                        onClick = { onOpenSheetTab(PlayerSheetRoute.ASPECT) }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // 6. 选集快捷入口 (Playlist) —— 竖屏用图标，避免“播放列表”四个字挤占时间轴空间
                GyroCircleActionButton(
                    onClick = { onOpenSheetTab(PlayerSheetRoute.PLAYLIST) },
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = stringResource(R.string.cd_playlist),
                    size = actionSize,
                    iconSize = actionIconSize,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    border = null
                )
                Spacer(modifier = Modifier.width(2.dp))

                // 7. 倍速（竖屏横屏均保留）
                val speedLabel = if (speed == 1.0f) "1.0x" else "${speed}x"
                PlayerTextActionButton(
                    text = speedLabel,
                    onClick = { onOpenSheetTab(PlayerSheetRoute.SPEED) }
                )

                Spacer(modifier = Modifier.width(2.dp))

                // 8. 全屏 / 退出全屏：竖屏已移到进度条右上方悬浮；横屏保留在操作行
                if (isLandscape) {
                    GyroCircleActionButton(
                        onClick = onToggleOrientation,
                        icon = Icons.Default.FullscreenExit,
                        contentDescription = stringResource(R.string.cd_exit_fullscreen),
                        size = actionSize,
                        iconSize = actionIconSize,
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        border = null
                    )
                }
            }
        }
    }
}

/**
 * 悬浮全屏按钮：只保留图标本身，不画圆形底。
 */
@Composable
private fun FloatingFullscreenButton(
    onClick: () -> Unit,
    contentDescription: String
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Fullscreen,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}

/**
 * 纯平通透文字功能按键 (PlayerTextActionButton)
 * - 纯文字呈现，杜绝外嵌厚重胶囊和实线描边；
 * - 携带底层文字阴影保证高光背景下依然清晰；
 * - 支持平滑水波触控反馈。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerTextActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    color: Color = Color.White.copy(alpha = 0.95f),
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.SemiBold
) {
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .then(clickModifier)
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
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}
