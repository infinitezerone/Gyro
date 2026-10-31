package dev.gyro.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.engine.model.ResizeMode
import java.util.Locale

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
    onOpenTracksSheet: () -> Unit,
    onEnterPiP: () -> Unit,
    onBack: () -> Unit
) {
    // 1. Lock Screen Floating Button (always accessible when controls or lock is toggled)
    AnimatedVisibility(
        visible = isVisible || isLocked,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = 24.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.65f),
            contentColor = if (isLocked) Color(0xFFFFB74D) else Color.White,
            modifier = Modifier.size(48.dp)
        ) {
            IconButton(onClick = onToggleLock) {
                Icon(
                    imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (isLocked) "Unlock Screen" else "Lock Screen"
                )
            }
        }
    }

    if (isLocked) return

    // 2. Full Interactive Controls (Top Bar & Bottom Bar)
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Top Bar with Gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            text = title.ifBlank { "Gyro Player" },
                            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White),
                            maxLines = 1
                        )
                        // Real-time Power & Performance Badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            val powerStr = if (powerMetrics.isEstimated) {
                                "~${String.format(Locale.US, "%.1f", powerMetrics.powerWatts)}W"
                            } else {
                                String.format(Locale.US, "%.2fW", powerMetrics.powerWatts)
                            }
                            val currentStr = "${powerMetrics.currentMa}mA"
                            val tempStr = "${String.format(Locale.US, "%.1f", powerMetrics.temperatureC)}°C"
                            val fpsStr = if (fps > 0.0) String.format(Locale.US, "%.1ffps", fps) else "hw"
                            val droppedStr = if (droppedFrames > 0L) " ($droppedFrames drop)" else ""

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color.White.copy(alpha = 0.15f),
                                contentColor = Color(0xFF81C784)
                            ) {
                                Text(
                                    text = "$hwdec · $fpsStr$droppedStr · $powerStr ($currentStr) · $tempStr",
                                    style = TextStyle(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    IconButton(onClick = onEnterPiP) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                    }

                    IconButton(onClick = onOpenTracksSheet) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Settings", tint = Color.White)
                    }
                }
            }

            // Bottom Bar with Scrub Bar & Player Actions
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Scrub Bar
                    PlayerScrubBar(
                        timePosMs = timePosMs,
                        durationMs = durationMs,
                        bufferedPosMs = bufferedPosMs,
                        scrubState = scrubState,
                        onSeekToFraction = onSeekToFraction
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            IconButton(
                                onClick = onTogglePlayPause,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White
                                )
                            }

                            // Tabular Time Display
                            val displayPos = if (scrubState.isActive) {
                                (scrubState.fraction * durationMs.coerceAtLeast(1L)).toLong()
                            } else {
                                timePosMs
                            }
                            Text(
                                text = "${formatTime(displayPos)} / ${formatTime(durationMs)}",
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontWeight = FontWeight.Medium,
                                    fontFeatureSettings = "tnum"
                                )
                            )
                        }

                        // Right-side Pills: Aspect Ratio, Speed, Tracks
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.15f),
                                contentColor = Color.White,
                                modifier = Modifier.clickable { onOpenTracksSheet() }
                            ) {
                                Text(
                                    text = resizeMode.label,
                                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.15f),
                                contentColor = Color.White,
                                modifier = Modifier.clickable { onOpenTracksSheet() }
                            ) {
                                Text(
                                    text = "${speed}x",
                                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }

                            IconButton(onClick = onOpenTracksSheet) {
                                Icon(Icons.Default.Subtitles, contentDescription = "Tracks", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Isolated leaf-level scrub bar to prevent unnecessary recomposition of surrounding panels.
 */
@Composable
private fun PlayerScrubBar(
    timePosMs: Long,
    durationMs: Long,
    bufferedPosMs: Long,
    scrubState: PlayerScrubState,
    onSeekToFraction: (Float) -> Unit
) {
    val total = durationMs.coerceAtLeast(1L)
    val currentFraction = if (scrubState.isActive) {
        scrubState.fraction
    } else {
        (timePosMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    Slider(
        value = currentFraction,
        onValueChange = { frac ->
            scrubState.begin(frac)
        },
        onValueChangeFinished = {
            scrubState.end(scrubState.fraction)
            onSeekToFraction(scrubState.fraction)
        },
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color(0xFF64B5F6),
            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
        ),
        modifier = Modifier.fillMaxWidth().height(24.dp)
    )
}
