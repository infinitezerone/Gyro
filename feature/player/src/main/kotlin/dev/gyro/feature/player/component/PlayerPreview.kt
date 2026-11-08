package dev.gyro.feature.player.component

import dev.gyro.feature.player.PlayerScrubState
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

        val sampleChapters = listOf(
            dev.gyro.core.model.ChapterInfo(0, "Introduction", 0L),
            dev.gyro.core.model.ChapterInfo(1, "Part 1 - Awakening", 120_000L),
            dev.gyro.core.model.ChapterInfo(2, "Part 2 - The Journey", 300_000L),
            dev.gyro.core.model.ChapterInfo(3, "Credits", 540_000L)
        )

        PlayerControlsOverlay(
            isVisible = true,
            isLocked = false,
            title = "Big Buck Bunny",
            mediaSubtitle = "1920×1080 · 60fps · HW",
            isPlaying = true,
            timePosMs = { 325_000L },
            durationMs = { 600_000L },
            bufferedPosMs = { 450_000L },
            resizeMode = ResizeMode.FIT,
            speed = 1.0f,
            scrubState = PlayerScrubState(),
            onTogglePlayPause = {},
            onSeekToFraction = {},
            onRewind10 = {},
            onForward10 = {},
            onOpenSheetTab = {},
            onToggleOrientation = {},
            onBack = {},
            onEnterPiP = {},
            onToggleLock = {},
            chapters = sampleChapters
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
            title = "Big Buck Bunny",
            mediaSubtitle = "1920×1080 · 60fps · HW",
            isPlaying = true,
            timePosMs = { 325_000L },
            durationMs = { 600_000L },
            bufferedPosMs = { 450_000L },
            resizeMode = ResizeMode.FIT,
            speed = 1.0f,
            scrubState = PlayerScrubState(),
            onTogglePlayPause = {},
            onSeekToFraction = {},
            onRewind10 = {},
            onForward10 = {},
            onOpenSheetTab = {},
            onToggleOrientation = {},
            onBack = {},
            onEnterPiP = {},
            onToggleLock = {}
        )
    }
}

@Preview(
    name = "Player Media Info Sheet Preview",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF1E1E1E
)
@Composable
fun PlayerMediaInfoSheetPreview() {
    val sampleMediaInfo = dev.gyro.core.model.MediaInfo(
        title = "Suzume.no.Tojimari.2022.1080p.BluRay.x265.mkv",
        pathOrUri = "/storage/emulated/0/Movies/Suzume.mkv",
        containerFormat = "matroska",
        fileSize = 4_831_838_208L,
        durationMs = 7_260_000L,
        videoCodec = "hevc (Main 10)",
        videoResolution = "1920 × 1080",
        videoFps = 23.976,
        videoBitrate = 4_800_000L,
        audioCodec = "flac",
        audioChannels = "5.1 (side)",
        audioSampleRate = 48_000L,
        audioBitrate = 640_000L,
        hwdecCurrent = "mediacodec",
        droppedFrames = 0L
    )

    androidx.compose.material3.Surface(
        color = Color(0xFF1E1E1E),
        modifier = Modifier.fillMaxSize()
    ) {
        PlayerTrackSelectionSheetContent(
            initialTab = PlayerSheetTab.INFO,
            tracks = emptyList(),
            currentResizeMode = ResizeMode.FIT,
            currentSpeed = 1.0f,
            subDelaySec = 0.0,
            audioDelaySec = 0.0,
            hwdec = "mediacodec",
            fps = 23.976,
            videoWidth = 1920,
            videoHeight = 1080,
            mediaInfo = sampleMediaInfo,
            onSelectTrack = { _, _ -> },
            onSelectResizeMode = {},
            onSelectSpeed = {},
            onSetSubDelay = {},
            onSetAudioDelay = {},
            onAddExternalSubtitle = {}
        )
    }
}

@Preview(
    name = "Player Audiovisual Sheet Preview",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF1E1E1E
)
@Composable
fun PlayerAudiovisualSheetPreview() {
    androidx.compose.material3.Surface(
        color = Color(0xFF1E1E1E),
        modifier = Modifier.fillMaxSize()
    ) {
        PlayerTrackSelectionSheetContent(
            initialTab = PlayerSheetTab.AUDIOVISUAL,
            tracks = emptyList(),
            currentResizeMode = ResizeMode.FIT,
            currentSpeed = 1.0f,
            subDelaySec = 0.0,
            audioDelaySec = 0.0,
            deband = true,
            brightness = 15,
            contrast = 5,
            saturation = -10,
            nightMode = true,
            stereoDownmix = false,
            onSelectTrack = { _, _ -> },
            onSelectResizeMode = {},
            onSelectSpeed = {},
            onSetSubDelay = {},
            onSetAudioDelay = {},
            onAddExternalSubtitle = {}
        )
    }
}

@Preview(
    name = "Player Settings Hub Preview",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF1E1E1E
)
@Composable
fun PlayerSettingsHubPreview() {
    androidx.compose.material3.Surface(
        color = Color(0xFF1E1E1E),
        modifier = Modifier.fillMaxSize()
    ) {
        PlayerTrackSelectionSheetContent(
            initialTab = PlayerSheetTab.SETTINGS_HUB,
            tracks = emptyList(),
            currentResizeMode = ResizeMode.FIT,
            currentSpeed = 1.0f,
            subDelaySec = 0.0,
            audioDelaySec = 0.0,
            hwdec = "mediacodec",
            fps = 60.0,
            videoWidth = 1920,
            videoHeight = 1080,
            onSelectTrack = { _, _ -> },
            onSelectResizeMode = {},
            onSelectSpeed = {},
            onSetSubDelay = {},
            onSetAudioDelay = {},
            onAddExternalSubtitle = {}
        )
    }
}

@Preview(
    name = "Player Playlist Dual-Pill Sheet Preview",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF1E1E1E
)
@Composable
fun PlayerPlaylistSheetPreview() {
    androidx.compose.material3.Surface(
        color = Color(0xFF1E1E1E),
        modifier = Modifier.fillMaxSize()
    ) {
        PlayerTrackSelectionSheetContent(
            initialTab = PlayerSheetTab.PLAYLIST,
            playlist = listOf(
                dev.gyro.core.model.VideoMediaItem(1L, "content://1", "Episode 01 - The Beginning", 1440000L, 500000000L, 1920, 1080, 0L, "Season 1"),
                dev.gyro.core.model.VideoMediaItem(2L, "content://2", "Episode 02 - Into The Forest", 1500000L, 500000000L, 1920, 1080, 0L, "Season 1"),
                dev.gyro.core.model.VideoMediaItem(3L, "content://3", "Episode 03 - Discovery", 1480000L, 500000000L, 1920, 1080, 0L, "Season 1")
            ),
            currentPlaylistIndex = 0,
            chapters = listOf(
                dev.gyro.core.model.ChapterInfo(0, "OP", 0L),
                dev.gyro.core.model.ChapterInfo(1, "Main Part", 90000L)
            ),
            tracks = emptyList(),
            currentResizeMode = ResizeMode.FIT,
            currentSpeed = 1.0f,
            subDelaySec = 0.0,
            audioDelaySec = 0.0,
            onSelectTrack = { _, _ -> },
            onSelectResizeMode = {},
            onSelectSpeed = {},
            onSetSubDelay = {},
            onSetAudioDelay = {},
            onAddExternalSubtitle = {}
        )
    }
}

