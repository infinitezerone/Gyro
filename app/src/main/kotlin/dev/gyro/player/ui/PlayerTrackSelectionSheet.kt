package dev.gyro.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.TrackInfo
import dev.gyro.player.R
import java.util.Locale

enum class PlayerSheetTab(@StringRes val titleRes: Int) {
    PLAYLIST(R.string.sheet_tab_playlist),
    SUBTITLES(R.string.sheet_tab_subtitles),
    AUDIO(R.string.sheet_tab_audio),
    SYNC(R.string.sheet_tab_sync),
    SPEED(R.string.sheet_tab_speed),
    ASPECT(R.string.sheet_tab_aspect),
    STATS(R.string.sheet_tab_stats)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerTrackSelectionSheet(
    initialTab: PlayerSheetTab = PlayerSheetTab.SUBTITLES,
    playlist: List<VideoMediaItem> = emptyList(),
    currentPlaylistIndex: Int = -1,
    tracks: List<TrackInfo>,
    currentResizeMode: ResizeMode,
    currentSpeed: Float,
    subDelaySec: Double,
    audioDelaySec: Double,
    subScale: Double = 1.0,
    subPos: Long = 100L,
    hwdec: String = "",
    fps: Double = 0.0,
    droppedFrames: Long = 0L,
    videoWidth: Int = 0,
    videoHeight: Int = 0,
    powerMetrics: PowerMetrics? = null,
    onDismiss: () -> Unit,
    onSelectPlaylistItem: (Int) -> Unit = {},
    onSelectTrack: (type: String, id: Int) -> Unit,
    onSelectResizeMode: (ResizeMode) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSetSubDelay: (Double) -> Unit,
    onSetAudioDelay: (Double) -> Unit,
    onSetSubScale: (Double) -> Unit = {},
    onSetSubPos: (Long) -> Unit = {},
    onToggleHwdec: () -> Unit = {},
    onAddExternalSubtitle: () -> Unit,
    onOpenFullSettings: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableStateOf(initialTab) }

    val audioTracks = remember(tracks) { tracks.filter { it.type == "audio" } }
    val subtitleTracks = remember(tracks) { tracks.filter { it.type == "sub" } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.cd_settings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onOpenFullSettings, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings_title),
                        tint = Color(0xFF64B5F6),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            @Suppress("DEPRECATION")
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                edgePadding = 16.dp,
                containerColor = Color(0xFF1E1E1E),
                contentColor = Color.White
            ) {
                PlayerSheetTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Text(
                                text = stringResource(tab.titleRes),
                                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 14.sp
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            when (selectedTab) {
                PlayerSheetTab.PLAYLIST -> {
                    if (playlist.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.playlist_empty), color = Color.White.copy(alpha = 0.5f))
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                            itemsIndexed(playlist) { index, item ->
                                val isCurrent = index == currentPlaylistIndex
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectPlaylistItem(index) }
                                        .background(if (isCurrent) Color(0xFF64B5F6).copy(alpha = 0.12f) else Color.Transparent)
                                        .padding(horizontal = 24.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isCurrent) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = stringResource(R.string.playlist_current_playing),
                                            tint = Color(0xFF64B5F6),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Text(
                                            text = "${index + 1}",
                                            fontSize = 13.sp,
                                            color = Color.White.copy(alpha = 0.5f),
                                            modifier = Modifier.width(20.dp),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.displayName,
                                            fontSize = 14.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isCurrent) Color(0xFF64B5F6) else Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (item.durationMs > 0L) {
                                            Text(
                                                text = item.formattedDuration,
                                                fontSize = 11.sp,
                                                color = Color.White.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                PlayerSheetTab.SUBTITLES -> {
                    // Subtitles Tab
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                        item {
                            TrackItem(
                                title = stringResource(R.string.track_none_subtitle),
                                subtitle = "Off",
                                isSelected = subtitleTracks.none { it.isSelected },
                                onClick = { onSelectTrack("sub", 0) }
                            )
                        }
                        items(subtitleTracks) { track ->
                            TrackItem(
                                title = track.title ?: "内置字幕 #${track.id}",
                                subtitle = track.lang?.uppercase() ?: "ASS/SRT",
                                isSelected = track.isSelected,
                                onClick = { onSelectTrack("sub", track.id) }
                            )
                        }
                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.1f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAddExternalSubtitle() }
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF64B5F6))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(stringResource(R.string.track_add_external_sub), color = Color(0xFF64B5F6), fontWeight = FontWeight.Medium)
                            }
                        }
                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.1f))
                            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
                                SyncCalibrationItem(
                                    label = stringResource(R.string.sync_sub_delay),
                                    currentSec = subDelaySec,
                                    onValueChange = onSetSubDelay
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(stringResource(R.string.sub_scale_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(0.8, 1.0, 1.2, 1.5).forEach { scale ->
                                        FilterChip(
                                            selected = Math.abs(subScale - scale) < 0.05,
                                            onClick = { onSetSubScale(scale) },
                                            label = { Text("${scale}x") }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(stringResource(R.string.sub_pos_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        100L to stringResource(R.string.sub_pos_bottom),
                                        95L to stringResource(R.string.sub_pos_raised),
                                        90L to stringResource(R.string.sub_pos_middle)
                                    ).forEach { (pos, label) ->
                                        FilterChip(
                                            selected = subPos == pos,
                                            onClick = { onSetSubPos(pos) },
                                            label = { Text(label) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                PlayerSheetTab.AUDIO -> {
                    // Audio Tracks
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                        items(audioTracks) { track ->
                            TrackItem(
                                title = track.title ?: "音频轨道 #${track.id}",
                                subtitle = track.lang?.uppercase() ?: "Undetermined",
                                isSelected = track.isSelected,
                                onClick = { onSelectTrack("audio", track.id) }
                            )
                        }
                        if (audioTracks.isEmpty()) {
                            item {
                                EmptyTrackNotice(stringResource(R.string.track_none_audio))
                            }
                        }
                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.1f))
                            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                                SyncCalibrationItem(
                                    label = stringResource(R.string.sync_audio_delay),
                                    currentSec = audioDelaySec,
                                    onValueChange = onSetAudioDelay
                                )
                            }
                        }
                    }
                }
                PlayerSheetTab.SYNC -> {
                    // Sync & Tuning (Both Audio & Subtitle Delays)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        SyncCalibrationItem(
                            label = stringResource(R.string.sync_sub_delay),
                            currentSec = subDelaySec,
                            onValueChange = onSetSubDelay
                        )

                        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                        SyncCalibrationItem(
                            label = stringResource(R.string.sync_audio_delay),
                            currentSec = audioDelaySec,
                            onValueChange = onSetAudioDelay
                        )
                    }
                }
                PlayerSheetTab.SPEED -> {
                    // Speed Tab
                    val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.speed_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            speedOptions.forEach { sp ->
                                FilterChip(
                                    selected = currentSpeed == sp,
                                    onClick = { onSelectSpeed(sp) },
                                    label = { Text("${sp}x") }
                                )
                            }
                        }
                    }
                }
                PlayerSheetTab.ASPECT -> {
                    // Aspect / ResizeMode
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ResizeMode.entries.forEach { mode ->
                            TrackItem(
                                title = mode.label,
                                subtitle = when (mode) {
                                    ResizeMode.FIT -> stringResource(R.string.aspect_fit_desc)
                                    ResizeMode.CROP -> stringResource(R.string.aspect_crop_desc)
                                    ResizeMode.STRETCH -> stringResource(R.string.aspect_stretch_desc)
                                    ResizeMode.ASPECT_16_9 -> stringResource(R.string.aspect_16_9_desc)
                                    ResizeMode.ASPECT_21_9 -> stringResource(R.string.aspect_21_9_desc)
                                },
                                isSelected = currentResizeMode == mode,
                                onClick = { onSelectResizeMode(mode) }
                            )
                        }
                    }
                }
                PlayerSheetTab.STATS -> {
                    // Stats & Decoding Tab
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.08f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF64B5F6))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(R.string.stats_resolution), color = Color.White.copy(alpha = 0.7f))
                                    Text(if (videoWidth > 0 && videoHeight > 0) "${videoWidth} × ${videoHeight}" else stringResource(R.string.stats_pending), fontWeight = FontWeight.Bold)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(R.string.stats_framerate), color = Color.White.copy(alpha = 0.7f))
                                    Text("${String.format(Locale.US, "%.1f", fps)} FPS", fontWeight = FontWeight.Bold)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(R.string.stats_dropped_frames), color = Color.White.copy(alpha = 0.7f))
                                    Text("$droppedFrames", fontWeight = FontWeight.Bold, color = if (droppedFrames > 0) Color(0xFFFFB74D) else Color.White)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(R.string.stats_decoder), color = Color.White.copy(alpha = 0.7f))
                                    val isHw = hwdec.contains("mediacodec")
                                    Text(if (isHw) stringResource(R.string.stats_hwdec_active) else stringResource(R.string.stats_hwdec_software), fontWeight = FontWeight.Bold, color = if (isHw) Color(0xFF81C784) else Color(0xFFFFB74D))
                                }
                                powerMetrics?.let { pm ->
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(stringResource(R.string.stats_power), color = Color.White.copy(alpha = 0.7f))
                                        Text("${String.format(Locale.US, "%.2f", pm.powerWatts)} W", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Decoder toggle action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(stringResource(R.string.stats_hwdec_title), fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = if (hwdec.contains("mediacodec")) stringResource(R.string.stats_hwdec_active) else stringResource(R.string.stats_hwdec_software),
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                            OutlinedButton(onClick = onToggleHwdec) {
                                Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(if (hwdec.contains("mediacodec")) R.string.stats_switch_to_sw else R.string.stats_switch_to_hw))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncCalibrationItem(
    label: String,
    currentSec: Double,
    onValueChange: (Double) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            val sign = if (currentSec > 0.0) "+" else ""
            Text(
                text = "$sign${String.format(Locale.US, "%.1fs", currentSec)}",
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64B5F6),
                    fontFeatureSettings = "tnum"
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { onValueChange(currentSec - 0.5) }) { Text("-0.5s") }
            OutlinedButton(onClick = { onValueChange(currentSec - 0.1) }) { Text("-0.1s") }
            Button(
                onClick = { onValueChange(0.0) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f))
            ) {
                Text("重置")
            }
            OutlinedButton(onClick = { onValueChange(currentSec + 0.1) }) { Text("+0.1s") }
            OutlinedButton(onClick = { onValueChange(currentSec + 0.5) }) { Text("+0.5s") }
        }
    }
}

@Composable
private fun TrackItem(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color(0xFF64B5F6) else Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        if (isSelected) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color(0xFF64B5F6))
        }
    }
}

@Composable
private fun EmptyTrackNotice(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = message, color = Color.White.copy(alpha = 0.5f))
    }
}
