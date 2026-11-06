package dev.gyro.feature.home

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.model.PlaybackProgress
import dev.gyro.core.model.VideoMediaItem

@Preview(
    name = "Home Screen - Clean Media Library",
    device = "spec:width=411dp,height=891dp",
    showBackground = true,
    backgroundColor = 0xFF0F1117
)
@Composable
fun HomeScreenPreview() {
    val sampleUri1 = Uri.parse("content://media/external/video/media/1")
    val sampleUri2 = Uri.parse("content://media/external/video/media/2")
    val sampleUri3 = Uri.parse("content://media/external/video/media/3")

    val mockVideos = listOf(
        VideoMediaItem(
            id = 1L,
            uriString = sampleUri1.toString(),
            displayName = "Big Buck Bunny 1080p 60fps HDR.mp4",
            durationMs = 596000L,
            sizeBytes = 285000000L,
            width = 1920,
            height = 1080,
            dateModified = System.currentTimeMillis() / 1000,
            folderName = "Movies"
        ),
        VideoMediaItem(
            id = 2L,
            uriString = sampleUri2.toString(),
            displayName = "Tears of Steel 4K.mkv",
            durationMs = 734000L,
            sizeBytes = 850000000L,
            width = 3840,
            height = 2160,
            dateModified = System.currentTimeMillis() / 1000,
            folderName = "4K Demos"
        ),
        VideoMediaItem(
            id = 3L,
            uriString = sampleUri3.toString(),
            displayName = "Sintel 1080p Animated Short.webm",
            durationMs = 888000L,
            sizeBytes = 150000000L,
            width = 1920,
            height = 1080,
            dateModified = System.currentTimeMillis() / 1000,
            folderName = "Anime"
        )
    )

    val mockPlaybackMap = mapOf(
        sampleUri1.toString() to PlaybackProgress(
            uriString = sampleUri1.toString(),
            positionMs = 325000L,
            durationMs = 596000L,
            lastPlayedTimestamp = System.currentTimeMillis()
        )
    )

    val mockState = HomeUiState(
        isLoading = false,
        hasPermission = true,
        rawVideos = mockVideos,
        filteredVideos = mockVideos,
        folders = listOf("Movies", "4K Demos", "Anime"),
        selectedFolder = null,
        searchQuery = "",
        isSearching = false,
        playbackMap = mockPlaybackMap
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GyroColors.Background)
    ) {
        HomeContent(
            uiState = mockState,
            onPlayVideo = { _, _ -> },
            onOpenFile = {},
            onPickFolder = {},
            onOpenSettings = {},
            onRequestPermission = {},
            onRefresh = {},
            onSelectFolder = {},
            onSearchQueryChange = {},
            onToggleSearch = {}
        )
    }
}
