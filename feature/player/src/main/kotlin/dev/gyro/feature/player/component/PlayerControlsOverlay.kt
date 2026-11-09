package dev.gyro.feature.player.component

import dev.gyro.feature.player.PlayerScrubState

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.gyro.core.designsystem.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.gyro.core.designsystem.component.GyroCircleActionButton
import dev.gyro.core.designsystem.component.GyroPillButton
import dev.gyro.feature.player.component.sheet.PlayerSheetTab
import dev.gyro.core.model.ChapterInfo
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.model.ResizeMode

/**
 * 主流现代化播放器控制遮罩骨架 (PlayerControlsOverlay)
 * - 沉浸式暗角渐变（顶部与底部平滑非线性过渡，取代生硬浮岛，保护白字与图标可读性）；
 * - 锁屏按键悬浮于左侧中央边缘 (Alignment.CenterStart)；
 * - 顶部栏：极简沉浸式返回、标题、杜比/HDR标签、字幕/画中画/设置；
 * - 底部栏：通栏长进度条 + 播放/上一集/下一集/时间/字幕/倍速/【右下角标准全屏切换】。
 */
@Composable
fun BoxScope.PlayerControlsOverlay(
    isVisible: Boolean,
    isLocked: Boolean,
    title: String,
    isPlaying: Boolean,
    timePosMs: () -> Long,
    durationMs: () -> Long,
    bufferedPosMs: () -> Long,
    resizeMode: ResizeMode,
    speed: Float,
    scrubState: PlayerScrubState,
    playlist: List<VideoMediaItem> = emptyList(),
    currentPlaylistIndex: Int = -1,
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onSelectPlaylistItem: (Int) -> Unit = {},
    onToggleLock: () -> Unit,
    onSeekToFraction: (Float) -> Unit,
    onRewind10: () -> Unit = {},
    onForward10: () -> Unit = {},
    subtitleEnabled: Boolean = true,
    onToggleSubtitle: () -> Unit = {},
    abLoopASeconds: Double = -1.0,
    abLoopBSeconds: Double = -1.0,
    onCycleAbLoop: () -> Unit = {},
    onScreenshot: () -> Unit = {},
    onEnterPiP: () -> Unit,
    onOpenSheetTab: (PlayerSheetTab) -> Unit,
    onToggleOrientation: () -> Unit,
    onBack: () -> Unit,
    chapters: List<ChapterInfo> = emptyList(),
    videoBottomDp: Dp? = null,
    hdrFormat: String = ""
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showControls = isVisible || scrubState.isActive

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 顶部与底部沉浸式非线性暗角渐变（为主流播放器设计核心，消弭生硬浮岛感）
        AnimatedVisibility(
            visible = showControls && !isLocked,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(180))
        ) {
            val topScrimHeight = if (isLandscape) 100.dp else 70.dp
            val bottomScrimHeight = if (isLandscape) 120.dp else 80.dp

            Box(modifier = Modifier.fillMaxSize()) {
                // 顶部向下淡出暗角（从状态栏向下自然淡出）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .height(topScrimHeight)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.70f),
                                    Color.Black.copy(alpha = 0.35f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // 底部向上淡出暗角（从控制行向上自然淡出）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .height(bottomScrimHeight)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.40f),
                                    Color.Black.copy(alpha = 0.80f)
                                )
                            )
                        )
                )
            }
        }

        // 2. 锁屏浮动悬浮按键（位于左侧中央边缘 Alignment.CenterStart）
        // 锁屏状态下遵循成熟播放器规范：静止时自动淡出以保护全屏纯净，点击屏幕任意位置呼出解锁按键
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .displayCutoutPadding()
                .padding(start = 14.dp)
        ) {
            GyroCircleActionButton(
                onClick = onToggleLock,
                icon = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = if (isLocked) stringResource(R.string.cd_unlock) else stringResource(R.string.cd_lock),
                containerColor = if (isLocked) Color.Black.copy(alpha = 0.65f) else Color.Black.copy(alpha = 0.45f),
                contentColor = if (isLocked) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.90f),
                border = BorderStroke(0.5.dp, if (isLocked) Color(0xFFFFB74D).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.20f)),
                size = 40.dp,
                iconSize = 20.dp
            )
        }

        // 2.5. 截图浮动悬浮按键（位于右侧中央边缘 Alignment.CenterEnd，右拇指黄金盲操点）
        AnimatedVisibility(
            visible = isVisible && !isLocked && !scrubState.isActive,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .displayCutoutPadding()
                .padding(end = 14.dp)
        ) {
            GyroCircleActionButton(
                onClick = onScreenshot,
                icon = Icons.Default.PhotoCamera,
                contentDescription = stringResource(R.string.cd_screenshot),
                containerColor = Color.Black.copy(alpha = 0.45f),
                contentColor = Color.White.copy(alpha = 0.90f),
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.20f)),
                size = 40.dp,
                iconSize = 20.dp
            )
        }

        // 锁定状态下，直接拦截隐藏所有其它控制元素
        if (isLocked) return@Box

        // 2. 竖屏模式下的全屏胶囊按键（置于【视频画面正下方居中】，下移 60dp）
        if (!isLandscape) {
            val bottomModifier = if (videoBottomDp != null && videoBottomDp > 0.dp) {
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = videoBottomDp + 60.dp)
            } else {
                Modifier
                    .align(Alignment.Center)
                    .offset(y = 180.dp)
            }

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                exit = fadeOut(),
                modifier = bottomModifier
            ) {
                GyroPillButton(
                    text = stringResource(R.string.cd_enter_fullscreen),
                    icon = Icons.Default.Fullscreen,
                    onClick = onToggleOrientation,
                    height = 36.dp,
                    containerColor = Color.Black.copy(alpha = 0.50f),
                    contentColor = Color.White,
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.28f))
                )
            }
        }

        // 3. 顶部操作栏（未锁屏且可见时展示；拖拽进度条期间让位于屏幕正上方的时间预览）
        AnimatedVisibility(
            visible = isVisible && !scrubState.isActive,
            enter = fadeIn() + slideInVertically(animationSpec = tween(220)) { -it / 2 },
            exit = fadeOut() + slideOutVertically(animationSpec = tween(180)) { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            PlayerTopBar(
                title = title,
                isLandscape = isLandscape,
                onBack = onBack,
                onEnterPiP = onEnterPiP,
                onOpenSheetTab = onOpenSheetTab,
                onScreenshot = onScreenshot,
                hdrFormat = hdrFormat
            )
        }

        // 4. 底部控制栏（通栏长进度条 + 主流多功能操作行）
        // 拖动屏幕 Seek 时仅显示进度条（不显示任何其他底部控件），通过 PlayerBottomBar 底部等高占位确保进度条绝对不发生下坠位移。
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically(animationSpec = tween(220)) { it / 2 },
            exit = fadeOut() + slideOutVertically(animationSpec = tween(180)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PlayerBottomBar(
                isVisible = isVisible,
                isLandscape = isLandscape,
                isPlaying = isPlaying,
                timePosMs = timePosMs,
                durationMs = durationMs,
                bufferedPosMs = bufferedPosMs,
                speed = speed,
                resizeMode = resizeMode,
                scrubState = scrubState,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onTogglePlayPause = onTogglePlayPause,
                onPlayPrevious = onPlayPrevious,
                onPlayNext = onPlayNext,
                onSeekToFraction = onSeekToFraction,
                subtitleEnabled = subtitleEnabled,
                onToggleSubtitle = onToggleSubtitle,
                abLoopASeconds = abLoopASeconds,
                abLoopBSeconds = abLoopBSeconds,
                onCycleAbLoop = onCycleAbLoop,
                onOpenSheetTab = onOpenSheetTab,
                onToggleOrientation = onToggleOrientation,
                chapters = chapters
            )
        }
    }
}
