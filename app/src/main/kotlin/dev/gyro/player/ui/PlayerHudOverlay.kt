package dev.gyro.player.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
 * Visual anti-jitter HUD overlay for gestures, strictly applying tabular numbers (tnum).
 */
@Composable
fun BoxScope.PlayerHudOverlay(
    hudState: GestureHudState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when (hudState) {
            is GestureHudState.Volume -> {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (hudState.percent == 0) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                            contentDescription = "Volume",
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "${hudState.percent}%",
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFeatureSettings = "tnum"
                            ),
                            modifier = Modifier.width(42.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
            is GestureHudState.Brightness -> {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BrightnessMedium,
                            contentDescription = "Brightness",
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "${hudState.percent}%",
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFeatureSettings = "tnum"
                            ),
                            modifier = Modifier.width(42.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
            is GestureHudState.Seek -> {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    contentColor = Color.White,
                    modifier = Modifier.widthIn(min = 180.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val sign = if (hudState.deltaMs >= 0) "+" else "-"
                            val deltaSec = abs(hudState.deltaMs) / 1000L
                            Text(
                                text = "[$sign${deltaSec}s]",
                                style = TextStyle(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hudState.deltaMs >= 0) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                    fontFeatureSettings = "tnum"
                                )
                            )
                        }
                        Text(
                            text = "${formatTime(hudState.targetMs)} / ${formatTime(hudState.totalMs)}",
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                fontFeatureSettings = "tnum"
                            )
                        )
                    }
                }
            }
            is GestureHudState.DoubleTap -> {
                val isLeft = hudState.side == DoubleTapSide.LEFT
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
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isLeft) {
                                Icon(Icons.Default.FastRewind, contentDescription = null, modifier = Modifier.size(24.dp))
                                Text(
                                    text = "-${hudState.seconds}s",
                                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
                                )
                            } else {
                                Text(
                                    text = "+${hudState.seconds}s",
                                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
                                )
                                Icon(Icons.Default.FastForward, contentDescription = null, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
            is GestureHudState.Speed -> {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.8f),
                    contentColor = Color(0xFFFFD54F)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.FastForward, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(
                            text = "${hudState.speed}x 播放中",
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
                        )
                    }
                }
            }
            GestureHudState.Idle -> {}
        }
    }
}
