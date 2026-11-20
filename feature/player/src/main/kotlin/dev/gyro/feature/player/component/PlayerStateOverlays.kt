package dev.gyro.feature.player.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gyro.core.designsystem.R
import dev.gyro.core.domain.model.PlaybackFailureReason
import dev.gyro.core.model.HwdecMode
import dev.gyro.engine.model.PlaybackDiagnostics
import dev.gyro.engine.model.VideoDimensions
import kotlinx.coroutines.flow.StateFlow
import dev.gyro.core.designsystem.icon.GyroIcons

/**
 * Full-screen state cards that sit above the video surface: load failure, empty library, and the
 * buffering spinner. They take no engine state - the caller decides when they apply - so a
 * playback-state change never pulls the player layout into recomposition on their behalf.
 */
@Composable
fun PlayerPlaybackErrorCard(
    error: Throwable,
    onRetry: () -> Unit,
    failureReason: PlaybackFailureReason? = null,
    onSkipNext: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // The cause is not rendered directly; keeping it as a parameter makes the failure identity
    // part of the composable's inputs, so switching to a different error does not reuse stale content.
    @Suppress("UNUSED_EXPRESSION")
    error
    val descriptionRes = when (failureReason) {
        is PlaybackFailureReason.FileNotFound -> R.string.player_error_file_not_found
        is PlaybackFailureReason.PermissionDenied -> R.string.player_error_permission_denied
        is PlaybackFailureReason.CodecCrash -> R.string.player_error_codec_crash
        is PlaybackFailureReason.DemuxError,
        PlaybackFailureReason.Unknown,
        null -> R.string.player_error_desc
    }
    Surface(
        color = Color.Black.copy(alpha = 0.82f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFE57373).copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = GyroIcons.Error,
                contentDescription = null,
                tint = Color(0xFFE57373),
                modifier = Modifier.size(54.dp)
            )
            Text(
                text = stringResource(R.string.player_error_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Icon(GyroIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_retry))
                }
                if (onSkipNext != null) {
                    OutlinedButton(
                        onClick = onSkipNext,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Icon(GyroIcons.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_skip_next))
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerBufferingSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        color = Color(0xFF64B5F6),
        modifier = modifier
    )
}

@Composable
fun PlayerEmptyStateCard(
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.82f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = GyroIcons.VideoLibrary,
                contentDescription = null,
                tint = Color(0xFF64B5F6),
                modifier = Modifier.size(54.dp)
            )
            Text(
                text = stringResource(R.string.player_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = stringResource(R.string.player_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            Button(
                onClick = onOpenFile,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
            ) {
                Icon(GyroIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_open_file))
            }
        }
    }
}

/**
 * Live hardware decoding, framerate, and bitrate diagnostics overlay.
 */
@Composable
fun PlayerStatsOverlay(
    diagnosticsFlow: StateFlow<PlaybackDiagnostics>,
    videoDimensions: VideoDimensions,
    videoFrameRate: Float,
    hwdecMode: HwdecMode,
    bitrate: Long,
    hdrLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val telemetry by diagnosticsFlow.collectAsStateWithLifecycle()
    StatsDiagnosticOverlay(
        fps = telemetry.fps,
        droppedFrames = telemetry.droppedFrames,
        hwdecMode = hwdecMode,
        bitrate = bitrate,
        hdrLabel = hdrLabel,
        sourceWidth = videoDimensions.width,
        sourceHeight = videoDimensions.height,
        sourceFrameRate = videoFrameRate,
        modifier = modifier,
    )
}

