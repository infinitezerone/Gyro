package dev.gyro.feature.player.component

import dev.gyro.feature.player.gesture.DoubleTapSide
import dev.gyro.feature.player.gesture.GestureHudState

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * 1. Top-center floating capsule slider for Volume & Brightness.
 * 2. Top-center floating pill for 2.0x playback speed.
 * 3. YouTube-style side jump pills for Double-Tap.
 * Note: Seeking HUD directly reuses the bottom progress bar in PlayerControlsOverlay.
 */
@Composable
fun BoxScope.PlayerHudOverlay(
    hudState: GestureHudState,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // 1. Top-Center Floating Capsule Slider (Volume / Brightness)
        AnimatedVisibility(
            visible = hudState is GestureHudState.Brightness || hudState is GestureHudState.Volume,
            enter = fadeIn(animationSpec = tween(150)) + slideInVertically(animationSpec = tween(150)) { -it / 2 },
            exit = fadeOut(animationSpec = tween(300)) + slideOutVertically(animationSpec = tween(300)) { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .displayCutoutPadding()
                .statusBarsPadding()
                .padding(top = 16.dp)
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
                .padding(top = 16.dp)
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

        // 3. YouTube-Style Double-Tap Jump Pills (Left / Right)
        AnimatedVisibility(
            visible = hudState is GestureHudState.DoubleTap,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            val doubleTapState = hudState as? GestureHudState.DoubleTap
            if (doubleTapState != null) {
                val isLeft = doubleTapState.side == DoubleTapSide.LEFT
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = if (isLeft) Alignment.CenterStart else Alignment.CenterEnd
                ) {
                    Surface(
                        shape = RoundedCornerShape(32.dp),
                        color = Color.Black.copy(alpha = 0.65f),
                        contentColor = Color.White,
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f)),
                        modifier = Modifier.padding(horizontal = 48.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            if (isLeft) {
                                Icon(Icons.Default.FastRewind, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text(
                                    text = stringResource(R.string.hud_rewind_seconds, doubleTapState.seconds),
                                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.hud_forward_seconds, doubleTapState.seconds),
                                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
                                )
                                Icon(Icons.Default.FastForward, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Top-center floating capsule slider for Volume & Brightness.
 */
@Composable
private fun TopCapsuleSlider(
    icon: ImageVector,
    contentDescription: String,
    ratio: Float,
    percentage: Int,
    activeColor: Color,
    isOverdrive: Boolean = false
) {
    val borderColor = if (isOverdrive) {
        Color(0xFFFF9800).copy(alpha = 0.6f)
    } else {
        Color.White.copy(alpha = 0.15f)
    }
    val capsuleWidth = if (isOverdrive) 264.dp else 210.dp

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.75f),
        border = BorderStroke(if (isOverdrive) 1.dp else 0.5.dp, borderColor),
        shadowElevation = 6.dp,
        modifier = Modifier
            .width(capsuleWidth)
            .height(38.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = activeColor,
                modifier = Modifier.size(18.dp)
            )

            // Horizontal Progress Bar Track
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
                        .fillMaxWidth(ratio.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(activeColor)
                )
            }

            // Percentage Text
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

            // Overdrive badge hint text (when > 100%)
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
