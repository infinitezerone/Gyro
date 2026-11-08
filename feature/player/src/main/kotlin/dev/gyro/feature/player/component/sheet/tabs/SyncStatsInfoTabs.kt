package dev.gyro.feature.player.component.sheet.tabs

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
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        SyncCalibrationItem(
            label = stringResource(R.string.sync_sub_delay),
            currentSec = state.subDelaySec,
            onValueChange = callbacks.onSetSubDelay
        )

        HorizontalDivider(color = SheetTrackColor)

        SyncCalibrationItem(
            label = stringResource(R.string.sync_audio_delay),
            currentSec = state.audioDelaySec,
            onValueChange = callbacks.onSetAudioDelay
        )
    }
}

/**
 * 实时性能统计 (StatsTab)：性能卡 + 解码切换 + 渲染管线。
 */
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
        // 0. 常驻诊断浮层开关（控制层隐藏时在画面角落显示紧凑性能胶囊）
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SheetCardColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterSwitchRow(
                title = stringResource(R.string.stats_overlay_toggle),
                description = "控制层隐藏时在画面左上角显示 fps / 丢帧 / 解码器",
                checked = state.statsOverlayEnabled,
                onCheckedChange = callbacks.onToggleStatsOverlay
            )
        }

        // 1. 实时性能监控卡片
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "实时播放性能",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SheetAccentBlue,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

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
                        label = stringResource(R.string.stats_resolution),
                        value = if (state.videoWidth > 0 && state.videoHeight > 0) {
                            "${state.videoWidth} × ${state.videoHeight}"
                        } else {
                            stringResource(R.string.stats_pending)
                        }
                    )
                    MediaInfoRow(
                        label = stringResource(R.string.stats_framerate),
                        value = "${String.format(Locale.US, "%.1f", state.fps)} FPS"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.stats_dropped_frames),
                            fontSize = 13.sp,
                            color = SheetTextSecondaryColor
                        )
                        Text(
                            text = "${state.droppedFrames}",
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (state.droppedFrames > 0) SheetAmber else SheetTextColor,
                                fontFeatureSettings = "tnum"
                            )
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.stats_decoder),
                            fontSize = 13.sp,
                            color = SheetTextSecondaryColor
                        )
                        val isHw = state.hwdec.contains("mediacodec")
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = (if (isHw) SheetGreen else SheetAmber).copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (isHw) stringResource(R.string.stats_hwdec_active) else stringResource(R.string.stats_hwdec_software),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isHw) SheetGreen else SheetAmber,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    state.powerMetrics?.let { pm ->
                        MediaInfoRow(
                            label = stringResource(R.string.stats_power),
                            value = "${String.format(Locale.US, "%.2f", pm.powerWatts)} W"
                        )
                    }
                }
            }
        }

        // 2. 解码器模式切换
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "解码配置",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SheetAccentBlue,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

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
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.stats_render_pipeline),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SheetAccentBlue,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SheetCardColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val isGpuNext = state.voRenderer == dev.gyro.core.model.VO_GPU_NEXT
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.stats_render_backend),
                                fontSize = 13.sp,
                                color = SheetTextSecondaryColor
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isGpuNext) "gpu-next (libplacebo)" else "OpenGL ES (EGL Pipeline)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isGpuNext) SheetAccentBlue else SheetGreen
                            )
                        }
                        callbacks.onToggleVoRenderer?.let { toggle ->
                            FilledTonalButton(
                                onClick = toggle,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = SheetCardAltColor,
                                    contentColor = SheetTextColor
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isGpuNext) "切至 OpenGL ES" else "切至 gpu-next",
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    MediaInfoRow(
                        label = stringResource(R.string.stats_tone_mapping),
                        value = if (isGpuNext) "Reinhard (通透自然 · 动态保真)" else "Hable (电影级保真 · 动态防过曝)"
                    )
                    MediaInfoRow(
                        label = stringResource(R.string.stats_texture_scaler),
                        value = "Spline36 (三次多项式样条插值)"
                    )
                    MediaInfoRow(
                        label = "HDR 峰值亮度计算",
                        value = "hdr-compute-peak = yes (已开启)"
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
