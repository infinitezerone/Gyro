package dev.gyro.feature.player

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap

/** Maximum number of full-screen poster bitmaps retained in composition memory simultaneously. */
internal const val MAX_POSTER_CACHE_SIZE = 12

/**
 * Shared bounded in-memory poster cache backing swipe-transition crossfades. Owned by [PlayerScreen]'s
 * composition; `VideoPagePoster` and the sliding-window preloader read and fill it via main-safe
 * repository callbacks without performing any manual thread switching in the UI layer.
 */
@Composable
internal fun rememberThumbnailCache(): SnapshotStateMap<String, Bitmap> = remember { mutableStateMapOf() }
