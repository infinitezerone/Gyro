package dev.gyro.core.data.repository

import android.net.Uri
import dev.gyro.core.model.CustomFolderDetail
import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.flow.Flow

/**
 * Persists the SAF tree URIs the user granted and scans them for video files.
 *
 * The persisted grant survives reboots, so this repository owns the only path that may call
 * `takePersistableUriPermission` / `releasePersistableUriPermission`.
 */
interface CustomFolderRepository {
    /** Grants currently held. Cold: each collector reads the store, the repository caches nothing. */
    val savedFolderUris: Flow<Set<Uri>>

    suspend fun addFolder(treeUri: Uri): Boolean

    suspend fun removeFolder(treeUri: Uri)

    suspend fun getFolderDetails(): List<CustomFolderDetail>

    suspend fun scanCustomFolders(): List<VideoMediaItem>
}
