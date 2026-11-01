package dev.gyro.player.ui.home

import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import dev.gyro.player.data.model.VideoMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val thumbnailMemoryCache = object : LruCache<Long, Bitmap>(50) {}

@Composable
fun VideoThumbnail(
    item: VideoMediaItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val bitmapState = produceState<Bitmap?>(initialValue = thumbnailMemoryCache.get(item.id), key1 = item.id) {
        val cached = thumbnailMemoryCache.get(item.id)
        if (cached != null) {
            value = cached
            return@produceState
        }

        val loaded = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(item.uri, Size(320, 180), null)
                } else {
                    @Suppress("DEPRECATION")
                    android.provider.MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver,
                        item.id,
                        android.provider.MediaStore.Video.Thumbnails.MINI_KIND,
                        null
                    )
                }
            } catch (e: Exception) {
                null
            }
        }

        if (loaded != null) {
            thumbnailMemoryCache.put(item.id, loaded)
            value = loaded
        }
    }

    val bitmap = bitmapState.value

    Box(
        modifier = modifier.background(Color(0xFF1E222B)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.PlayCircleFilled,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.25f)
            )
        }
    }
}
