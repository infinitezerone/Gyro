package dev.gyro.feature.player.component

import dev.gyro.feature.player.gesture.DoubleTapSide
import dev.gyro.feature.player.gesture.GestureHudState
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.GlslShaderState
import dev.gyro.core.model.HwdecMode
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.engine.model.TrackInfo
import dev.gyro.engine.model.TrackType
import dev.gyro.feature.player.PlayerScrubState
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.PlayerSettingsSheetContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.engine.model.ResizeMode
import dev.gyro.core.designsystem.icon.GyroIcons

private val sampleChapters = listOf(
    dev.gyro.core.model.ChapterInfo(0, "Introduction", 0L),
    dev.gyro.core.model.ChapterInfo(1, "Part 1 - Awakening", 120_000L),
    dev.gyro.core.model.ChapterInfo(2, "Part 2 - The Journey", 300_000L),
    dev.gyro.core.model.ChapterInfo(3, "Credits", 540_000L)
)

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
            title = "Big Buck Bunny",
            isPlaying = true,
            timePosMs = { 325_000L },
            durationMs = { 600_000L },
            bufferedPosMs = { 450_000L },
            resizeMode = ResizeMode.FIT,
            speed = 1.0f,
            scrubState = PlayerScrubState(),
            actions = PlayerOverlayActions(
                onTogglePlayPause = {},
                onSeekToFraction = {},
                onRewind10 = {},
                onForward10 = {},
                onOpenSheetTab = {},
                onToggleOrientation = {},
                onBack = {},
                onEnterPiP = {},
                onToggleLock = {}
            ),
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
            isPlaying = true,
            timePosMs = { 325_000L },
            durationMs = { 600_000L },
            bufferedPosMs = { 450_000L },
            resizeMode = ResizeMode.FIT,
            speed = 1.0f,
            scrubState = PlayerScrubState(),
            actions = PlayerOverlayActions(
                onTogglePlayPause = {},
                onSeekToFraction = {},
                onRewind10 = {},
                onForward10 = {},
                onOpenSheetTab = {},
                onToggleOrientation = {},
                onBack = {},
                onEnterPiP = {},
                onToggleLock = {}
            ),
            chapters = sampleChapters
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
        hwdecCurrent = HwdecMode.HARDWARE_DIRECT.persistedKey,
        droppedFrames = 0L
    )

    androidx.compose.material3.Surface(
        color = GyroColors.PanelBackground,
        contentColor = Color.White,
        modifier = Modifier.fillMaxSize()
    ) {
        PlayerSettingsSheetContent(
            initialTab = PlayerSheetRoute.STATS_INFO,
            state = PlayerSheetUiState(
                hwdec = HwdecMode.HARDWARE_DIRECT.persistedKey,
                hwdecMode = HwdecMode.HARDWARE_DIRECT,
                fps = 23.976,
                videoWidth = 1920,
                videoHeight = 1080,
                bitrateHistory = listOf(3_800_000L, 4_500_000L, 4_200_000L, 4_900_000L),
                videoBitrate = 4_800_000L,
                mediaInfo = sampleMediaInfo
            ),
            callbacks = PlayerSheetCallbacks()
        )
    }
}

@Preview(
    name = "Player Video Sheet Preview",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF14161D
)
@Composable
fun PlayerVideoSheetPreview() {
    androidx.compose.material3.Surface(
        color = GyroColors.PanelBackground,
        contentColor = Color.White,
        modifier = Modifier.fillMaxSize()
    ) {
        PlayerSettingsSheetContent(
            initialTab = PlayerSheetRoute.VIDEO,
            state = PlayerSheetUiState(
                currentResizeMode = ResizeMode.CROP,
                shaderState = GlslShaderState(
                    activeShader = GlslShader.ANIME4K,
                    displayName = "Anime4K"
                ),
                deband = true,
                brightness = 15,
                contrast = 5,
                saturation = -10,
                hwdecMode = HwdecMode.HARDWARE_COPY,
            ),
            callbacks = PlayerSheetCallbacks()
        )
    }
}

@Preview(
    name = "Player Settings Hub Preview",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF14161D
)
@Composable
fun PlayerSettingsHubPreview() {
    val sampleTracks = listOf(
        TrackInfo(id = 1, type = TrackType.AUDIO, title = "Japanese FLAC 5.1ch", lang = "jpn", isSelected = true),
        TrackInfo(id = 1, type = TrackType.SUBTITLE, title = "Chinese (Simplified)", lang = "chi", isSelected = true),
    )
    androidx.compose.material3.Surface(
        color = GyroColors.PanelBackground,
        contentColor = Color.White,
        modifier = Modifier.fillMaxSize()
    ) {
        PlayerSettingsSheetContent(
            initialTab = PlayerSheetRoute.SETTINGS_HUB,
            state = PlayerSheetUiState(
                tracks = sampleTracks,
                softwareVolume = 125.0,
                hwdec = HwdecMode.HARDWARE_DIRECT.persistedKey,
                hwdecMode = HwdecMode.HARDWARE_DIRECT,
                fps = 60.0,
                videoWidth = 1920,
                videoHeight = 1080
            ),
            callbacks = PlayerSheetCallbacks()
        )
    }
}

@Preview(
    name = "Player Playlist Dual-Pill Sheet Preview",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF14161D
)
@Composable
fun PlayerPlaylistSheetPreview() {
    androidx.compose.material3.Surface(
        color = GyroColors.PanelBackground,
        contentColor = Color.White,
        modifier = Modifier.fillMaxSize()
    ) {
        PlayerSettingsSheetContent(
            initialTab = PlayerSheetRoute.PLAYLIST,
            state = PlayerSheetUiState(
                playlist = listOf(
                    dev.gyro.core.model.VideoMediaItem(1L, "content://1", "Episode 01 - The Beginning", 1440000L, 500000000L, 1920, 1080, 0L, "Season 1"),
                    dev.gyro.core.model.VideoMediaItem(2L, "content://2", "Episode 02 - Into The Forest", 1500000L, 500000000L, 1920, 1080, 0L, "Season 1"),
                    dev.gyro.core.model.VideoMediaItem(3L, "content://3", "Episode 03 - Discovery", 1480000L, 500000000L, 1920, 1080, 0L, "Season 1")
                ),
                currentPlaylistIndex = 0,
                chapters = listOf(
                    dev.gyro.core.model.ChapterInfo(0, "OP", 0L),
                    dev.gyro.core.model.ChapterInfo(1, "Main Part", 90000L)
                )
            ),
            callbacks = PlayerSheetCallbacks()
        )
    }
}

@Preview(
    name = "Player Subtitles Sheet Preview",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF14161D
)
@Composable
fun PlayerSubtitlesSheetPreview() {
    val sampleTracks = listOf(
        TrackInfo(id = 1, type = TrackType.SUBTITLE, title = "Chinese (Simplified)", lang = "chi", isSelected = true),
        TrackInfo(id = 2, type = TrackType.SUBTITLE, title = "English (Full)", lang = "eng", isSelected = false),
        TrackInfo(id = 3, type = TrackType.SUBTITLE, title = "Japanese (Original)", lang = "jpn", isSelected = false)
    )
    androidx.compose.material3.Surface(
        color = GyroColors.PanelBackground,
        contentColor = Color.White,
        modifier = Modifier.fillMaxSize()
    ) {
        PlayerSettingsSheetContent(
            initialTab = PlayerSheetRoute.SUBTITLES,
            state = PlayerSheetUiState(
                tracks = sampleTracks,
                subDelaySec = 0.5,
                subScale = 1.2,
                subPos = 100L
            ),
            callbacks = PlayerSheetCallbacks()
        )
    }
}

@Preview(
    name = "Player Audio Sheet Preview",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF14161D
)
@Composable
fun PlayerAudioSheetPreview() {
    val sampleTracks = listOf(
        TrackInfo(id = 1, type = TrackType.AUDIO, title = "Japanese FLAC 5.1ch", lang = "jpn", isSelected = true),
        TrackInfo(id = 2, type = TrackType.AUDIO, title = "Chinese Mandarin AAC 2.0ch", lang = "chi", isSelected = false),
        TrackInfo(id = 3, type = TrackType.AUDIO, title = "Director Commentary", lang = "eng", isSelected = false)
    )
    androidx.compose.material3.Surface(
        color = GyroColors.PanelBackground,
        contentColor = Color.White,
        modifier = Modifier.fillMaxSize()
    ) {
        PlayerSettingsSheetContent(
            initialTab = PlayerSheetRoute.AUDIO,
            state = PlayerSheetUiState(
                tracks = sampleTracks,
                softwareVolume = 125.0,
                nightMode = true,
                audioDelaySec = -0.2
            ),
            callbacks = PlayerSheetCallbacks()
        )
    }
}

@Preview(
    name = "Player Speed Sheet Preview",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF14161D
)
@Composable
fun PlayerSpeedSheetPreview() {
    androidx.compose.material3.Surface(
        color = GyroColors.PanelBackground,
        contentColor = Color.White,
        modifier = Modifier.fillMaxSize()
    ) {
        PlayerSettingsSheetContent(
            initialTab = PlayerSheetRoute.SPEED,
            state = PlayerSheetUiState(currentSpeed = 1.25f),
            callbacks = PlayerSheetCallbacks()
        )
    }
}

@Preview(
    name = "Player Stats Sheet Preview",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF14161D
)
@Composable
fun PlayerStatsSheetPreview() {
    androidx.compose.material3.Surface(
        color = GyroColors.PanelBackground,
        contentColor = Color.White,
        modifier = Modifier.fillMaxSize()
    ) {
        PlayerSettingsSheetContent(
            initialTab = PlayerSheetRoute.STATS_INFO,
            state = PlayerSheetUiState(
                hwdec = HwdecMode.HARDWARE_DIRECT.persistedKey,
                hwdecMode = HwdecMode.HARDWARE_DIRECT,
                fps = 59.94,
                droppedFrames = 3L,
                videoWidth = 3840,
                videoHeight = 2160
            ),
            callbacks = PlayerSheetCallbacks()
        )
    }
}

@Preview(
    name = "Player Double-Tap Curved Arc HUD Preview",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF101010
)
@Composable
fun PlayerDoubleTapHudPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
    ) {
        PlayerHudOverlay(
            hudState = GestureHudState.DoubleTap(DoubleTapSide.RIGHT, 10),
            scrubState = remember { PlayerScrubState() },
            durationMs = { 45 * 60_000L }
        )
    }
}

@Preview(
    name = "Player Volume HUD Preview (75%)",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF101010
)
@Composable
fun PlayerVolumeBrightnessHudPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
    ) {
        PlayerHudOverlay(
            hudState = GestureHudState.Volume(volumeRatio = 0.75f, percent = 75),
            scrubState = remember { PlayerScrubState() },
            durationMs = { 45 * 60_000L }
        )
    }
}

@Preview(
    name = "Player Brightness HUD Preview (20%)",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFFEAEAEA
)
@Composable
fun PlayerBrightnessHudPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFEAEAEA))
    ) {
        PlayerHudOverlay(
            hudState = GestureHudState.Brightness(brightnessRatio = 0.20f, percent = 20),
            scrubState = remember { PlayerScrubState() },
            durationMs = { 45 * 60_000L }
        )
    }
}

@Preview(
    name = "Player HUD Compact Slim Preview",
    device = "spec:width=411dp,height=260dp",
    showBackground = true,
    backgroundColor = 0xFF101010
)
@Composable
fun PlayerHudCompactPreview() {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF101010)),
            contentAlignment = Alignment.Center
        ) {
            TopFillPillHud(
                icon = GyroIcons.VolumeHigh,
                contentDescription = "Volume",
                ratio = 0.75f,
                isCompact = true
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFEAEAEA)),
            contentAlignment = Alignment.Center
        ) {
            TopFillPillHud(
                icon = GyroIcons.Brightness,
                contentDescription = "Brightness",
                ratio = 0.20f,
                isCompact = true
            )
        }
    }
}

@Preview(
    name = "Player Scrub Bar Idle vs Dragging",
    device = "spec:width=411dp,height=180dp",
    showBackground = true,
    backgroundColor = 0xFF141418
)
@Composable
fun PlayerScrubBarStatesPreview() {
    val idleState = remember { PlayerScrubState() }
    val draggingState = remember { PlayerScrubState().apply { begin(0.54f) } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141418))
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Idle", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
            PlayerScrubBar(
                timePosMs = 325_000L,
                durationMs = 600_000L,
                bufferedPosMs = 450_000L,
                scrubState = idleState,
                onSeekToFraction = {},
                chapters = sampleChapters
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Dragging", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
            PlayerScrubBar(
                timePosMs = 325_000L,
                durationMs = 600_000L,
                bufferedPosMs = 450_000L,
                scrubState = draggingState,
                onSeekToFraction = {},
                chapters = sampleChapters
            )
        }
    }
}

