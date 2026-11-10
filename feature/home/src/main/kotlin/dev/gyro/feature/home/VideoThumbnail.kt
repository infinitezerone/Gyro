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
import coil3.video.VideoFrameDecoder
import dev.gyro.core.designsystem.theme.GyroColors

@Composable
fun VideoThumbnail(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isLoaded by remember(uri) { mutableStateOf(false) }

    val request = remember(uri) {
        ImageRequest.Builder(context)
            .data(uri)
            .decoderFactory(VideoFrameDecoder.Factory())
            .crossfade(true)
            .build()
    }

    Box(
        modifier = modifier.background(Color(0xFF222634)),
        contentAlignment = Alignment.Center
    ) {
        if (!isLoaded) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = GyroColors.TextMuted,
                modifier = Modifier.size(28.dp)
            )
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { isLoaded = true },
            onError = { isLoaded = false }
        )
    }
}
