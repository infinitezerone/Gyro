package dev.gyro.player.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import dev.gyro.engine.model.ResizeMode

/**
 * 现代轻量级播放器控制遮罩骨架 (PlayerControlsOverlay)
 * 已彻底解耦巨型 Composable，将职责下沉至独立叶子节点：
 * - PlayerTopBar: 顶部导航、标题、画中画与右侧功能胶囊
 * - PlayerCenterControls: 画面中央核心播放/快退/快进集群与锁屏解锁键
 * - PlayerBottomBar: 底部进度条与操作（包含滑屏 Seek 极简态、竖屏纯净态与横屏专业态）
 */
@Composable
fun BoxScope.PlayerControlsOverlay(
    isVisible: Boolean,
    isLocked: Boolean,
    title: String,
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
    onCycleSpeed: () -> Unit,
    onCycleResizeMode: () -> Unit,
    onOpenTracksSheet: () -> Unit,
    onEnterPiP: () -> Unit,
    onToggleOrientation: () -> Unit,
    onBack: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 顶部操作栏（未锁屏且可见时展示）
        AnimatedVisibility(
            visible = isVisible && !isLocked,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(280)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            PlayerTopBar(
                title = title,
                speed = speed,
                resizeMode = resizeMode,
                isLandscape = isLandscape,
                onBack = onBack,
                onEnterPiP = onEnterPiP,
                onCycleSpeed = onCycleSpeed,
                onCycleResizeMode = onCycleResizeMode,
                onOpenTracksSheet = onOpenTracksSheet
            )
        }

        // 2. 画面中央核心集群（大播放键/快进退，或锁屏时的解锁胶囊）
        PlayerCenterControls(
            isVisible = isVisible,
            isLocked = isLocked,
            isPlaying = isPlaying,
            onTogglePlayPause = onTogglePlayPause,
            onRewind10 = onRewind10,
            onForward10 = onForward10,
            onToggleLock = onToggleLock,
            modifier = Modifier.align(Alignment.Center)
        )

        // 3. 底部栏（可见态或滑屏 Seek 态）
        val showBottomBar = (isVisible && !isLocked) || scrubState.isActive
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
                onRewind10 = onRewind10,
                onForward10 = onForward10,
                onToggleLock = onToggleLock,
                onCycleSpeed = onCycleSpeed,
                onCycleResizeMode = onCycleResizeMode,
                onOpenTracksSheet = onOpenTracksSheet,
                onToggleOrientation = onToggleOrientation
            )
        }
    }
}
