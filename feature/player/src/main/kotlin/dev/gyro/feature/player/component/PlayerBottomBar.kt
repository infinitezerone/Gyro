package dev.gyro.feature.player.component

import dev.gyro.feature.player.PlayerScrubState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroCapsuleContainer
import dev.gyro.core.designsystem.component.GyroCircleActionButton
import dev.gyro.core.designsystem.component.GyroGhostIconButton
import dev.gyro.core.designsystem.component.GyroPillButton
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.model.ChapterInfo
import dev.gyro.engine.model.ResizeMode
import dev.gyro.feature.player.component.sheet.PlayerSheetTab

/**
 * 底部控制栏统一组件 (PlayerBottomBar)
 *
 * 极简两行架构（方案一规范）：
 * 1. 行 1：通栏自适应弹性进度条（带缓冲状态与安全间距）；
 * 2. 行 2：极简核心控制行：
 *    - 左侧仅保留 [播放/暂停 38dp圆钮] 与清晰时间 [05:20 / 45:00]；
 *    - 右侧保留 [倍速 1.0x 胶囊]（多集连播时保留 [选集 胶囊]）；
 *    - 竖屏全屏按键彻底移出底栏（放置于视频画面正下方），横屏模式下在底栏右侧呈现退出全屏按键；
 * 3. 采用 4 段非线性平滑暗角衰减，消除生硬黑阶。
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
val bottomElevate = if (isLandscape) 14.dp else 20.dp
        Box(
            modifier = modifier
                .fillMaxWidth()
                .displayCutoutPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = bottomElevate)
        ) {
        // 悬浮胶囊底衬（伪磨砂）：替代原通栏四段渐变 scrim
        GyroCapsuleContainer(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            elevation = 8.dp
        ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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
            // A. 拖拽屏幕 Seek 时的轻量反馈条（复用控制栏同款 ScrubRow，样式完全一致）
            ScrubRow(
                displayPos = displayPos,
                totalMs = totalMs,
                bufferedPosMs = bufferedPosMs(),
                scrubState = scrubState,
                onSeekToFraction = onSeekToFraction,
                chapters = chapters,
                abLoopASeconds = abLoopASeconds,
                abLoopBSeconds = abLoopBSeconds,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            // B. 现代双行控制栏：时间环绕进度条两翼 + 纯净操作行
            Column(modifier = Modifier.fillMaxWidth()) {
                // Row 1: 时间环绕自适应进度条 [05:25] ----------●---------- [45:00]
                ScrubRow(
                    displayPos = displayPos,
                    totalMs = totalMs,
                    bufferedPosMs = bufferedPosMs(),
                    scrubState = scrubState,
                    onSeekToFraction = onSeekToFraction,
                    chapters = chapters,
                    abLoopASeconds = abLoopASeconds,
                    abLoopBSeconds = abLoopBSeconds
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Row 2: 黄金触达极简操作行（左侧播放圆钮，右侧选集/倍速功能组，左右对齐呼应）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 播放 / 暂停 / 重播（采用 38dp GyroCircleActionButton 微磨砂圆钮）
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

                    Spacer(modifier = Modifier.weight(1f))

                    // AB 循环胶囊（三态：设A → 设B → 循环中/清除）
                    val abStateLabel = when {
                        abLoopASeconds >= 0 && abLoopBSeconds >= 0 -> "循环中"
                        abLoopASeconds >= 0 -> "A✓ 设B"
                        else -> "AB循环"
                    }
                    GyroPillButton(
                        text = abStateLabel,
                        onClick = onCycleAbLoop,
                        containerColor = if (abLoopASeconds >= 0 && abLoopBSeconds >= 0) {
                            GyroColors.AccentOrange.copy(alpha = 0.30f)
                        } else {
                            Color.Black.copy(alpha = 0.45f)
                        },
                        contentColor = if (abLoopASeconds >= 0) {
                            GyroColors.AccentOrange
                        } else {
                            Color.White
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    // 字幕快开钮（单击开关字幕轨，长按进入字幕设置面板）
                    GyroCircleActionButton(
                        onClick = onToggleSubtitle,
                        icon = Icons.Default.ClosedCaption,
                        contentDescription = stringResource(R.string.sheet_tab_subtitles),
                        size = 38.dp,
                        iconSize = 22.dp,
                        containerColor = Color.Transparent,
                        contentColor = if (subtitleEnabled) {
                            GyroColors.PrimaryLight
                        } else {
                            Color.White.copy(alpha = 0.55f)
                        },
                        border = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    // 选集胶囊 (有连播列表时呈现)
                    if (hasPrevious || hasNext) {
                        GyroPillButton(
                            text = stringResource(R.string.cd_playlist),
                            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            onClick = { onOpenSheetTab(PlayerSheetTab.PLAYLIST) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // 倍速胶囊 (高反差圆角药丸键)
                    GyroPillButton(
                        text = "${speed}x",
                        onClick = { onOpenSheetTab(PlayerSheetTab.SPEED) }
                    )

                    // 横屏模式下，底栏最右侧提供退出全屏按键；竖屏模式下已解耦彻底移出底栏
                    if (isLandscape) {
                        Spacer(modifier = Modifier.width(8.dp))
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
    }
}

/**
 * 共享进度行 (ScrubRow)：时间左翼 + 弹性进度条 + 时长右翼。
 * 控制栏完整模式与滑屏 Seek 轻量模式共用同一布局壳，保证两种场景进度条样式完全一致。
 */
@Composable
private fun ScrubRow(
    displayPos: Long,
    totalMs: Long,
    bufferedPosMs: Long,
    scrubState: PlayerScrubState,
    onSeekToFraction: (Float) -> Unit,
    chapters: List<ChapterInfo>,
    abLoopASeconds: Double = -1.0,
    abLoopBSeconds: Double = -1.0,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 当前播放时间 (左翼)
        Text(
            text = formatTime(displayPos),
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.90f),
                fontFeatureSettings = "tnum"
            )
        )

        Spacer(modifier = Modifier.width(10.dp))

        // 核心进度条 (自适应拉伸铺满中间)
        PlayerScrubBar(
            timePosMs = displayPos,
            durationMs = totalMs,
            bufferedPosMs = bufferedPosMs,
            scrubState = scrubState,
            onSeekToFraction = onSeekToFraction,
            chapters = chapters,
            abLoopASeconds = abLoopASeconds,
            abLoopBSeconds = abLoopBSeconds,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(10.dp))

        // 总时长 (右翼)
        Text(
            text = formatTime(totalMs),
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.70f),
                fontFeatureSettings = "tnum"
            )
        )
    }
}
