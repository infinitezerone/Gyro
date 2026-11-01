package dev.gyro.player.ui.home

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.gyro.player.data.model.VideoMediaItem

@Preview(
    name = "Home Screen - Media Library",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF0F1117
)
@Composable
fun HomeScreenPreview() {
    val sampleVideos = listOf(
        VideoMediaItem(
            id = 1L,
            uri = Uri.EMPTY,
            displayName = "Big Buck Bunny 4K 60fps HDR.mp4",
            durationMs = 596_000L,
            sizeBytes = 1_288_490_188L, // ~1.2 GB
            width = 3840,
            height = 2160,
            dateModified = 1711929600L,
            folderName = "Movies"
        ),
        VideoMediaItem(
            id = 2L,
            uri = Uri.EMPTY,
            displayName = "Tears of Steel 1080p Sci-Fi.mkv",
            durationMs = 734_000L,
            sizeBytes = 644_245_094L, // ~614 MB
            width = 1920,
            height = 1080,
            dateModified = 1711843200L,
            folderName = "Download"
        ),
        VideoMediaItem(
            id = 3L,
            uri = Uri.EMPTY,
            displayName = "Sintel Animated Short Movie 720p.mp4",
            durationMs = 888_000L,
            sizeBytes = 320_000_000L,
            width = 1280,
            height = 720,
            dateModified = 1711756800L,
            folderName = "Movies"
        ),
        VideoMediaItem(
            id = 4L,
            uri = Uri.EMPTY,
            displayName = "VID_20260401_Camera_Rec.mp4",
            durationMs = 45_000L,
            sizeBytes = 110_000_000L,
            width = 1920,
            height = 1080,
            dateModified = 1711670400L,
            folderName = "Camera"
        )
    )

    val state = HomeUiState(
        isLoading = false,
        hasPermission = true,
        rawVideos = sampleVideos,
        filteredVideos = sampleVideos,
        folders = listOf("Camera", "Download", "Movies"),
        selectedFolder = null,
        searchQuery = "",
        isSearching = false
    )

    HomeContent(
        uiState = state,
        onPlayVideo = {},
        onOpenFile = {},
        onRequestPermission = {},
        onRefresh = {},
        onSelectFolder = {},
        onSearchQueryChange = {},
        onToggleSearch = {}
    )
}
