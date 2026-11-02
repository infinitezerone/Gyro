package dev.gyro.feature.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.gyro.core.designsystem.R
import androidx.compose.ui.unit.dp
import dev.gyro.core.model.ChapterInfo
import dev.gyro.engine.model.ResizeMode

/**
 * 现代轻量级播放器控制遮罩骨架 (PlayerControlsOverlay)
 * - 纯悬浮无背景设计，零遮挡色块；
 * - 锁屏按键悬浮于左侧边缘 (Alignment.CenterStart)；
 * - 顶部栏承载返回、标题与次级设置；
 * - 底部栏采用现代高精度双行通栏架构。
 */
@Composable
fun BoxScope.PlayerControlsOverlay(
    isVisible: Boolean,
    isLocked: Boolean,
    title: String,
    mediaSubtitle: String? = null,
    isPlaying: Boolean,
    /**
     * Playback clock, passed as a lambda rather than a value.
     *
     * `time-pos` updates on every decoded frame, so receiving it as a value would recompose this
     * whole overlay (and the caller) at video frame rate even while the controls are hidden and
     * nothing is on screen. Deferring the read keeps the invalidation inside the bottom bar, which
     * only composes while it is visible.
     */
    timePosMs: () -> Long,
    durationMs: () -> Long,
    bufferedPosMs: () -> Long,
    resizeMode: ResizeMode,
    speed: Float,
    scrubState: PlayerScrubState,
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onToggleLock: () -> Unit,
    onSeekToFraction: (Float) -> Unit,
    onRewind10: () -> Unit = {},
    onForward10: () -> Unit = {},
    onEnterPiP: () -> Unit,
    onOpenSheetTab: (PlayerSheetTab) -> Unit,
    onToggleOrientation: () -> Unit,
    onBack: () -> Unit,
    chapters: List<ChapterInfo> = emptyList()
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 锁屏浮动悬浮按键（位于左侧中央边缘 Alignment.CenterStart，纯悬浮微透明）
        AnimatedVisibility(
            visible = isVisible || isLocked,
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.85f),
            exit = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .displayCutoutPadding()
                .padding(start = 14.dp)
        ) {
            IconButton(
                onClick = onToggleLock,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (isLocked) stringResource(R.string.cd_unlock) else stringResource(R.string.cd_lock),
                    tint = if (isLocked) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // 锁定状态下，直接拦截隐藏所有其它控制元素
        if (isLocked) return@Box

        // 2. 顶部操作栏（未锁屏且可见时展示）
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(280)),
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

        // 3. 底部栏（可见态或滑屏 Seek 态）
        val showBottomBar = isVisible || scrubState.isActive
        AnimatedVisibility(
            visible = showBottomBar,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(300)),
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
