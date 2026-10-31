package dev.gyro.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
    onToggleOrientation: () -> Unit,
    onBack: () -> Unit
) {
    // 1. Lock Screen Floating Button (Accessible when controls are shown or screen is locked)
    AnimatedVisibility(
        visible = isVisible || isLocked,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = Modifier
            .align(Alignment.CenterStart)
            .displayCutoutPadding()
            .padding(start = 24.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.7f),
            contentColor = if (isLocked) Color(0xFFFFB74D) else Color.White,
            shadowElevation = 6.dp,
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
            // Top Bar with Cutout Safe Padding & Gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.88f), Color.Transparent)
                        )
                    )
                    .displayCutoutPadding()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            text = title.ifBlank { "Gyro Player" },
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White),
                            maxLines = 1
                        )
                        // Real-time Power, Thermal & Performance Badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            val powerStr = if (powerMetrics.isEstimated) {
                                "~${String.format(Locale.US, "%.1f", powerMetrics.powerWatts)}W (est.)"
                            } else {
                                "${String.format(Locale.US, "%.2f", powerMetrics.powerWatts)}W (${powerMetrics.currentMa}mA)"
                            }
                            val tempStr = "${String.format(Locale.US, "%.1f", powerMetrics.temperatureC)}°C"
                            val fpsStr = if (fps > 0.0) String.format(Locale.US, "%.1ffps", fps) else "hw"
                            val droppedStr = if (droppedFrames > 0L) " ($droppedFrames drop)" else ""

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color.White.copy(alpha = 0.15f),
                                contentColor = Color(0xFF81C784)
                            ) {
                                Text(
                                    text = "$hwdec · $fpsStr$droppedStr · $powerStr · $tempStr",
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

                    IconButton(onClick = onToggleOrientation) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate", tint = Color.White)
                    }

                    IconButton(onClick = onEnterPiP) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                    }

                    IconButton(onClick = onOpenTracksSheet) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Settings", tint = Color.White)
                    }
                }
            }

            // Bottom Bar with Expanding Scrub Bar & Actions
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))
                        )
                    )
                    .displayCutoutPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 18.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Expanding Streamline Scrub Bar
                    ExpandingScrubBar(
                        timePosMs = timePosMs,
                        durationMs = durationMs,
                        bufferedPosMs = bufferedPosMs,
                        scrubState = scrubState,
                        onSeekToFraction = onSeekToFraction
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Player Action Buttons
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
 * Expanding professional streamline scrub bar:
 * - Idle state: 3dp whisper line
 * - Active / Dragging state: Smoothly expands to 7dp with glowing thumb circle
 * - Multi-layer: Base dark track -> Buffered position (30% white) -> Played progress (Cyan)
 */
@Composable
private fun ExpandingScrubBar(
    timePosMs: Long,
    durationMs: Long,
    bufferedPosMs: Long,
    scrubState: PlayerScrubState,
    onSeekToFraction: (Float) -> Unit
) {
    val total = durationMs.coerceAtLeast(1L)
    val playedFraction = if (scrubState.isActive) {
        scrubState.fraction
    } else {
        (timePosMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
    val bufferedFraction = (bufferedPosMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    val trackHeight by animateDpAsState(
        targetValue = if (scrubState.isActive) 7.dp else 4.dp,
        label = "trackHeight"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(total) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val initialFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                    scrubState.begin(initialFraction)

                    var currentFraction = initialFraction
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) {
                            pointer.consume()
                            break
                        }
                        pointer.consume()
                        currentFraction = (pointer.position.x / size.width).coerceIn(0f, 1f)
                        scrubState.update(currentFraction)
                    }

                    scrubState.end(currentFraction)
                    onSeekToFraction(currentFraction)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // 1. Background Inactive Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.22f))
        )

        // 2. Buffered Progress Track
        Box(
            modifier = Modifier
                .fillMaxWidth(bufferedFraction)
                .height(trackHeight)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.45f))
        )

        // 3. Played Active Track
        Box(
            modifier = Modifier
                .fillMaxWidth(playedFraction)
                .height(trackHeight)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF64B5F6))
        )

        // 4. Glowing Thumb Dot (Reveals on hover/drag)
        if (scrubState.isActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(playedFraction),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}
