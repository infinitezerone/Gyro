package dev.gyro.feature.settings.folders

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.gyro.core.model.CustomFolderDetail

@Preview(name = "FolderManagementContentPreview", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun FolderManagementContentPreview() {
    val sampleFolders = listOf(
        CustomFolderDetail(
            uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AAnime"),
            displayName = "Anime",
            videoCount = 24,
            totalSizeBytes = 12_884_901_888L
        ),
        CustomFolderDetail(
            uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies"),
            displayName = "Movies",
            videoCount = 8,
            totalSizeBytes = 34_359_738_368L
        )
    )

    FolderManagementContent(
        uiState = FolderManagementUiState(isLoading = false, folders = sampleFolders),
        onBack = {},
        onAddFolder = {},
        onRevokeFolder = {},
        onRefresh = {}
    )
}

@Preview(name = "FolderManagementEmptyPreview", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun FolderManagementEmptyPreview() {
    FolderManagementContent(
        uiState = FolderManagementUiState(isLoading = false, folders = emptyList()),
        onBack = {},
        onAddFolder = {},
        onRevokeFolder = {},
        onRefresh = {}
    )
}
