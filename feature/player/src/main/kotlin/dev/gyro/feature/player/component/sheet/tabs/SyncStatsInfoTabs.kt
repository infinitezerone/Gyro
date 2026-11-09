package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.gyro.feature.player.component.sheet.common.formatBitrate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import dev.gyro.feature.player.component.sheet.common.MediaInfoGroup
import dev.gyro.feature.player.component.sheet.common.MediaInfoRow
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetSectionHeader
import dev.gyro.feature.player.component.sheet.common.SheetAmber
import dev.gyro.feature.player.component.sheet.common.SheetCardAltColor
import dev.gyro.feature.player.component.sheet.common.SheetCardColor
import dev.gyro.feature.player.component.sheet.common.SheetGreen
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.SheetTextColor
import dev.gyro.feature.player.component.sheet.common.SheetTrackColor
import dev.gyro.feature.player.component.sheet.common.SyncCalibrationItem
import java.util.Locale

/**
 * A/V 同步校准 (SyncTab)：字幕延迟 + 音频延迟双卡。
 */
@Composable
internal fun SyncTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SheetSectionHeader(stringResource(R.string.sync_sub_delay))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SheetCardColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Box(modifier = Modifier.padding(14.dp)) {
                    SyncCalibrationItem(
                        label = stringResource(R.string.sync_sub_delay),
                        currentSec = state.subDelaySec,
                        accentColor = SheetAccentBlue,
                        onValueChange = callbacks.onSetSubDelay
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SheetSectionHeader(stringResource(R.string.sync_audio_delay))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SheetCardColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Box(modifier = Modifier.padding(14.dp)) {
                    SyncCalibrationItem(
                        label = stringResource(R.string.sync_audio_delay),
                        currentSec = state.audioDelaySec,
                        accentColor = SheetAccentBlue,
                        onValueChange = callbacks.onSetAudioDelay
                    )
                }
            }
        }
    }
}

/**
 * 实时性能统计 (StatsTab)：性能卡 + 解码切换 + 渲染管线。
 */

/**
 * 实时码率波形走势图 (LiveBitrateChart)
 */
@Composable
internal fun LiveBitrateChart(
    bitrateHistory: List<Long>,
    currentBitrate: Long,
    audioBitrate: Long = 0L,
    fps: Double = 0.0,
    droppedFrames: Long = 0L,
    modifier: Modifier = Modifier
) {
    val peakBitrate = remember(bitrateHistory, currentBitrate) {
        maxOf(bitrateHistory.maxOrNull() ?: currentBitrate, currentBitrate, 1_000_000L)
    }
    val avgBitrate = remember(bitrateHistory, currentBitrate) {
        if (bitrateHistory.isNotEmpty()) {
            bitrateHistory.average().toLong()
        } else {
            currentBitrate
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SheetCardColor,
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 1. 顶部标题与实时呼吸指示灯
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "实时视频码率",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SheetTextColor
                    )
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.35f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )
                    Surface(
                        shape = CircleShape,
                        color = SheetAccentBlue.copy(alpha = pulseAlpha),
                        modifier = Modifier.size(7.dp)
                    ) {}
                    Text(
                        text = "LIVE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SheetAccentBlue
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = "峰值", fontSize = 10.sp, color = SheetTextSecondaryColor)
                        Text(
                            text = formatBitrate(peakBitrate),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = SheetAmber,
                            style = TextStyle(fontFeatureSettings = "tnum")
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = "均值", fontSize = 10.sp, color = SheetTextSecondaryColor)
                        Text(
                            text = formatBitrate(avgBitrate),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = SheetTextColor,
                            style = TextStyle(fontFeatureSettings = "tnum")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 瞬时码率大数展示
            Text(
                text = formatBitrate(currentBitrate),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = SheetAccentBlue,
                style = TextStyle(fontFeatureSettings = "tnum")
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 实时平滑走势曲线 Canvas
            val samples = if (bitrateHistory.size >= 2) {
                bitrateHistory
            } else if (bitrateHistory.size == 1) {
                listOf(bitrateHistory[0], bitrateHistory[0])
            } else if (currentBitrate > 0) {
                listOf(currentBitrate, currentBitrate)
            } else {
                emptyList()
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.25f))
            ) {
                if (samples.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "正在采样码率波形...",
                            fontSize = 12.sp,
                            color = SheetTextSecondaryColor
                        )
                    }
                } else {
                    val maxVal = maxOf(samples.maxOrNull() ?: 1L, 100_000L).toFloat() * 1.15f
                    val gridColor = Color.White.copy(alpha = 0.06f)
                    val lineColor = SheetAccentBlue

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        val w = size.width
                        val h = size.height

                        // 三条基准参考虚线
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, 0f),
                            end = Offset(w, 0f),
                            strokeWidth = 1f
                        )
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, h * 0.5f),
                            end = Offset(w, h * 0.5f),
                            strokeWidth = 1f
                        )
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, h),
                            end = Offset(w, h),
                            strokeWidth = 1f
                        )

                        val stepX = w / (samples.size - 1).coerceAtLeast(1)
                        val points = samples.mapIndexed { idx, value ->
                            val x = idx * stepX
                            val y = h - ((value.toFloat() / maxVal).coerceIn(0f, 1f) * (h - 4f))
                            Offset(x, y)
                        }

                        val linePath = Path()
                        val fillPath = Path()

                        linePath.moveTo(points.first().x, points.first().y)
                        fillPath.moveTo(points.first().x, h)
                        fillPath.lineTo(points.first().x, points.first().y)

                        for (i in 0 until points.size - 1) {
                            val p0 = points[i]
                            val p1 = points[i + 1]
                            val cx = (p0.x + p1.x) / 2f
                            linePath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                            fillPath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                        }

                        fillPath.lineTo(points.last().x, h)
                        fillPath.close()

                        // 曲线下部渐变着色
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    lineColor.copy(alpha = 0.35f),
                                    lineColor.copy(alpha = 0.05f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = h
                            )
                        )

                        // 贝塞尔走势曲线
                        drawPath(
                            path = linePath,
                            color = lineColor,
                            style = Stroke(
                                width = 2.5.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )

                        // 顶端最新采样动态指示光圈
                        val lastPoint = points.last()
                        drawCircle(
                            color = lineColor.copy(alpha = 0.30f),
                            radius = 7.dp.toPx(),
                            center = lastPoint
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 3.dp.toPx(),
                            center = lastPoint
                        )
                    }

                    // 刻度标尺读数
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatBitrate((maxVal * 0.95f).toLong()),
                            fontSize = 9.sp,
                            color = SheetTextSecondaryColor.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "0 kbps",
                            fontSize = 9.sp,
                            color = SheetTextSecondaryColor.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. 关联播放性能指标小卡片组
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricChip(
                    label = "实时帧率",
                    value = if (fps > 0) String.format(Locale.US, "%.1f FPS", fps) else "--",
                    modifier = Modifier.weight(1f)
                )
                MetricChip(
                    label = "丢帧计数",
                    value = "$droppedFrames 帧",
                    valueColor = if (droppedFrames > 0) SheetAmber else SheetGreen,
                    modifier = Modifier.weight(1f)
                )
                if (audioBitrate > 0) {
                    MetricChip(
                        label = "音频码率",
                        value = formatBitrate(audioBitrate),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricChip(
    label: String,
    value: String,
    valueColor: Color = SheetTextColor,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = SheetCardAltColor,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = label, fontSize = 10.sp, color = SheetTextSecondaryColor)
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
        }
    }
}

@Composable
internal fun StatsTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 实时码率波形走势图 (核心图表)
        LiveBitrateChart(
            bitrateHistory = state.bitrateHistory,
            currentBitrate = state.videoBitrate,
            audioBitrate = state.audioBitrate,
            fps = state.fps,
            droppedFrames = state.droppedFrames
        )

        // 2. 常驻性能浮层开关
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SheetCardColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterSwitchRow(
                title = stringResource(R.string.stats_overlay_toggle),
                description = "在画面角落显示实时帧率与码率",
                checked = state.statsOverlayEnabled,
                onCheckedChange = callbacks.onToggleStatsOverlay
            )
        }

        // 2. 解码器模式切换
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SheetSectionHeader("解码配置", modifier = Modifier.padding(start = 4.dp))

            val isHw = state.hwdec.contains("mediacodec")
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SheetCardColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isHw) SheetGreen.copy(alpha = 0.15f) else SheetAmber.copy(alpha = 0.15f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = if (isHw) SheetGreen else SheetAmber,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.stats_hwdec_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = SheetTextColor
                            )
                            Text(
                                text = if (isHw) stringResource(R.string.stats_hwdec_active) else stringResource(R.string.stats_hwdec_software),
                                fontSize = 12.sp,
                                color = if (isHw) SheetGreen else SheetAmber
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = callbacks.onToggleHwdec,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SheetCardAltColor,
                            contentColor = SheetTextColor
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = stringResource(if (isHw) R.string.stats_switch_to_sw else R.string.stats_switch_to_hw),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 3. 渲染管线配置
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SheetSectionHeader(stringResource(R.string.stats_render_pipeline), modifier = Modifier.padding(start = 4.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SheetCardColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MediaInfoRow(
                        label = stringResource(R.string.stats_render_backend),
                        value = "OpenGL ES (EGL Pipeline)"
                    )
                    MediaInfoRow(
                        label = stringResource(R.string.stats_tone_mapping),
                        value = "Hable"
                    )
                    MediaInfoRow(
                        label = stringResource(R.string.stats_texture_scaler),
                        value = "Spline36"
                    )
                    MediaInfoRow(
                        label = "HDR 峰值亮度",
                        value = "已开启"
                    )
                }
            }
        }
    }
}

/**
 * 媒体文件信息 (InfoTab)
 */
@Composable
internal fun MediaInfoTabContent(
    state: PlayerSheetUiState
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
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 容器与文件
        MediaInfoGroup(title = stringResource(R.string.info_group_file)) {
            if (resolvedInfo.title.isNotBlank()) {
                MediaInfoRow(label = stringResource(R.string.info_title), value = resolvedInfo.title)
            }
            if (resolvedInfo.containerFormat.isNotBlank()) {
                MediaInfoRow(
                    label = stringResource(R.string.info_container),
                    value = resolvedInfo.containerFormat.uppercase()
                )
            }
            if (resolvedInfo.fileSize > 0) {
                MediaInfoRow(label = stringResource(R.string.info_file_size), value = resolvedInfo.formattedFileSize)
            }
            if (resolvedInfo.durationMs > 0) {
                val sec = resolvedInfo.durationMs / 1000
                val min = sec / 60
                val remSec = sec % 60
                val durStr = String.format(Locale.US, "%02d:%02d", min, remSec)
                MediaInfoRow(label = stringResource(R.string.info_duration), value = durStr)
            }
        }

        // 2. 视频流
        MediaInfoGroup(title = stringResource(R.string.info_group_video)) {
            val codec = resolvedInfo.videoCodec.ifBlank { "未知" }
            MediaInfoRow(label = stringResource(R.string.info_video_codec), value = codec.uppercase())
            if (resolvedInfo.videoResolution.isNotBlank()) {
                MediaInfoRow(
                    label = stringResource(R.string.info_video_resolution),
                    value = resolvedInfo.videoResolution
                )
            }
            if (resolvedInfo.videoFps > 0) {
                MediaInfoRow(
                    label = stringResource(R.string.info_video_framerate),
                    value = "${String.format(Locale.US, "%.2f", resolvedInfo.videoFps)} FPS"
                )
            }
            if (resolvedInfo.videoBitrate > 0) {
                MediaInfoRow(
                    label = stringResource(R.string.info_video_bitrate),
                    value = resolvedInfo.formattedVideoBitrate
                )
            }
            val isHw = resolvedInfo.hwdecCurrent.contains("mediacodec")
            MediaInfoRow(
                label = stringResource(R.string.stats_decoder),
                value = if (isHw) stringResource(R.string.stats_hwdec_active) else stringResource(R.string.stats_hwdec_software)
            )
        }

        // 3. 音频流
        MediaInfoGroup(title = stringResource(R.string.info_group_audio)) {
            val aCodec = resolvedInfo.audioCodec.ifBlank { "未知" }
            MediaInfoRow(label = stringResource(R.string.info_audio_codec), value = aCodec.uppercase())
            if (resolvedInfo.audioChannels.isNotBlank()) {
                MediaInfoRow(
                    label = stringResource(R.string.info_audio_channels),
                    value = resolvedInfo.audioChannels
                )
            }
            if (resolvedInfo.audioSampleRate > 0) {
                MediaInfoRow(
                    label = stringResource(R.string.info_audio_samplerate),
                    value = resolvedInfo.formattedSampleRate
                )
            }
            if (resolvedInfo.audioBitrate > 0) {
                MediaInfoRow(
                    label = stringResource(R.string.info_audio_bitrate),
                    value = resolvedInfo.formattedAudioBitrate
                )
            }
        }
    }
}
