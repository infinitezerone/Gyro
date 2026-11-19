package dev.gyro.feature.home

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.feature.home.thumbnail.ContentThumbnailFetcher
import dev.gyro.feature.home.thumbnail.VideoThumbnailLoader

/**
 * High-performance video thumbnail component powered by Coil 3.
 *
 * Uses [VideoThumbnailLoader] to automatically prioritize hardware-accelerated system thumbnails
 * via [ContentThumbnailFetcher], and throttles fallback frame extractions to protect UI responsiveness.
 */
@Composable
fun VideoThumbnail(
    uri: Uri,
    modifier: Modifier = Modifier,
    targetWidth: Int = ContentThumbnailFetcher.DEFAULT_WIDTH,
    targetHeight: Int = ContentThumbnailFetcher.DEFAULT_HEIGHT,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    placeholder: @Composable (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var isLoaded by remember(uri) { mutableStateOf(false) }

    val imageLoader = remember(context) { VideoThumbnailLoader.get(context) }
    val request = remember(uri, context, targetWidth, targetHeight) {
        ImageRequest.Builder(context)
            .data(uri)
            .size(targetWidth, targetHeight)
            .crossfade(true)
            .build()
    }

    Box(
        modifier = modifier.background(Color(0xFF222634)),
        contentAlignment = Alignment.Center
    ) {
        if (!isLoaded) {
            if (placeholder != null) {
                placeholder()
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = GyroColors.TextMuted,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { isLoaded = true },
            onError = { isLoaded = false }
        )
    }
}
