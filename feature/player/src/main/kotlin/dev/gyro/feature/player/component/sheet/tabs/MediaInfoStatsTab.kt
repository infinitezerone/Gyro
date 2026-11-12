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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                text = "实时渲染指标",
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
                            text = if (state.fps > 0) "${String.format(Locale.US, "%.1f", state.fps)} FPS" else "实时 FPS",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = SheetTextColor,
                            style = TextStyle(fontFeatureSettings = "tnum")
                        )
                    }
                    Text(
                        text = "输出帧率",
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
                        text = "丢帧总数",
                        fontSize = 11.sp,
                        color = SheetTextSecondaryColor
                    )
                }

                // 解码器状态
                Column(horizontalAlignment = Alignment.End) {
                    val hwdecText = when {
                        state.hwdec.contains("copy") -> "HW+ 硬件复制"
                        state.hwdec.contains("mediacodec") -> "HW 硬解直出"
                        else -> "SW 纯软解"
                    }
                    Text(
                        text = hwdecText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = SheetAccentBlue
                    )
                    Text(
                        text = "解码管线",
                        fontSize = 11.sp,
                        color = SheetTextSecondaryColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 实时视频码率曲线
            Text(
                text = "实时码率：${formatBitrate(state.videoBitrate)}",
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
                            text = "正在采样码率波形...",
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
                title = "屏幕悬浮性能监视窗",
                description = "在画面左上角实时常驻帧率与性能 HUD",
                checked = state.statsOverlayEnabled,
                onCheckedChange = callbacks.onToggleStatsOverlay
            )
        }

        HorizontalDivider(color = SheetCardAltColor.copy(alpha = 0.5f), thickness = 0.5.dp)

        // ==================== 2. 媒体硬核属性详情 ====================
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "片源媒体规格",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SheetAccentBlue
            )

            val codec = resolvedInfo.videoCodec.ifBlank { "未知" }
            val resolution = resolvedInfo.videoResolution.ifBlank {
                if (state.videoWidth > 0) "${state.videoWidth} × ${state.videoHeight}" else "未知"
            }
            val hdr = if (state.isHdr) {
                if (state.hdrFormat.isNotBlank()) state.hdrFormat else "HDR"
            } else {
                "SDR 标准动态范围"
            }

            MediaInfoRow(label = "视频编码", value = codec.uppercase())
            MediaInfoRow(label = "物理分辨率", value = resolution)
            MediaInfoRow(label = "动态范围", value = hdr)
            if (resolvedInfo.videoFps > 0) {
                MediaInfoRow(
                    label = "原生帧率",
                    value = "${String.format(Locale.US, "%.3f", resolvedInfo.videoFps)} FPS"
                )
            }

            val aCodec = resolvedInfo.audioCodec.ifBlank { "未知" }
            val channels = resolvedInfo.audioChannels.ifBlank { "立体声 2.0" }
            MediaInfoRow(label = "音频编码与通道", value = "${aCodec.uppercase()} · $channels")

            if (resolvedInfo.containerFormat.isNotBlank()) {
                MediaInfoRow(label = "封装容器", value = resolvedInfo.containerFormat.uppercase())
            }
            if (resolvedInfo.fileSize > 0) {
                MediaInfoRow(label = "文件大小", value = resolvedInfo.formattedFileSize)
            }
            if (resolvedInfo.durationMs > 0) {
                val durStr = formatDuration(resolvedInfo.durationMs)
                MediaInfoRow(label = "片源总长", value = durStr)
            }
        }
    }
}
