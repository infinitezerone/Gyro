package dev.gyro.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.TrackInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerTrackSelectionSheet(
    tracks: List<TrackInfo>,
    currentResizeMode: ResizeMode,
    currentSpeed: Float,
    onDismiss: () -> Unit,
    onSelectTrack: (type: String, id: Int) -> Unit,
    onSelectResizeMode: (ResizeMode) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onAddExternalSubtitle: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Audio", "Subtitles", "Aspect", "Speed")

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
            Text(
                text = "Playback Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF1E1E1E),
                contentColor = Color.White
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                0 -> {
                    // Audio Tracks
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                        items(audioTracks) { track ->
                            TrackItem(
                                title = track.title ?: "Audio Track #${track.id}",
                                subtitle = track.lang?.uppercase() ?: "Undetermined",
                                isSelected = track.isSelected,
                                onClick = { onSelectTrack("audio", track.id) }
                            )
                        }
                        if (audioTracks.isEmpty()) {
                            item {
                                EmptyTrackNotice("No internal audio tracks detected")
                            }
                        }
                    }
                }
                1 -> {
                    // Subtitle Tracks
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                        item {
                            TrackItem(
                                title = "Disable Subtitles",
                                subtitle = "Off",
                                isSelected = subtitleTracks.none { it.isSelected },
                                onClick = { onSelectTrack("sub", 0) }
                            )
                        }
                        items(subtitleTracks) { track ->
                            TrackItem(
                                title = track.title ?: "Subtitle #${track.id}",
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
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF64B5F6))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Add External Subtitle File...", color = Color(0xFF64B5F6), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                2 -> {
                    // Resize Mode
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
                                    ResizeMode.FIT -> "Maintain aspect ratio with black bars (Letterbox)"
                                    ResizeMode.CROP -> "Crop and fill screen completely (Pan & Scan)"
                                    ResizeMode.STRETCH -> "Stretch to fill display ignoring ratio"
                                    ResizeMode.ASPECT_16_9 -> "Force standard widescreen 16:9"
                                    ResizeMode.ASPECT_21_9 -> "Force cinematic ultrawide 21:9"
                                },
                                isSelected = currentResizeMode == mode,
                                onClick = { onSelectResizeMode(mode) }
                            )
                        }
                    }
                }
                3 -> {
                    // Speed
                    val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
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
