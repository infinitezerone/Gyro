package dev.gyro.feature.player.component

import dev.gyro.feature.player.PlayerScrubState

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import dev.gyro.core.model.ChapterInfo
import dev.gyro.engine.model.ResizeMode
import dev.gyro.core.designsystem.icon.GyroIcons

/**
 * 主流现代化播放器控制遮罩骨架 (PlayerControlsOverlay)
 * - 沉浸式暗角渐变（顶部与底部平滑非线性过渡，保护纯白无底图标可读性）；
 * - 竖屏将锁屏/截图下沉至下半区（仅锁屏态保留左下角琥珀色解锁按键）；横屏维持两侧无底纯白图标；
 * - 顶部栏：极简返回、标题、杜比/HDR标签；竖屏仅留竖三点，横屏保留系统状态 + 画中画 + 横三点；
 * - 底部栏：进度条左右同行显示时间 + 核心操作行。
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
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    actions: PlayerOverlayActions,
    subtitleEnabled: Boolean = true,
    abLoopASeconds: Double = -1.0,
    abLoopBSeconds: Double = -1.0,
    chapters: List<ChapterInfo> = emptyList(),
    ambientColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    videoBottomDp: Dp? = null,
    hdrFormat: String = "",
    activeUri: android.net.Uri? = null,
    posterBitmap: android.graphics.Bitmap? = null,
    loadFilmstripThumbnail: (suspend (Long) -> android.graphics.Bitmap?)? = null,
    videoAspectRatio: Float = 16f / 9f
) {
    // Local aliases keep the forwarding bodies below unchanged while the lambda dump moves
    // into [PlayerOverlayActions].
    val onTogglePlayPause = actions.onTogglePlayPause
    val onPlayPrevious = actions.onPlayPrevious
    val onPlayNext = actions.onPlayNext
    val onToggleLock = actions.onToggleLock
    val onSeekToFraction = actions.onSeekToFraction
    val onScreenshot = actions.onScreenshot
    val onEnterPiP = actions.onEnterPiP
    val onOpenSheetTab = actions.onOpenSheetTab
    val onToggleOrientation = actions.onToggleOrientation
    val onBack = actions.onBack
    val onToggleSubtitle = actions.onToggleSubtitle
    val onSetAbLoopA = actions.onSetAbLoopA
    val onSetAbLoopB = actions.onSetAbLoopB
    val onClearAbLoop = actions.onClearAbLoop
    val onPausePlayback = actions.onPausePlayback
    val onResumePlayback = actions.onResumePlayback

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isScrubbing = scrubState.isActive
    val isPreciseSeeking = scrubState.isPreciseSeeking
    val showOtherControls = isVisible && !isScrubbing && !isPreciseSeeking
    val showBottomBar = isVisible || isScrubbing

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 顶部与底部沉浸式非线性暗角渐变（零动画，随控制层瞬时显隐）
        if (!isLocked) {
            val topScrimHeight = if (isLandscape) 100.dp else 70.dp
            val bottomScrimHeight = if (isLandscape) 120.dp else 260.dp

            if (showOtherControls) {
                // 顶部向下淡出暗角
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
            }

            if (showBottomBar && !isPreciseSeeking) {
                // 底部向上淡出暗角
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

        // 2. 锁屏 / 截图悬浮按键：
        // - 横屏保持画面左右中线（无圆形底框纯白图标）；
        // - 竖屏未锁屏态下沉至下半区（见 PlayerBottomBar），仅锁屏态保留左下角琥珀色解锁入口。
        if (isLandscape) {
            if (showOtherControls) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .displayCutoutPadding()
                        .padding(start = 14.dp)
                ) {
                    GyroCircleActionButton(
                        onClick = onToggleLock,
                        icon = if (isLocked) GyroIcons.Lock else GyroIcons.LockOpen,
                        contentDescription = if (isLocked) stringResource(R.string.cd_unlock) else stringResource(R.string.cd_lock),
                        containerColor = Color.Transparent,
                        contentColor = if (isLocked) Color(0xFFFFB74D) else Color.White,
                        border = null,
                        size = 40.dp,
                        iconSize = 24.dp
                    )
                }
            }

            if (showOtherControls && !isLocked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .displayCutoutPadding()
                        .padding(end = 14.dp)
                ) {
                    GyroCircleActionButton(
                        onClick = onScreenshot,
                        icon = GyroIcons.Screenshot,
                        contentDescription = stringResource(R.string.cd_screenshot),
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        border = null,
                        size = 40.dp,
                        iconSize = 24.dp
                    )
                }
            }
        } else if (isLocked && showOtherControls) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .displayCutoutPadding()
                    .navigationBarsPadding()
                    .padding(
                        start = 10.dp,
                        bottom = 172.dp
                    )
            ) {
                GyroCircleActionButton(
                    onClick = onToggleLock,
                    icon = GyroIcons.Lock,
                    contentDescription = stringResource(R.string.cd_unlock),
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color(0xFFFFB74D),
                    border = BorderStroke(0.5.dp, Color(0xFFFFB74D).copy(alpha = 0.45f)),
                    size = 42.dp,
                    iconSize = 22.dp
                )
            }
        }

        // 锁定状态下，直接拦截隐藏所有其它控制元素
        if (isLocked) return@Box

        // 3. 顶部操作栏（零动画，瞬时响应；拖动进度或精准微调时隐藏）
        if (showOtherControls) {
            Box(modifier = Modifier.align(Alignment.TopCenter)) {
                PlayerTopBar(
                    title = title,
                    isLandscape = isLandscape,
                    onBack = onBack,
                    onEnterPiP = onEnterPiP,
                    onOpenSheetTab = onOpenSheetTab,
                    hdrFormat = hdrFormat
                )
            }
        }

        // 4. 底部控制栏（统一下半区：左锁屏 / 右截图、全宽进度条、底部两端时间、底部核心操作行）
        if (showBottomBar && !scrubState.isPreciseSeeking) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
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
                    onToggleLock = onToggleLock,
                    onScreenshot = onScreenshot,
                    onPlayPrevious = onPlayPrevious,
                    onPlayNext = onPlayNext,
                    onSeekToFraction = onSeekToFraction,
                    subtitleEnabled = subtitleEnabled,
                    onToggleSubtitle = onToggleSubtitle,
                    abLoopASeconds = abLoopASeconds,
                    abLoopBSeconds = abLoopBSeconds,
                    onOpenSheetTab = onOpenSheetTab,
                    onToggleOrientation = onToggleOrientation,
                    chapters = chapters,
                    ambientColor = ambientColor,
                    onEnterPreciseSeeking = { originMs ->
                        scrubState.enterPreciseSeeking(originMs, isPlaying)
                        onPausePlayback()
                    }
                )
            }
        }

        // 5. YouTube 风格精准选帧胶片微调面板（一整排连续可横向滚动的缩略图胶卷，上滑后常驻保持）
        PreciseSeekingPanel(
            scrubState = scrubState,
            currentTimeMs = if (scrubState.isActive && durationMs() > 0L) {
                (scrubState.fraction * durationMs()).toLong()
            } else {
                timePosMs()
            },
            durationMs = durationMs(),
            bufferedPosMs = bufferedPosMs(),
            chapters = chapters,
            fallbackBitmap = posterBitmap,
            onSeekToFraction = actions.onSeekToFraction,
            onPreciseSeek = actions.onPreciseSeek,
            loadThumbnail = loadFilmstripThumbnail,
            onCancelRevert = {
                val originMs = scrubState.originTimeMs
                val originFrac = if (durationMs() > 0L) (originMs.toFloat() / durationMs().toFloat()).coerceIn(0f, 1f) else 0f
                scrubState.update(originFrac)
                actions.onSeekToFraction(originFrac)
                scrubState.exitPreciseSeeking()
                if (scrubState.wasPlayingBeforePreciseSeek) {
                    onResumePlayback()
                }
            },
            onDismiss = {
                scrubState.exitPreciseSeeking()
                if (scrubState.wasPlayingBeforePreciseSeek) {
                    onResumePlayback()
                }
            },
            videoAspectRatio = videoAspectRatio,
            abLoopASeconds = abLoopASeconds,
            abLoopBSeconds = abLoopBSeconds,
            onSetAbLoopA = onSetAbLoopA,
            onSetAbLoopB = onSetAbLoopB,
            onClearAbLoop = onClearAbLoop,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
