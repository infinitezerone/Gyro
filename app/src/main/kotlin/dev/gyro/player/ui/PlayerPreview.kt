package dev.gyro.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import dev.gyro.engine.model.ResizeMode

@Preview(
    name = "Portrait Controls - Full Screen",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF000000
)
@Composable
fun PlayerControlsPortraitPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
    ) {
        // Simulated 16:9 Video centered in portrait screen
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .align(Alignment.Center)
                .background(Color(0xFF222530)),
            contentAlignment = Alignment.Center
        ) {
            Text("Simulated 16:9 Video Frame", color = Color.White.copy(alpha = 0.6f))
        }

        PlayerControlsOverlay(
            isVisible = true,
            isLocked = false,
            title = "Big Buck Bunny (1080p HEVC)",
            isPlaying = true,
            timePosMs = 325_000L,
            durationMs = 600_000L,
            bufferedPosMs = 450_000L,
            hwdec = "mediacodec",
            fps = 60.0,
            droppedFrames = 0L,
            powerMetrics = PowerMetrics(0.0, 32),
            resizeMode = ResizeMode.FIT,
            speed = 1.0f,
            scrubState = PlayerScrubState(),
            onTogglePlayPause = {},
            onSeekToFraction = {},
            onRewind10 = {},
            onForward10 = {},
            onCycleSpeed = {},
            onCycleResizeMode = {},
            onToggleOrientation = {},
            onOpenTracksSheet = {},
            onBack = {},
            onEnterPiP = {},
            onToggleLock = {}
        )
    }
}

@Preview(
    name = "Landscape Controls - Dual Row",
    device = "spec:width=891dp,height=411dp",
    showBackground = true,
    backgroundColor = 0xFF000000
)
@Composable
fun PlayerControlsLandscapePreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF181824))
    ) {
        PlayerControlsOverlay(
            isVisible = true,
            isLocked = false,
            title = "Big Buck Bunny (1080p HEVC)",
            isPlaying = true,
            timePosMs = 325_000L,
            durationMs = 600_000L,
            bufferedPosMs = 450_000L,
            hwdec = "mediacodec",
            fps = 60.0,
            droppedFrames = 0L,
            powerMetrics = PowerMetrics(0.0, 32),
            resizeMode = ResizeMode.FIT,
            speed = 1.0f,
            scrubState = PlayerScrubState(),
            onTogglePlayPause = {},
            onSeekToFraction = {},
            onRewind10 = {},
            onForward10 = {},
            onCycleSpeed = {},
            onCycleResizeMode = {},
            onToggleOrientation = {},
            onOpenTracksSheet = {},
            onBack = {},
            onEnterPiP = {},
            onToggleLock = {}
        )
    }
}
