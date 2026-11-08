package dev.gyro.feature.player.component

import dev.gyro.feature.player.PlayerScrubState

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.gyro.core.designsystem.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.gyro.core.designsystem.component.GyroCircleActionButton
import dev.gyro.core.designsystem.component.GyroPillButton
import dev.gyro.core.model.ChapterInfo
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.model.ResizeMode
import java.util.Locale

/**
 * 现代轻量级播放器控制遮罩骨架 (PlayerControlsOverlay)
 * - 纯悬浮设计，零遮挡色块；
 * - 锁屏按键悬浮于左侧边缘 (Alignment.CenterStart)；
 * - 竖屏模式下，全屏胶囊按键置于【视频画面正下方居中】（下移 32dp 舒展呈现）；
 * - 竖屏多集模式下，在全屏胶囊下方自然展开一行轻量选集横滑栏 [01] [02]...；
 * - 顶部栏承载极简返回、标题、字幕快捷键与设置入口；
 * - 底部栏采用极简两行架构（进度条 + 播放/时间/倍速）。
 */
@Composable
fun BoxScope.PlayerControlsOverlay(
    isVisible: Boolean,
    isLocked: Boolean,
    title: String,
    mediaSubtitle: String? = null,
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
    onEnterPiP: () -> Unit,
    onOpenSheetTab: (PlayerSheetTab) -> Unit,
    onToggleOrientation: () -> Unit,
    onBack: () -> Unit,
    chapters: List<ChapterInfo> = emptyList(),
    videoBottomDp: Dp? = null
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 锁屏浮动悬浮按键（位于左侧中央边缘 Alignment.CenterStart，采用 GyroCircleActionButton）
        AnimatedVisibility(
            visible = isVisible || isLocked,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .displayCutoutPadding()
                .padding(start = 14.dp)
        ) {
            GyroCircleActionButton(
                onClick = onToggleLock,
                icon = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = if (isLocked) stringResource(R.string.cd_unlock) else stringResource(R.string.cd_lock),
                containerColor = if (isLocked) Color.Black.copy(alpha = 0.60f) else Color.Black.copy(alpha = 0.40f),
                contentColor = if (isLocked) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.85f),
                border = BorderStroke(0.5.dp, if (isLocked) Color(0xFFFFB74D).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.22f)),
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
                enter = EnterTransition.None,
                exit = ExitTransition.None,
                modifier = bottomModifier
            ) {
                // 横竖切换药丸胶囊
                GyroPillButton(
                    text = "横屏",
                    icon = Icons.Default.Fullscreen,
                    onClick = onToggleOrientation,
                    height = 36.dp,
                    containerColor = Color.Black.copy(alpha = 0.50f),
                    contentColor = Color.White,
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.28f))
                )
            }
        }

        // 3. 顶部操作栏（未锁屏且可见时展示）
        AnimatedVisibility(
            visible = isVisible,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            PlayerTopBar(
                title = title,
                mediaSubtitle = mediaSubtitle,
                isLandscape = isLandscape,
                onBack = onBack,
                onEnterPiP = onEnterPiP,
                onOpenSheetTab = onOpenSheetTab
            )
        }

        // 4. 底部栏（极简两行，可见态或滑屏 Seek 态）
        val showBottomBar = isVisible || scrubState.isActive
        AnimatedVisibility(
            visible = showBottomBar,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
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
                onOpenSheetTab = onOpenSheetTab,
                onToggleOrientation = onToggleOrientation,
                chapters = chapters
            )
        }
    }
}
