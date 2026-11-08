package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.SheetTextColor

/**
 * 播放列表选集 (PlaylistTab)
 */
@Composable
internal fun PlaylistTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    if (state.playlist.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.playlist_empty), color = SheetTextSecondaryColor)
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
            itemsIndexed(state.playlist) { index, item ->
                val isCurrent = index == state.currentPlaylistIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { callbacks.onSelectPlaylistItem(index) }
                        .background(if (isCurrent) SheetAccentBlue.copy(alpha = 0.12f) else Color.Transparent)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isCurrent) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.playlist_current_playing),
                            tint = SheetAccentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            fontSize = 13.sp,
                            color = SheetTextSecondaryColor,
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
                            color = if (isCurrent) SheetAccentBlue else SheetTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.durationMs > 0L) {
                            Text(
                                text = item.formattedDuration,
                                fontSize = 11.sp,
                                color = SheetTextSecondaryColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 章节列表 (ChaptersTab)
 */
@Composable
internal fun ChaptersTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    if (state.chapters.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.chapter_empty),
                color = SheetTextSecondaryColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    } else {
        val activeIndex = if (state.currentChapterIndex in state.chapters.indices) {
            state.currentChapterIndex
        } else {
            state.chapters.indexOfLast { it.timeMs <= state.currentTimePosMs }.coerceAtLeast(0)
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        ) {
            itemsIndexed(state.chapters) { index, chapter ->
                val isCurrent = index == activeIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { callbacks.onSelectChapter(index) }
                        .background(
                            if (isCurrent) SheetAccentBlue.copy(alpha = 0.12f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isCurrent) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.chapter_current_badge),
                            tint = SheetAccentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = SheetTextSecondaryColor,
                            modifier = Modifier.width(24.dp),
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = chapter.title.ifBlank { "Chapter ${index + 1}" },
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) SheetAccentBlue else SheetTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = chapter.formattedTime,
                            fontSize = 12.sp,
                            color = if (isCurrent) SheetAccentBlue.copy(alpha = 0.8f) else SheetTextSecondaryColor,
                            style = TextStyle(fontFeatureSettings = "tnum")
                        )
                    }

                    if (isCurrent) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = SheetAccentBlue.copy(alpha = 0.15f),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.chapter_current_badge),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SheetAccentBlue,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
