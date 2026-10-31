package dev.gyro.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

/**
 * Format milliseconds into mm:ss or hh:mm:ss.
 */
fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000L
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
 * Visual anti-jitter HUD overlay aligning with MX Player (edge vertical sliders) and YouTube (jump pills).
 */
@Composable
fun BoxScope.PlayerHudOverlay(
    hudState: GestureHudState,
    modifier: Modifier = Modifier,
    showBottomBar: Boolean = true
) {
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // 1. Left Edge Vertical Brightness Slider (MX Player Style)
        AnimatedVisibility(
            visible = hudState is GestureHudState.Brightness,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 28.dp)
        ) {
            val brightnessState = hudState as? GestureHudState.Brightness
            if (brightnessState != null) {
                VerticalEdgeSlider(
                    icon = Icons.Default.BrightnessMedium,
                    contentDescription = "Brightness",
                    ratio = brightnessState.brightnessRatio,
                    percentage = brightnessState.percent,
                    activeColor = Color(0xFFFFB74D) // Warm amber
                )
            }
        }

        // 2. Right Edge Vertical Volume Slider (MX Player Style)
        AnimatedVisibility(
            visible = hudState is GestureHudState.Volume,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 28.dp)
        ) {
            val volumeState = hudState as? GestureHudState.Volume
            if (volumeState != null) {
                VerticalEdgeSlider(
                    icon = if (volumeState.percent == 0) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                    contentDescription = "Volume",
                    ratio = volumeState.volumeRatio,
                    percentage = volumeState.percent,
                    activeColor = Color(0xFF64B5F6) // Material blue
                )
            }
        }

        // 3. Center Horizontal Seek Time Delta Card
        AnimatedVisibility(
            visible = hudState is GestureHudState.Seek,
            enter = fadeIn() + scaleIn(initialScale = 0.95f),
            exit = fadeOut() + scaleOut(targetScale = 0.95f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val seekState = hudState as? GestureHudState.Seek
            if (seekState != null) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    contentColor = Color.White,
                    shadowElevation = 8.dp,
                    modifier = Modifier.widthIn(min = 210.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Delta Pill
                        val sign = if (seekState.deltaMs >= 0) "+" else "-"
                        val deltaSec = abs(seekState.deltaMs) / 1000L
                        val deltaMin = deltaSec / 60L
                        val deltaRemSec = deltaSec % 60L
                        val deltaText = if (deltaMin > 0) {
                            String.format(Locale.US, "%s%02d:%02d", sign, deltaMin, deltaRemSec)
                        } else {
                            String.format(Locale.US, "%s%ds", sign, deltaRemSec)
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (seekState.deltaMs >= 0) Color(0xFF1B5E20) else Color(0xFFE65100)
                        ) {
                            Text(
                                text = "[$deltaText]",
                                style = TextStyle(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontFeatureSettings = "tnum"
                                ),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }

                        // Absolute Target / Total
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = formatTime(seekState.targetMs),
                                style = TextStyle(
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontFeatureSettings = "tnum"
                                )
                            )
                            Text(
                                text = " / ${formatTime(seekState.totalMs)}",
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontFeatureSettings = "tnum"
                                )
                            )
                        }
                    }
                }
            }
        }

        // 3b. MiniBgm Style Bottom Seek Progress Bar
        AnimatedVisibility(
            visible = hudState is GestureHudState.Seek && showBottomBar,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(350)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            val seekState = hudState as? GestureHudState.Seek
            if (seekState != null && seekState.totalMs > 0L) {
                val fraction = (seekState.targetMs.toFloat() / seekState.totalMs.toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                            )
                        )
                        .displayCutoutPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Track
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            // Inactive Background Track
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.25f))
                            )

                            // Active Played Track with Glowing Thumb
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color(0xFF64B5F6))
                                )
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(0xFF64B5F6), CircleShape)
                                )
                            }
                        }

                        // Timestamps below track
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatTime(seekState.targetMs),
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF64B5F6),
                                    fontFeatureSettings = "tnum"
                                )
                            )
                            val deltaSign = if (seekState.deltaMs >= 0) "+" else ""
                            val deltaSec = seekState.deltaMs / 1000L
                            Text(
                                text = "${deltaSign}${deltaSec}s",
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (seekState.deltaMs >= 0) Color(0xFF81C784) else Color(0xFFFFB74D),
                                    fontFeatureSettings = "tnum"
                                )
                            )
                            Text(
                                text = formatTime(seekState.totalMs),
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontFeatureSettings = "tnum"
                                )
                            )
                        }
                    }
                }
            }
        }

        // 4. Double-Tap Animated Jump Pills (YouTube Style)
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
                        color = Color.White.copy(alpha = 0.25f),
                        contentColor = Color.White,
                        modifier = Modifier
                            .padding(horizontal = 48.dp)
                            .clip(CircleShape)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isLeft) {
                                Icon(Icons.Default.FastRewind, contentDescription = null, modifier = Modifier.size(24.dp))
                                Text(
                                    text = "-${doubleTapState.seconds}s",
                                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
                                )
                            } else {
                                Text(
                                    text = "+${doubleTapState.seconds}s",
                                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
                                )
                                Icon(Icons.Default.FastForward, contentDescription = null, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }

        // 5. Long-Press 2x Speed Pill
        AnimatedVisibility(
            visible = hudState is GestureHudState.Speed,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 70.dp)
        ) {
            val speedState = hudState as? GestureHudState.Speed
            if (speedState != null) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.8f),
                    contentColor = Color(0xFFFFD54F),
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.FastForward, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(
                            text = "${speedState.speed}x 播放中",
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
                        )
                    }
                }
            }
        }
    }
}

/**
 * MX Player style vertical capsule slider with animated fill bar and tabular numbers.
 */
@Composable
private fun VerticalEdgeSlider(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    ratio: Float,
    percentage: Int,
    activeColor: Color
) {
    val animatedRatio by animateFloatAsState(targetValue = ratio.coerceIn(0f, 1f), label = "edgeRatio")

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.Black.copy(alpha = 0.75f),
        contentColor = Color.White,
        shadowElevation = 8.dp,
        modifier = Modifier
            .width(44.dp)
            .height(180.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )

            // Vertical Track Container
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(95.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .fillMaxHeight(animatedRatio)
                        .clip(RoundedCornerShape(3.dp))
                        .background(activeColor)
                )
            }

            // Tabular Number text
            Text(
                text = "$percentage%",
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum"
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}
