package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.model.MediaInfo
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.FilterSwitchRow
import dev.gyro.feature.player.component.sheet.common.MediaInfoRow
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetAmber
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetGreen
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.formatBitrate
import dev.gyro.core.model.time.formatDuration
import java.util.Locale

/**
 * 媒体信息与性能诊断合体看板 (MediaInfoStatsTab) - 高密度扁平化纯平铺布局
 */
@Composable
internal fun MediaInfoStatsTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    val resolvedInfo = state.mediaInfo ?: MediaInfo(
        videoResolution = if (state.videoWidth > 0 && state.videoHeight > 0) {
            "${state.videoWidth} × ${state.videoHeight}"
        } else {
            ""
        },
        videoFps = state.fps,
        hwdecCurrent = state.hwdec,
        droppedFrames = state.droppedFrames
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ==================== 1. 实时渲染与性能快照 ====================
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.stats_realtime_section),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue
            )

            // 三核心指标横向平铺 (FPS / 丢帧 / 解码管线)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // FPS 徽章
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val pulseAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "fpsPulse"
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(SheetGreen.copy(alpha = pulseAlpha))
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (state.fps > 0) {
                                "${String.format(Locale.US, "%.1f", state.fps)} FPS"
                            } else {
                                stringResource(R.string.stats_fps_placeholder)
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = SheetTextColor,
                            style = TextStyle(fontFeatureSettings = "tnum")
                        )
                    }
                    Text(
                        text = stringResource(R.string.stats_fps_label),
                        fontSize = 11.sp,
                        color = SheetTextSecondaryColor
                    )
                }

                // 丢帧指示
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${state.droppedFrames}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.droppedFrames > 0) SheetAmber else SheetGreen,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                    Text(
                        text = stringResource(R.string.stats_dropped_total),
                        fontSize = 11.sp,
                        color = SheetTextSecondaryColor
                    )
                }

                // 解码器状态
                Column(horizontalAlignment = Alignment.End) {
                    val hwdecText = when {
                        state.hwdec.contains("copy") -> stringResource(R.string.hwdec_copy)
                        state.hwdec.contains("mediacodec") -> stringResource(R.string.hwdec_direct)
                        else -> stringResource(R.string.hwdec_sw)
                    }
                    Text(
                        text = hwdecText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = SheetAccentBlue
                    )
                    Text(
                        text = stringResource(R.string.stats_decode_pipeline),
                        fontSize = 11.sp,
                        color = SheetTextSecondaryColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 实时视频码率曲线
            Text(
                text = stringResource(R.string.stats_live_bitrate_fmt, formatBitrate(state.videoBitrate)),
                fontSize = 11.sp,
                color = SheetTextSecondaryColor
            )

            val samples = if (state.bitrateHistory.size >= 2) {
                state.bitrateHistory
            } else if (state.bitrateHistory.size == 1) {
                listOf(state.bitrateHistory[0], state.bitrateHistory[0])
            } else if (state.videoBitrate > 0) {
                listOf(state.videoBitrate, state.videoBitrate)
            } else {
                emptyList()
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(65.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.25f))
            ) {
                if (samples.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.stats_sampling_bitrate),
                            fontSize = 11.sp,
                            color = SheetTextSecondaryColor
                        )
                    }
                } else {
                    val maxVal = maxOf(samples.maxOrNull() ?: 1L, 100_000L).toFloat() * 1.15f
                    val lineColor = SheetAccentBlue
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        val w = size.width
                        val h = size.height
                        val stepX = w / (samples.size - 1).coerceAtLeast(1)
                        val path = Path()

                        samples.forEachIndexed { idx, v ->
                            val clamped = v.coerceAtLeast(0L).toFloat()
                            val norm = (clamped / maxVal).coerceIn(0f, 1f)
                            val x = idx * stepX
                            val y = h - (norm * h)
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = lineColor,
                            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 悬浮性能监视窗开关
            FilterSwitchRow(
                title = stringResource(R.string.stats_overlay_screen_title),
                description = stringResource(R.string.stats_overlay_screen_desc),
                checked = state.statsOverlayEnabled,
                onCheckedChange = callbacks.onToggleStatsOverlay
            )
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // ==================== 2. 媒体硬核属性详情 ====================
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.info_source_section),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue
            )

            val codec = resolvedInfo.videoCodec.ifBlank { stringResource(R.string.common_unknown) }
            val resolution = resolvedInfo.videoResolution.ifBlank {
                if (state.videoWidth > 0) "${state.videoWidth} × ${state.videoHeight}" else stringResource(R.string.common_unknown)
            }
            val hdr = if (state.isHdr) {
                if (state.hdrFormat.isNotBlank()) state.hdrFormat else "HDR"
            } else {
                stringResource(R.string.dynamic_range_sdr)
            }

            MediaInfoRow(label = stringResource(R.string.info_video_codec), value = codec.uppercase())
            MediaInfoRow(label = stringResource(R.string.info_native_resolution), value = resolution)
            MediaInfoRow(label = stringResource(R.string.info_dynamic_range), value = hdr)
            if (resolvedInfo.videoFps > 0) {
                MediaInfoRow(
                    label = stringResource(R.string.info_native_framerate),
                    value = "${String.format(Locale.US, "%.3f", resolvedInfo.videoFps)} FPS"
                )
            }

            val aCodec = resolvedInfo.audioCodec.ifBlank { stringResource(R.string.common_unknown) }
            val channels = resolvedInfo.audioChannels.ifBlank { stringResource(R.string.audio_channels_default) }
            MediaInfoRow(label = stringResource(R.string.info_audio_codec_channels), value = "${aCodec.uppercase()} · $channels")

            if (resolvedInfo.containerFormat.isNotBlank()) {
                MediaInfoRow(label = stringResource(R.string.info_container_type), value = resolvedInfo.containerFormat.uppercase())
            }
            if (resolvedInfo.fileSize > 0) {
                MediaInfoRow(label = stringResource(R.string.info_file_size), value = resolvedInfo.formattedFileSize)
            }
            if (resolvedInfo.durationMs > 0) {
                val durStr = formatDuration(resolvedInfo.durationMs)
                MediaInfoRow(label = stringResource(R.string.info_source_duration), value = durStr)
            }
        }
    }
}
