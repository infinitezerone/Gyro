package dev.gyro.feature.player.component

import dev.gyro.feature.player.PlayerScrubState
import dev.gyro.feature.player.gesture.DoubleTapSide
import dev.gyro.feature.player.gesture.GestureHudState
import dev.gyro.core.designsystem.theme.GyroColors

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import java.util.Locale

/**
 * Format milliseconds into mm:ss or hh:mm:ss.
 */
fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = (ms + 500L) / 1000L
    val s = totalSec % 60
    val m = (totalSec / 60) % 60
    val h = totalSec / 3600
    return if (h > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

/**
 * Clean, lightweight HUD overlay:
 * 1. Top-center scrub time preview while dragging the progress bar（屏幕正上方统一时间预览）.
 * 2. Center floating capsule slider for Volume & Brightness（居中呈现，避开顶/底栏）.
 * 3. Top-center floating pill for 2.0x playback speed.
 * 4. YouTube-style side jump pills for Double-Tap.
 * Note: Seeking HUD directly reuses the bottom progress bar in PlayerControlsOverlay.
 */
@Composable
fun BoxScope.PlayerHudOverlay(
    hudState: GestureHudState,
    scrubState: PlayerScrubState,
    durationMs: () -> Long,
    swapVolumeAndBrightness: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // 0. Top-Center Scrub & Gesture Seek Time Preview（拖拽进度条或屏幕水平滑动快进/快退，屏幕正上方显示目标时间与偏差）
        val isSeeking = scrubState.isActive || hudState is GestureHudState.Seek
        AnimatedVisibility(
            visible = isSeeking && durationMs() > 0L,
            enter = fadeIn(animationSpec = tween(100)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .displayCutoutPadding()
                .statusBarsPadding()
                .padding(top = 64.dp)
        ) {
            val total = durationMs()
            if (total > 0L) {
                val targetMs = when {
                    hudState is GestureHudState.Seek -> hudState.targetMs
                    scrubState.isActive -> (scrubState.fraction * total).toLong()
                    else -> 0L
                }
                ScrubTimePreview(
                    targetMs = targetMs,
                    totalMs = total
                )
            }
        }

        // 1. 屏幕正上方浮动胶囊滑块（音量 / 亮度 HUD，位于顶部舒适可视区，下移至 64dp，避开挖孔与顶栏）
        AnimatedVisibility(
            visible = hudState is GestureHudState.Brightness || hudState is GestureHudState.Volume,
            enter = fadeIn(animationSpec = tween(150)) + slideInVertically(animationSpec = tween(150)) { -it / 2 },
            exit = fadeOut(animationSpec = tween(250)) + slideOutVertically(animationSpec = tween(200)) { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .displayCutoutPadding()
                .statusBarsPadding()
                .padding(top = 64.dp)
        ) {
            when (hudState) {
                is GestureHudState.Brightness -> {
                    TopCapsuleSlider(
                        icon = Icons.Default.BrightnessMedium,
                        contentDescription = stringResource(R.string.cd_brightness),
                        ratio = hudState.brightnessRatio,
                        percentage = hudState.percent,
                        activeColor = Color(0xFFFFB74D), // Warm Amber
                        isOverdrive = false
                    )
                }
                is GestureHudState.Volume -> {
                    val icon = if (hudState.percent == 0) {
                        Icons.AutoMirrored.Filled.VolumeMute
                    } else {
                        Icons.AutoMirrored.Filled.VolumeUp
                    }
                    val activeColor = if (hudState.isOverdrive) {
                        Color(0xFFFF9800) // Warm Amber #FF9800 for overdrive
                    } else {
                        Color(0xFF64B5F6) // Material Blue for normal stream volume
                    }
                    TopCapsuleSlider(
                        icon = icon,
                        contentDescription = stringResource(R.string.cd_volume),
                        ratio = hudState.volumeRatio,
                        percentage = hudState.percent,
                        activeColor = activeColor,
                        isOverdrive = hudState.isOverdrive
                    )
                }
                else -> Unit
            }
        }

        // 2. Top-Center Speed Boost Capsule (Long-press 2.0x)
        AnimatedVisibility(
            visible = hudState is GestureHudState.Speed,
            enter = fadeIn(animationSpec = tween(150)) + slideInVertically(animationSpec = tween(150)) { -it / 2 },
            exit = fadeOut(animationSpec = tween(300)) + slideOutVertically(animationSpec = tween(300)) { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .displayCutoutPadding()
                .statusBarsPadding()
                .padding(top = 64.dp)
        ) {
            val speedState = hudState as? GestureHudState.Speed
            if (speedState != null) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)),
                    shadowElevation = 6.dp,
                    modifier = Modifier.height(38.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = stringResource(R.string.speed_title),
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.hud_speed_indicator, speedState.speed),
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                }
            }
        }

        // 2.5. 屏幕正上方切集指示胶囊（上下滑动直接切视频）
        AnimatedVisibility(
            visible = hudState is GestureHudState.SwitchVideo,
            enter = fadeIn(animationSpec = tween(150)) + slideInVertically(animationSpec = tween(150)) { -it / 2 },
            exit = fadeOut(animationSpec = tween(300)) + slideOutVertically(animationSpec = tween(300)) { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .displayCutoutPadding()
                .statusBarsPadding()
                .padding(top = 64.dp)
        ) {
            val switchState = hudState as? GestureHudState.SwitchVideo
            if (switchState != null) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)),
                    shadowElevation = 6.dp,
                    modifier = Modifier.height(38.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (switchState.isNext) Icons.Default.SkipNext else Icons.Default.SkipPrevious,
                            contentDescription = null,
                            tint = GyroColors.PrimaryLight,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (switchState.isNext) stringResource(R.string.cd_next_video) else stringResource(R.string.cd_prev_video),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 3. 纯透明双击快进/快退指示（无胶囊背景、无圆圈，仅保留纯净文字与箭头图标）
        val doubleTapState = hudState as? GestureHudState.DoubleTap
        val isDoubleTapLeft = doubleTapState?.side == DoubleTapSide.LEFT
        AnimatedVisibility(
            visible = doubleTapState != null,
            enter = fadeIn(animationSpec = tween(100)) + scaleIn(initialScale = 0.85f),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier
                .align(if (isDoubleTapLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .displayCutoutPadding()
                .padding(horizontal = 64.dp)
        ) {
            if (doubleTapState != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isDoubleTapLeft) Icons.Default.FastRewind else Icons.Default.FastForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = stringResource(
                            if (isDoubleTapLeft) R.string.hud_rewind_seconds else R.string.hud_forward_seconds,
                            doubleTapState.seconds
                        ),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFeatureSettings = "tnum",
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.90f),
                                blurRadius = 8f
                            )
                        )
                    )
                }
            }
        }
    }
}

/**
 * 屏幕正上方拖拽进度时间预览（纯净大字无胶囊，一眼看清，视野开阔通透）
 */
@Composable
private fun ScrubTimePreview(
    targetMs: Long,
    totalMs: Long
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = formatTime(targetMs),
            style = TextStyle(
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFeatureSettings = "tnum",
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.90f),
                    blurRadius = 12f
                )
            )
        )
        Text(
            text = " / " + formatTime(totalMs),
            style = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.70f),
                fontFeatureSettings = "tnum",
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.90f),
                    blurRadius = 12f
                )
            )
        )
    }
}

/**
 * 紧凑诊断浮层 (StatsDiagnosticOverlay)：
 * 控制层隐藏时常驻画面左上角，替代 mpv stats 页 1 的核心读数。
 * 需要遮蔽系统状态栏/刘海场景由调用方负责 insets。
 */
@Composable
fun StatsDiagnosticOverlay(
    fps: Double,
    droppedFrames: Long,
    hwdec: String,
    vsyncRatio: Double,
    speed: Float,
    bitrate: Long = 0L,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.60f),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            val bitStr = if (bitrate >= 1_000_000L) {
                String.format(java.util.Locale.US, "%.1fM", bitrate / 1_000_000.0)
            } else if (bitrate >= 1_000L) {
                String.format(java.util.Locale.US, "%.0fK", bitrate / 1_000.0)
            } else {
                "--"
            }
            DiagnosticRow(label = "BITRATE", value = bitStr)
            DiagnosticRow(
                label = "FPS",
                value = if (fps > 0) String.format(java.util.Locale.US, "%.1f", fps) else "--"
            )
            DiagnosticRow(
                label = "DROP",
                value = if (droppedFrames > 0) "$droppedFrames" else "0"
            )
            DiagnosticRow(
                label = "VSYNC",
                value = if (vsyncRatio > 0) String.format(java.util.Locale.US, "%.2f", vsyncRatio) else "--"
            )
            DiagnosticRow(
                label = "DEC",
                value = if (hwdec.contains("mediacodec")) "HW" else "SW"
            )
            DiagnosticRow(label = "SPD", value = String.format(java.util.Locale.US, "%.2fx", speed))
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.width(38.dp)
        )
        Text(
            text = value,
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFeatureSettings = "tnum"
            )
        )
    }
}

/**
 * 屏幕正上方浮动胶囊滑块 (TopCapsuleSlider)：
 * 居于屏幕正上方顶部中央 (Alignment.TopCenter)，彻底避免遮挡正中间画面核心与底部字幕区。
 */
@Composable
private fun TopCapsuleSlider(
    icon: ImageVector,
    contentDescription: String,
    ratio: Float,
    percentage: Int,
    activeColor: Color,
    isOverdrive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isOverdrive) {
        Color(0xFFFF9800).copy(alpha = 0.60f)
    } else {
        Color.White.copy(alpha = 0.15f)
    }
    val capsuleWidth = if (isOverdrive) 240.dp else 200.dp

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.75f),
        border = BorderStroke(if (isOverdrive) 1.dp else 0.5.dp, borderColor),
        shadowElevation = 6.dp,
        modifier = modifier
            .width(capsuleWidth)
            .height(38.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = activeColor,
                modifier = Modifier.size(18.dp)
            )

            // 水平进度条平滑物理插值
            val animatedRatio by animateFloatAsState(
                targetValue = ratio.coerceIn(0f, 1f),
                animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioNoBouncy),
                label = "hudRatio"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedRatio)
                        .clip(RoundedCornerShape(2.dp))
                        .background(activeColor)
                )
            }

            // 百分比数值
            Text(
                text = "$percentage%",
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isOverdrive) activeColor else Color.White,
                    fontFeatureSettings = "tnum",
                    textAlign = TextAlign.End
                ),
                modifier = Modifier.width(36.dp)
            )

            // 超驱动指示
            if (isOverdrive) {
                Text(
                    text = stringResource(R.string.hud_volume_overdrive_hint),
                    style = TextStyle(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF9800)
                    )
                )
            }
        }
    }
}
