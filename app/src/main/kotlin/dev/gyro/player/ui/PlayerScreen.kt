package dev.gyro.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.ui.GyroPlayerSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    engine: GyroPlayerEngine,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val playbackState by engine.playbackState.collectAsState()
    val isPlaying by engine.isPlaying.collectAsState()
    val timePosMs by engine.timePosMs.collectAsState()
    val durationMs by engine.durationMs.collectAsState()
    val hwdecCurrent by engine.hwdecCurrent.collectAsState()
    val speed by engine.speed.collectAsState()

    var showControls by remember { mutableStateOf(true) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderProgressMs by remember { mutableLongStateOf(0L) }

    // Gesture HUD overlay state
    var gestureOverlayText by remember { mutableStateOf<String?>(null) }
    var isLongPressFastForward by remember { mutableStateOf(false) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // SAF File Picker
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            engine.loadUri(it)
        }
    }

    // Auto-hide controls after 4 seconds of inactivity
    LaunchedEffect(showControls, isPlaying, isDraggingSlider) {
        if (showControls && isPlaying && !isDraggingSlider) {
            delay(4000)
            showControls = false
        }
    }

    // Clear gesture HUD after 1.2s
    LaunchedEffect(gestureOverlayText) {
        if (gestureOverlayText != null) {
            delay(1200)
            gestureOverlayText = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // Screen Tap & Long Press Gesture Handling
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        showControls = !showControls
                    },
                    onDoubleTap = { offset ->
                        val screenWidth = size.width
                        if (offset.x < screenWidth * 0.4f) {
                            engine.seekRelative(-10000L)
                            gestureOverlayText = "-10s"
                        } else if (offset.x > screenWidth * 0.6f) {
                            engine.seekRelative(10000L)
                            gestureOverlayText = "+10s"
                        } else {
                            engine.togglePlayPause()
                        }
                    },
                    onPress = {
                        // Long press to trigger 2.0x fast forward
                        val pressStartTime = System.currentTimeMillis()
                        val released = tryAwaitRelease()
                        if (System.currentTimeMillis() - pressStartTime > 500) {
                            // Long press ended
                            if (isLongPressFastForward) {
                                isLongPressFastForward = false
                                engine.setSpeed(1.0f)
                                gestureOverlayText = "速度恢复 1.0x"
                            }
                        }
                    }
                )
            }
            // Vertical Drag for Brightness (Left) and Volume (Right)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    val screenWidth = size.width
                    val isLeftSide = change.position.x < screenWidth / 2f
                    val delta = -dragAmount / 400f

                    if (isLeftSide) {
                        // Brightness control
                        activity?.let { act ->
                            val lp = act.window.attributes
                            var current = lp.screenBrightness
                            if (current < 0) current = 0.5f
                            val newBrightness = (current + delta).coerceIn(0.01f, 1.0f)
                            lp.screenBrightness = newBrightness
                            act.window.attributes = lp
                            gestureOverlayText = "亮度 ${(newBrightness * 100).toInt()}%"
                        }
                    } else {
                        // Volume control
                        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val step = if (delta > 0) 1 else -1
                        val newVol = (curVol + step).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        gestureOverlayText = "音量 ${(newVol * 100 / maxVolume.coerceAtLeast(1))}%"
                    }
                }
            }
    ) {
        // Core Video Surface View (Direct Native EGL zero-copy rendering)
        GyroPlayerSurface(
            modifier = Modifier.fillMaxSize(),
            engine = engine
        )

        // Buffering Indicator
        if (playbackState == PlaybackState.BUFFERING) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.Center)
            )
        }

        // Gesture Overlay HUD (Center Toast)
        gestureOverlayText?.let { text ->
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }

        // Animated Player Controls (Top and Bottom Bars)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Gyro Player",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Decoder Badge
                        Surface(
                            color = if (hwdecCurrent.contains("mediacodec")) Color(0xFF1B5E20) else Color(0xFF37474F),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (hwdecCurrent.isNotBlank()) "HW: $hwdecCurrent" else "SW",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Open File Button
                        IconButton(onClick = { openDocumentLauncher.launch(arrayOf("video/*", "audio/*")) }) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Open File",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Bottom Control Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Progress Slider
                    val effectivePos = if (isDraggingSlider) sliderProgressMs else timePosMs
                    val safeDuration = durationMs.coerceAtLeast(1L)
                    val progressRatio = (effectivePos.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

                    Slider(
                        value = progressRatio,
                        onValueChange = { ratio ->
                            isDraggingSlider = true
                            sliderProgressMs = (ratio * safeDuration).toLong()
                        },
                        onValueChangeFinished = {
                            isDraggingSlider = false
                            engine.seekTo(sliderProgressMs)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Play/Pause & Seek Buttons
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { engine.seekRelative(-10000L) }) {
                                Icon(
                                    imageVector = Icons.Default.FastRewind,
                                    contentDescription = "-10s",
                                    tint = Color.White
                                )
                            }

                            IconButton(onClick = { engine.togglePlayPause() }) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            IconButton(onClick = { engine.seekRelative(10000L) }) {
                                Icon(
                                    imageVector = Icons.Default.FastForward,
                                    contentDescription = "+10s",
                                    tint = Color.White
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Timestamp display
                            Text(
                                text = "${formatDuration(effectivePos)} / ${formatDuration(durationMs)}",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Speed Picker
                        IconButton(onClick = {
                            val nextSpeed = when {
                                speed < 1.0f -> 1.0f
                                speed < 1.25f -> 1.25f
                                speed < 1.5f -> 1.5f
                                speed < 2.0f -> 2.0f
                                else -> 0.75f
                            }
                            engine.setSpeed(nextSpeed)
                            gestureOverlayText = "${nextSpeed}x"
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "Playback Speed",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "${speed}x",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
