package dev.gyro.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long edge the poster bitmaps are decoded at - tall enough for full-screen portrait posters. */
private val THUMBNAIL_SIZE = Size(720, 1280)

/**
 * Shared in-memory poster cache backing swipe-transition crossfades. Owned by [PlayerScreen]'s
 * composition; [VideoPagePoster] and the playlist preloader read and fill it.
 */
@Composable
internal fun rememberThumbnailCache(): SnapshotStateMap<String, Bitmap> = remember { mutableStateMapOf() }

/**
 * Decodes a poster frame for [uriString]: MediaStore thumbnails for content URIs, `ThumbnailUtils`
 * for file paths, with the pre-Q legacy overload where needed. Returns null for unparseable URIs
 * or decode failures - callers keep showing the filename placeholder in that case.
 */
internal suspend fun loadVideoThumbnail(context: Context, uriString: String): Bitmap? {
    val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull() ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
                context.contentResolver.loadThumbnail(uri, THUMBNAIL_SIZE, null)
            } else {
                val path = uri.path ?: uriString
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.media.ThumbnailUtils.createVideoThumbnail(java.io.File(path), THUMBNAIL_SIZE, null)
                } else {
                    @Suppress("DEPRECATION")
                    android.media.ThumbnailUtils.createVideoThumbnail(
                        path,
                        android.provider.MediaStore.Images.Thumbnails.MINI_KIND
                    )
                }
            }
        }.getOrNull()
    }
}
