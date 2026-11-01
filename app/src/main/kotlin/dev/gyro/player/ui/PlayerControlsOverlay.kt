package dev.gyro.player.ui

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
import androidx.compose.ui.unit.dp
import dev.gyro.engine.model.ResizeMode

/**
 * 现代轻量级播放器控制遮罩骨架 (PlayerControlsOverlay)
 * - 画面正中保持绝对纯净，零任何大按键遮挡；
 * - 锁屏按键悬浮于左侧边缘 (Alignment.CenterStart)；
 * - 竖屏底栏采用经典单行内嵌布局，零多余按钮塞入；
 * - 顶部栏承载标题、文件打开、画中画与次级设置。
 */
@Composable
fun BoxScope.PlayerControlsOverlay(
    isVisible: Boolean,
    isLocked: Boolean,
    title: String,
    mediaSubtitle: String? = null,
    isPlaying: Boolean,
    timePosMs: Long,
    durationMs: Long,
    bufferedPosMs: Long,
    hwdec: String,
    fps: Double,
    droppedFrames: Long,
    powerMetrics: PowerMetrics,
    resizeMode: ResizeMode,
    speed: Float,
    scrubState: PlayerScrubState,
    onTogglePlayPause: () -> Unit,
    onToggleLock: () -> Unit,
    onSeekToFraction: (Float) -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    onOpenFile: () -> Unit,
    onEnterPiP: () -> Unit,
    onOpenSheetTab: (PlayerSheetTab) -> Unit,
    onToggleOrientation: () -> Unit,
    onBack: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 锁屏浮动悬浮按键（位于左侧中央边缘 Alignment.CenterStart，纯悬浮无背景）
        AnimatedVisibility(
            visible = isVisible || isLocked,
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.85f),
            exit = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .displayCutoutPadding()
                .padding(start = 16.dp)
        ) {
            IconButton(
                onClick = onToggleLock,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (isLocked) "Unlock Screen" else "Lock Screen",
                    tint = if (isLocked) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(26.dp)
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
                onOpenFile = onOpenFile,
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
                onTogglePlayPause = onTogglePlayPause,
                onSeekToFraction = onSeekToFraction,
                onOpenSheetTab = onOpenSheetTab,
                onToggleOrientation = onToggleOrientation
            )
        }
    }
}
