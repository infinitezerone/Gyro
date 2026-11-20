package dev.gyro.feature.player.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroCircleActionButton
import dev.gyro.core.model.ChapterInfo
import dev.gyro.core.model.time.formatDuration
import dev.gyro.engine.model.ResizeMode
import dev.gyro.feature.player.PlayerScrubState
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import dev.gyro.feature.player.component.sheet.common.displayLabel
import dev.gyro.core.designsystem.icon.GyroIcons

internal val PortraitBarBottomPadding = 20.dp
internal val PortraitControlRowHeight = 52.dp
internal val PortraitScrubRowHeight = 28.dp
internal val PortraitSideRowHeight = 48.dp
internal val PortraitSideRowBottomPadding = 84.dp
internal val PortraitSideRowHorizontalPadding = 10.dp
internal val PortraitActionButtonSize = 46.dp

/**
 * 沉浸式底部控制栏 (PlayerBottomBar)
 *
 * - 竖屏专属辅助行（仅竖屏显示）：左 [锁屏]、右 [截图]，无圆形底框纯白图标下沉收拢至下半区；
 * - 进度条行：[当前时间] + [自适应长进度条] + [总时长/剩余时间]（恢复经典左右同行排布）；
 * - 核心操作行：左 [播放/暂停/重播]，右 [字幕(横)] + [比例(横)] + [选集] + [倍速] + [全屏/退出全屏]。
 */
@OptIn(ExperimentalLayoutApi::class)
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
    speed: Float,
    resizeMode: ResizeMode,
    scrubState: PlayerScrubState,
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onToggleLock: () -> Unit = {},
    onScreenshot: () -> Unit = {},
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onSeekToFraction: (Float) -> Unit,
    onOpenSheetTab: (PlayerSheetRoute) -> Unit,
    onToggleOrientation: () -> Unit,
    chapters: List<ChapterInfo> = emptyList(),
    onEnterPreciseSeeking: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 竖屏手指更好按，整体放大并抬高；横屏空间宽阔，保持紧凑并避开圆角与挖孔区域。
    val landscapeHorizontalInset = if (isLandscape) rememberLandscapeHorizontalInset() else 0.dp
    val barBottomPadding = if (isLandscape) LandscapeBottomBarBottomPadding else PortraitBarBottomPadding
    val controlRowHeight = if (isLandscape) 48.dp else PortraitControlRowHeight
    val actionSize = if (isLandscape) LandscapeActionButtonSize else PortraitActionButtonSize
    val actionIconSize = if (isLandscape) 25.dp else 27.dp
    val playIconSize = if (isLandscape) 34.dp else 36.dp
    val pauseIconSize = if (isLandscape) 30.dp else 32.dp
    val replayIconSize = if (isLandscape) 24.dp else 25.dp
    val timeFontSize = if (isLandscape) 14.sp else 15.sp
    val sideRowHeight = PortraitSideRowHeight
    val sideRowBottomPadding = PortraitSideRowBottomPadding

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isLandscape) {
                    Modifier.padding(horizontal = landscapeHorizontalInset)
                } else {
                    Modifier
                        .displayCutoutPadding()
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                }
            )
            .padding(bottom = barBottomPadding)
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

        // 1. 竖屏下半区左右辅助行（无圆底纯白极简 锁屏 / 截图；横屏保留在画面两侧）
        if (!isLandscape) {
            if (!isVisible) {
                Spacer(modifier = Modifier.height(sideRowHeight + sideRowBottomPadding))
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PortraitSideRowHorizontalPadding)
                        .padding(bottom = sideRowBottomPadding)
                        .height(sideRowHeight),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GyroCircleActionButton(
                        onClick = onToggleLock,
                        icon = GyroIcons.LockOpen,
                        contentDescription = stringResource(R.string.cd_lock),
                        size = actionSize,
                        iconSize = 25.dp,
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        border = null
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    GyroCircleActionButton(
                        onClick = onScreenshot,
                        icon = GyroIcons.Screenshot,
                        contentDescription = stringResource(R.string.cd_screenshot),
                        size = actionSize,
                        iconSize = 26.dp,
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        border = null
                    )
                }
            }
        }

        // 2. 时间 + 通栏进度条 + 时间（左右同行，两侧等宽，点击右侧切换总时长/剩余时间）
        var showRemainingTime by remember { mutableStateOf(false) }
        val timeLabelWidth = if (totalMs >= 3_600_000L) 64.dp else 46.dp
        val rightTimeText = if (showRemainingTime && totalMs > 0L) {
            "-" + formatDuration((totalMs - displayPos).coerceAtLeast(0L), padHours = true)
        } else {
            formatDuration(totalMs, padHours = true)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!isLandscape) Modifier.height(PortraitScrubRowHeight) else Modifier)
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

        // 4. 操作控制行
        if (isLandscape) {
            Spacer(modifier = Modifier.height(2.dp))
        }
        if (!isVisible) {
            Spacer(modifier = Modifier.height(controlRowHeight))
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(controlRowHeight)
                    .padding(horizontal = if (isLandscape) LandscapeBarHorizontalExtra else 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 播放 / 暂停 / 重播
                val isEnded = !isPlaying && totalMs > 0L && displayPos >= totalMs - 500L
                GyroCircleActionButton(
                    onClick = onTogglePlayPause,
                    icon = when {
                        isPlaying -> GyroIcons.Pause
                        isEnded -> GyroIcons.Replay
                        else -> GyroIcons.Play
                    },
                    contentDescription = if (isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                    size = actionSize,
                    iconSize = when {
                        isPlaying -> pauseIconSize
                        isEnded -> replayIconSize
                        else -> playIconSize
                    },
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    border = null
                )

                Spacer(modifier = Modifier.weight(1f))

                // 字幕快开钮（横屏展示；长按打开字幕面板）
                if (isLandscape) {
                    GyroCircleActionButton(
                        onClick = onToggleSubtitle,
                        onLongClick = { onOpenSheetTab(PlayerSheetRoute.SUBTITLES) },
                        icon = if (subtitleEnabled) GyroIcons.Subtitles else GyroIcons.SubtitlesOutline,
                        contentDescription = stringResource(R.string.sheet_tab_subtitles),
                        size = actionSize,
                        iconSize = actionIconSize,
                        containerColor = Color.Transparent,
                        contentColor = if (subtitleEnabled) Color.White else Color.White.copy(alpha = 0.45f),
                        border = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // 画面比例（横屏展示）
                if (isLandscape) {
                    val aspectLabel = resizeMode.displayLabel()
                    PlayerTextActionButton(
                        text = aspectLabel,
                        onClick = { onOpenSheetTab(PlayerSheetRoute.VIDEO) }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // 选集入口
                GyroCircleActionButton(
                    onClick = { onOpenSheetTab(PlayerSheetRoute.PLAYLIST) },
                    icon = GyroIcons.PlaylistPlay,
                    contentDescription = stringResource(R.string.cd_playlist),
                    size = actionSize,
                    iconSize = actionIconSize,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    border = null
                )
                Spacer(modifier = Modifier.width(2.dp))

                // 倍速
                val speedLabel = if (speed == 1.0f) "1.0x" else "${speed}x"
                PlayerTextActionButton(
                    text = speedLabel,
                    onClick = { onOpenSheetTab(PlayerSheetRoute.SPEED) }
                )

                Spacer(modifier = Modifier.width(2.dp))

                // 全屏 / 退出全屏（横竖屏统一收在右下角）
                GyroCircleActionButton(
                    onClick = onToggleOrientation,
                    icon = if (isLandscape) GyroIcons.FullscreenExit else GyroIcons.FullscreenEnter,
                    contentDescription = if (isLandscape) {
                        stringResource(R.string.cd_exit_fullscreen)
                    } else {
                        stringResource(R.string.cd_enter_fullscreen)
                    },
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
