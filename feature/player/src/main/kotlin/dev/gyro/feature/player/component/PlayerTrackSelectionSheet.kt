package dev.gyro.feature.player.component

import dev.gyro.feature.player.PowerMetrics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.shape.CircleShape
import dev.gyro.core.designsystem.component.GyroPillButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import dev.gyro.core.model.ChapterInfo
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.GlslShaderState
import dev.gyro.core.model.MediaInfo
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.TrackInfo
import dev.gyro.core.designsystem.R
import java.io.File
import java.util.Locale

enum class PlayerSheetTab(@StringRes val titleRes: Int) {
    SETTINGS_HUB(R.string.settings_title),
    PLAYLIST(R.string.sheet_tab_playlist),
    CHAPTERS(R.string.sheet_tab_chapters),
    SUBTITLES(R.string.sheet_tab_subtitles),
    AUDIO(R.string.sheet_tab_audio),
    SHADERS(R.string.sheet_tab_shaders),
    AUDIOVISUAL(R.string.sheet_tab_audiovisual),
    SYNC(R.string.sheet_tab_sync),
    SPEED(R.string.sheet_tab_speed),
    ASPECT(R.string.sheet_tab_aspect),
    STATS(R.string.sheet_tab_stats),
    INFO(R.string.sheet_tab_info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerTrackSelectionSheet(
    initialTab: PlayerSheetTab = PlayerSheetTab.SUBTITLES,
    playlist: List<VideoMediaItem> = emptyList(),
    currentPlaylistIndex: Int = -1,
    chapters: List<ChapterInfo> = emptyList(),
    currentChapterIndex: Int = -1,
    currentTimePosMs: Long = 0L,
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
    mediaInfo: MediaInfo? = null,
    shaderState: GlslShaderState = GlslShaderState(),
    customShaders: List<File> = emptyList(),
    deband: Boolean = false,
    brightness: Int = 0,
    contrast: Int = 0,
    saturation: Int = 0,
    nightMode: Boolean = false,
    stereoDownmix: Boolean = false,
    onDismiss: () -> Unit,
    onSelectPlaylistItem: (Int) -> Unit = {},
    onSelectChapter: (Int) -> Unit = {},
    onSelectShader: (GlslShader) -> Unit = {},
    onClearShader: () -> Unit = {},
    onSelectCustomShaderFile: (File) -> Unit = {},
    onImportCustomShader: () -> Unit = {},
    onSetDeband: (Boolean) -> Unit = {},
    onSetBrightness: (Int) -> Unit = {},
    onSetContrast: (Int) -> Unit = {},
    onSetSaturation: (Int) -> Unit = {},
    onResetColors: () -> Unit = {},
    onSetNightMode: (Boolean) -> Unit = {},
    onSetStereoDownmix: (Boolean) -> Unit = {},
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
        contentColor = Color.White
    ) {
        PlayerTrackSelectionSheetContent(
            initialTab = initialTab,
            playlist = playlist,
            currentPlaylistIndex = currentPlaylistIndex,
            chapters = chapters,
            currentChapterIndex = currentChapterIndex,
            currentTimePosMs = currentTimePosMs,
            tracks = tracks,
            currentResizeMode = currentResizeMode,
            currentSpeed = currentSpeed,
            subDelaySec = subDelaySec,
            audioDelaySec = audioDelaySec,
            subScale = subScale,
            subPos = subPos,
            hwdec = hwdec,
            fps = fps,
            droppedFrames = droppedFrames,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            powerMetrics = powerMetrics,
            mediaInfo = mediaInfo,
            shaderState = shaderState,
            customShaders = customShaders,
            deband = deband,
            brightness = brightness,
            contrast = contrast,
            saturation = saturation,
            nightMode = nightMode,
            stereoDownmix = stereoDownmix,
            onSelectPlaylistItem = onSelectPlaylistItem,
            onSelectChapter = onSelectChapter,
            onSelectShader = onSelectShader,
            onClearShader = onClearShader,
            onSelectCustomShaderFile = onSelectCustomShaderFile,
            onImportCustomShader = onImportCustomShader,
            onSetDeband = onSetDeband,
            onSetBrightness = onSetBrightness,
            onSetContrast = onSetContrast,
            onSetSaturation = onSetSaturation,
            onResetColors = onResetColors,
            onSetNightMode = onSetNightMode,
            onSetStereoDownmix = onSetStereoDownmix,
            onSelectTrack = onSelectTrack,
            onSelectResizeMode = onSelectResizeMode,
            onSelectSpeed = onSelectSpeed,
            onSetSubDelay = onSetSubDelay,
            onSetAudioDelay = onSetAudioDelay,
            onSetSubScale = onSetSubScale,
            onSetSubPos = onSetSubPos,
            onToggleHwdec = onToggleHwdec,
            onAddExternalSubtitle = onAddExternalSubtitle,
            onOpenFullSettings = onOpenFullSettings
        )
    }
}


/**
 * 现代轻量级设置卡片项 (SettingsHubItem)
 */
@Composable
private fun SettingsHubItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color = Color(0xFF64B5F6),
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.05f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.60f)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
fun PlayerTrackSelectionSheetContent(
    initialTab: PlayerSheetTab = PlayerSheetTab.SUBTITLES,
    playlist: List<VideoMediaItem> = emptyList(),
    currentPlaylistIndex: Int = -1,
    chapters: List<ChapterInfo> = emptyList(),
    currentChapterIndex: Int = -1,
    currentTimePosMs: Long = 0L,
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
    mediaInfo: MediaInfo? = null,
    shaderState: GlslShaderState = GlslShaderState(),
    customShaders: List<File> = emptyList(),
    deband: Boolean = false,
    brightness: Int = 0,
    contrast: Int = 0,
    saturation: Int = 0,
    nightMode: Boolean = false,
    stereoDownmix: Boolean = false,
    onSelectPlaylistItem: (Int) -> Unit = {},
    onSelectChapter: (Int) -> Unit = {},
    onSelectShader: (GlslShader) -> Unit = {},
    onClearShader: () -> Unit = {},
    onSelectCustomShaderFile: (File) -> Unit = {},
    onImportCustomShader: () -> Unit = {},
    onSetDeband: (Boolean) -> Unit = {},
    onSetBrightness: (Int) -> Unit = {},
    onSetContrast: (Int) -> Unit = {},
    onSetSaturation: (Int) -> Unit = {},
    onResetColors: () -> Unit = {},
    onSetNightMode: (Boolean) -> Unit = {},
    onSetStereoDownmix: (Boolean) -> Unit = {},
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
    var selectedTab by remember { mutableStateOf(initialTab) }

    val audioTracks = remember(tracks) { tracks.filter { it.type == "audio" } }
    val subtitleTracks = remember(tracks) { tracks.filter { it.type == "sub" } }

    val isPlaylistScene = initialTab == PlayerSheetTab.PLAYLIST || initialTab == PlayerSheetTab.CHAPTERS

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp)
    ) {
            if (isPlaylistScene) {
                // A. 选集/章节场景：极简双胶囊导航，剔除其余无关 9 个 Tab
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GyroPillButton(
                        text = "${stringResource(R.string.sheet_tab_playlist)} (${playlist.size})",
                        onClick = { selectedTab = PlayerSheetTab.PLAYLIST },
                        containerColor = if (selectedTab == PlayerSheetTab.PLAYLIST) Color(0xFF64B5F6).copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                        contentColor = if (selectedTab == PlayerSheetTab.PLAYLIST) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.85f)
                    )
                    if (chapters.isNotEmpty()) {
                        GyroPillButton(
                            text = "${stringResource(R.string.sheet_tab_chapters)} (${chapters.size})",
                            onClick = { selectedTab = PlayerSheetTab.CHAPTERS },
                            containerColor = if (selectedTab == PlayerSheetTab.CHAPTERS) Color(0xFF64B5F6).copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                            contentColor = if (selectedTab == PlayerSheetTab.CHAPTERS) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            } else if (selectedTab == PlayerSheetTab.SETTINGS_HUB) {
                // B. 设置总览大厅场景
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
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
            } else {
                // C. 各子设置场景：清晰返回导航头
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { selectedTab = PlayerSheetTab.SETTINGS_HUB },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(selectedTab.titleRes),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onOpenFullSettings, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (selectedTab) {
                PlayerSheetTab.SETTINGS_HUB -> {
                    // 全局设置分类大厅卡片列表
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 1. 画面比例与解码
                        val hwDesc = if (hwdec.contains("mediacodec")) "硬件解码 (MediaCodec)" else "软件解码"
                        SettingsHubItem(
                            title = stringResource(R.string.sheet_tab_aspect),
                            subtitle = "${currentResizeMode.label} · $hwDesc",
                            icon = Icons.Default.AspectRatio,
                            iconTint = Color(0xFF4FC3F7),
                            onClick = { selectedTab = PlayerSheetTab.ASPECT }
                        )

                        // 2. 音轨与音效
                        val currentAudioName = audioTracks.firstOrNull { it.isSelected }?.title ?: stringResource(R.string.track_none_audio)
                        val nightDesc = if (nightMode) "夜间音效已开" else "标准立体声"
                        SettingsHubItem(
                            title = stringResource(R.string.sheet_tab_audio),
                            subtitle = "$currentAudioName · $nightDesc",
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            iconTint = Color(0xFF81C784),
                            onClick = { selectedTab = PlayerSheetTab.AUDIO }
                        )

                        // 3. 字幕管理与微调
                        val currentSubName = subtitleTracks.firstOrNull { it.isSelected }?.title ?: "已关闭"
                        val delayDesc = if (Math.abs(subDelaySec) > 0.01) "延迟 ${String.format(Locale.US, "%.1f", subDelaySec)}s" else "无延迟"
                        SettingsHubItem(
                            title = stringResource(R.string.sheet_tab_subtitles),
                            subtitle = "$currentSubName · $delayDesc",
                            icon = Icons.Default.ClosedCaption,
                            iconTint = Color(0xFFFFB74D),
                            onClick = { selectedTab = PlayerSheetTab.SUBTITLES }
                        )

                        // 4. 播放速度
                        SettingsHubItem(
                            title = stringResource(R.string.sheet_tab_speed),
                            subtitle = "当前倍速: ${currentSpeed}x",
                            icon = Icons.Default.Speed,
                            iconTint = Color(0xFFBA68C8),
                            onClick = { selectedTab = PlayerSheetTab.SPEED }
                        )

                        // 5. 着色器与 Anime4K
                        val shaderLabel = if (shaderState.activeShader != GlslShader.OFF) (shaderState.displayName ?: shaderState.activeShader.name) else "原生渲染 (未开启)"
                        SettingsHubItem(
                            title = stringResource(R.string.sheet_tab_shaders),
                            subtitle = shaderLabel,
                            icon = Icons.Default.AutoAwesome,
                            iconTint = Color(0xFFFF8A65),
                            onClick = { selectedTab = PlayerSheetTab.SHADERS }
                        )

                        // 6. 视听画质增强
                        val debandDesc = if (deband) "去色带已开 · 色彩校准" else "色彩与明暗调节"
                        SettingsHubItem(
                            title = stringResource(R.string.sheet_tab_audiovisual),
                            subtitle = debandDesc,
                            icon = Icons.Default.Tune,
                            iconTint = Color(0xFF4DB6AC),
                            onClick = { selectedTab = PlayerSheetTab.AUDIOVISUAL }
                        )

                        // 7. 媒体参数与丢帧统计
                        val resDesc = if (videoWidth > 0 && videoHeight > 0) "${videoWidth}×${videoHeight} · ${String.format(Locale.US, "%.1f", fps)} FPS" else "实时性能与媒体详情"
                        SettingsHubItem(
                            title = stringResource(R.string.sheet_tab_stats),
                            subtitle = resDesc,
                            icon = Icons.Default.Info,
                            iconTint = Color(0xFF90A4AE),
                            onClick = { selectedTab = PlayerSheetTab.STATS }
                        )
                    }
                }
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
                PlayerSheetTab.CHAPTERS -> {
                    if (chapters.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.chapter_empty),
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        val activeIndex = if (currentChapterIndex in chapters.indices) {
                            currentChapterIndex
                        } else {
                            chapters.indexOfLast { it.timeMs <= currentTimePosMs }.coerceAtLeast(0)
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                        ) {
                            itemsIndexed(chapters) { index, chapter ->
                                val isCurrent = index == activeIndex
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onSelectChapter(index) }
                                        .background(
                                            if (isCurrent) Color(0xFF64B5F6).copy(alpha = 0.12f)
                                            else Color.Transparent
                                        )
                                        .padding(horizontal = 24.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isCurrent) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = stringResource(R.string.chapter_current_badge),
                                            tint = Color(0xFF64B5F6),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Text(
                                            text = "${index + 1}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White.copy(alpha = 0.5f),
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
                                            color = if (isCurrent) Color(0xFF64B5F6) else Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = chapter.formattedTime,
                                            fontSize = 12.sp,
                                            color = if (isCurrent) Color(0xFF64B5F6).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.5f),
                                            style = TextStyle(fontFeatureSettings = "tnum")
                                        )
                                    }

                                    if (isCurrent) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFF64B5F6).copy(alpha = 0.15f),
                                            modifier = Modifier.padding(start = 8.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.chapter_current_badge),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF64B5F6),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                PlayerSheetTab.SUBTITLES -> {
                    // 现代字幕配置面板 (全展开平滑滚动，无截断)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. 字幕轨道选择
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.sheet_tab_subtitles),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF64B5F6)
                                )
                                Text(
                                    text = "${subtitleTracks.size} 个字幕",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            }

                            TrackItem(
                                title = stringResource(R.string.track_none_subtitle),
                                subtitle = "Off",
                                isSelected = subtitleTracks.none { it.isSelected },
                                accentColor = Color(0xFF64B5F6),
                                icon = Icons.Default.ClosedCaption,
                                onClick = { onSelectTrack("sub", 0) }
                            )

                            subtitleTracks.forEach { track ->
                                TrackItem(
                                    title = track.title ?: "内置字幕 #${track.id}",
                                    subtitle = track.lang?.uppercase() ?: "ASS/SRT",
                                    isSelected = track.isSelected,
                                    accentColor = Color(0xFF64B5F6),
                                    icon = Icons.Default.ClosedCaption,
                                    onClick = { onSelectTrack("sub", track.id) }
                                )
                            }

                            Surface(
                                onClick = { onAddExternalSubtitle() },
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.05f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF64B5F6).copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            tint = Color(0xFF64B5F6),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(R.string.track_add_external_sub),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF64B5F6)
                                    )
                                }
                            }
                        }

                        // 2. 字幕同步校准
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "字幕同步",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64B5F6),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )

                            SyncCalibrationItem(
                                label = stringResource(R.string.sync_sub_delay),
                                currentSec = subDelaySec,
                                accentColor = Color(0xFF64B5F6),
                                onValueChange = onSetSubDelay
                            )
                        }

                        // 3. 字幕样式微调 (尺寸与位置)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = 0.05f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.sub_scale_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(0.8, 1.0, 1.2, 1.5).forEach { scale ->
                                        FilterChip(
                                            selected = Math.abs(subScale - scale) < 0.05,
                                            onClick = { onSetSubScale(scale) },
                                            label = { Text("${scale}x") },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Text(
                                    text = stringResource(R.string.sub_pos_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        100L to stringResource(R.string.sub_pos_bottom),
                                        95L to stringResource(R.string.sub_pos_raised),
                                        90L to stringResource(R.string.sub_pos_middle)
                                    ).forEach { (pos, label) ->
                                        FilterChip(
                                            selected = subPos == pos,
                                            onClick = { onSetSubPos(pos) },
                                            label = { Text(label) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                PlayerSheetTab.AUDIO -> {
                    // 现代顶级音轨与音频处理大厅 (全展开平滑滚动，无截断)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. 音轨选择列表 (卡片化微磨砂)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.sheet_tab_audio),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF81C784)
                                )
                                Text(
                                    text = "${audioTracks.size} 个音轨",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            }

                            if (audioTracks.isEmpty()) {
                                EmptyTrackNotice(stringResource(R.string.track_none_audio))
                            } else {
                                audioTracks.forEach { track ->
                                    TrackItem(
                                        title = track.title ?: "音频轨道 #${track.id}",
                                        subtitle = track.lang?.uppercase() ?: "Standard Audio",
                                        isSelected = track.isSelected,
                                        accentColor = Color(0xFF81C784),
                                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                                        onClick = { onSelectTrack("audio", track.id) }
                                    )
                                }
                            }
                        }

                        // 2. 音效增强 (夜间模式 / 声道混缩)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.effects_audio_group),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF81C784),
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.05f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                            ) {
                                Column {
                                    FilterSwitchRow(
                                        title = stringResource(R.string.effects_night_dialogue),
                                        description = stringResource(R.string.effects_night_dialogue_desc),
                                        checked = nightMode,
                                        onCheckedChange = onSetNightMode
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = Color.White.copy(alpha = 0.08f)
                                    )
                                    FilterSwitchRow(
                                        title = stringResource(R.string.effects_stereo_downmix),
                                        description = stringResource(R.string.effects_stereo_downmix_desc),
                                        checked = stereoDownmix,
                                        onCheckedChange = onSetStereoDownmix
                                    )
                                }
                            }
                        }

                        // 3. 声音延迟校准卡片
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "声音同步",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF81C784),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )

                            SyncCalibrationItem(
                                label = stringResource(R.string.sync_audio_delay),
                                currentSec = audioDelaySec,
                                accentColor = Color(0xFF81C784),
                                onValueChange = onSetAudioDelay
                            )
                        }
                    }
                }
                PlayerSheetTab.SHADERS -> {
                    ShadersTabContent(
                        shaderState = shaderState,
                        customShaders = customShaders,
                        onSelectShader = onSelectShader,
                        onClearShader = onClearShader,
                        onSelectCustomShaderFile = onSelectCustomShaderFile,
                        onImportCustomShader = onImportCustomShader
                    )
                }
                PlayerSheetTab.AUDIOVISUAL -> {
                    AudiovisualTabContent(
                        deband = deband,
                        brightness = brightness,
                        contrast = contrast,
                        saturation = saturation,
                        nightMode = nightMode,
                        stereoDownmix = stereoDownmix,
                        onSetDeband = onSetDeband,
                        onSetBrightness = onSetBrightness,
                        onSetContrast = onSetContrast,
                        onSetSaturation = onSetSaturation,
                        onResetColors = onResetColors,
                        onSetNightMode = onSetNightMode,
                        onSetStereoDownmix = onSetStereoDownmix
                    )
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
                                Text(stringResource(R.string.stats_hwdec_title), fontWeight = FontWeight.SemiBold, color = Color.White)
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
                PlayerSheetTab.INFO -> {
                    val resolvedInfo = mediaInfo ?: MediaInfo(
                        videoResolution = if (videoWidth > 0 && videoHeight > 0) "${videoWidth} × ${videoHeight}" else "",
                        videoFps = fps,
                        hwdecCurrent = hwdec,
                        droppedFrames = droppedFrames
                    )
                    MediaInfoTabContent(mediaInfo = resolvedInfo)
                }
            }
        }
    }

@Composable
private fun MediaInfoTabContent(mediaInfo: MediaInfo) {
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
            if (mediaInfo.title.isNotBlank()) {
                MediaInfoRow(label = stringResource(R.string.info_title), value = mediaInfo.title)
            }
            if (mediaInfo.containerFormat.isNotBlank()) {
                MediaInfoRow(label = stringResource(R.string.info_container), value = mediaInfo.containerFormat.uppercase())
            }
            if (mediaInfo.fileSize > 0) {
                MediaInfoRow(label = stringResource(R.string.info_file_size), value = mediaInfo.formattedFileSize)
            }
            if (mediaInfo.durationMs > 0) {
                val sec = mediaInfo.durationMs / 1000
                val min = sec / 60
                val remSec = sec % 60
                val durStr = String.format(Locale.US, "%02d:%02d", min, remSec)
                MediaInfoRow(label = stringResource(R.string.info_duration), value = durStr)
            }
        }

        // 2. 视频流
        MediaInfoGroup(title = stringResource(R.string.info_group_video)) {
            val codec = mediaInfo.videoCodec.ifBlank { "未知" }
            MediaInfoRow(label = stringResource(R.string.info_video_codec), value = codec.uppercase())
            if (mediaInfo.videoResolution.isNotBlank()) {
                MediaInfoRow(label = stringResource(R.string.info_video_resolution), value = mediaInfo.videoResolution)
            }
            if (mediaInfo.videoFps > 0) {
                MediaInfoRow(label = stringResource(R.string.info_video_framerate), value = "${String.format(Locale.US, "%.2f", mediaInfo.videoFps)} FPS")
            }
            if (mediaInfo.videoBitrate > 0) {
                MediaInfoRow(label = stringResource(R.string.info_video_bitrate), value = mediaInfo.formattedVideoBitrate)
            }
            val isHw = mediaInfo.hwdecCurrent.contains("mediacodec")
            MediaInfoRow(
                label = stringResource(R.string.stats_decoder),
                value = if (isHw) stringResource(R.string.stats_hwdec_active) else stringResource(R.string.stats_hwdec_software)
            )
        }

        // 3. 音频流
        MediaInfoGroup(title = stringResource(R.string.info_group_audio)) {
            val aCodec = mediaInfo.audioCodec.ifBlank { "未知" }
            MediaInfoRow(label = stringResource(R.string.info_audio_codec), value = aCodec.uppercase())
            if (mediaInfo.audioChannels.isNotBlank()) {
                MediaInfoRow(label = stringResource(R.string.info_audio_channels), value = mediaInfo.audioChannels)
            }
            if (mediaInfo.audioSampleRate > 0) {
                MediaInfoRow(label = stringResource(R.string.info_audio_samplerate), value = mediaInfo.formattedSampleRate)
            }
            if (mediaInfo.audioBitrate > 0) {
                MediaInfoRow(label = stringResource(R.string.info_audio_bitrate), value = mediaInfo.formattedAudioBitrate)
            }
        }
    }
}

@Composable
private fun MediaInfoGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64B5F6)
            )
            content()
        }
    }
}

@Composable
private fun MediaInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                fontFeatureSettings = "tnum"
            )
        )
    }
}

@Composable
private fun SyncCalibrationItem(
    label: String,
    currentSec: Double,
    accentColor: Color = Color(0xFF64B5F6),
    onValueChange: (Double) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.05f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Row 1: 标题与实时同步状态胶囊
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                val formatted = String.format(Locale.US, "%.1fs", currentSec)
                val statusText = when {
                    currentSec > 0.05 -> "延迟 +$formatted"
                    currentSec < -0.05 -> "提前 $formatted"
                    else -> "已同步 0.0s"
                }
                val statusColor = when {
                    Math.abs(currentSec) > 0.05 -> accentColor
                    else -> Color(0xFF81C784)
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Row 2: 细调步进按钮组 [-0.1s] [ 重置 ] [+0.1s]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = { onValueChange(currentSec - 0.1) },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("-0.1s", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                FilledTonalButton(
                    onClick = { onValueChange(0.0) },
                    modifier = Modifier.weight(1.2f).height(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    enabled = Math.abs(currentSec) > 0.01,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = accentColor.copy(alpha = 0.2f),
                        contentColor = accentColor,
                        disabledContainerColor = Color.White.copy(alpha = 0.04f),
                        disabledContentColor = Color.White.copy(alpha = 0.3f)
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("重置", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                FilledTonalButton(
                    onClick = { onValueChange(currentSec + 0.1) },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("+0.1s", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 3: 粗调快速芯片组 [-1.0s] [-0.5s] [+0.5s] [+1.0s]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(-1.0, -0.5, 0.5, 1.0).forEach { delta ->
                    val sign = if (delta > 0) "+" else ""
                    FilledTonalButton(
                        onClick = { onValueChange(currentSec + delta) },
                        modifier = Modifier.weight(1f).height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.05f),
                            contentColor = Color.White.copy(alpha = 0.7f)
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("$sign${delta}s", fontSize = 11.sp, fontWeight = FontWeight.Normal)
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
    accentColor: Color = Color(0xFF64B5F6),
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) accentColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
        border = if (isSelected) BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) accentColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) accentColor else Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) accentColor else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                }
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
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

@Composable
private fun ShadersTabContent(
    shaderState: GlslShaderState,
    customShaders: List<File>,
    onSelectShader: (GlslShader) -> Unit,
    onClearShader: () -> Unit,
    onSelectCustomShaderFile: (File) -> Unit,
    onImportCustomShader: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        // 1. Off
        item {
            TrackItem(
                title = stringResource(R.string.shader_off),
                subtitle = stringResource(R.string.common_off),
                isSelected = shaderState.activeShader == GlslShader.OFF,
                onClick = onClearShader
            )
        }

        // 2. Anime4K Lite
        item {
            TrackItem(
                title = stringResource(R.string.shader_anime4k),
                subtitle = stringResource(R.string.shader_desc_anime4k),
                isSelected = shaderState.activeShader == GlslShader.ANIME4K,
                onClick = { onSelectShader(GlslShader.ANIME4K) }
            )
        }

        // Divider
        item {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White.copy(alpha = 0.1f)
            )
        }

        // 4. Cached Custom Shaders
        if (customShaders.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.shader_custom),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF64B5F6),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }
            items(customShaders) { file ->
                val isSelected = shaderState.activeShader == GlslShader.CUSTOM &&
                        shaderState.currentPath == file.absolutePath
                TrackItem(
                    title = file.nameWithoutExtension,
                    subtitle = file.name,
                    isSelected = isSelected,
                    onClick = { onSelectCustomShaderFile(file) }
                )
            }
        }

        // 5. Import Action Item via SAF
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onImportCustomShader() }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Color(0xFF64B5F6),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.shader_import_saf),
                        color = Color(0xFF64B5F6),
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.shader_desc_custom),
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun AudiovisualTabContent(
    deband: Boolean,
    brightness: Int,
    contrast: Int,
    saturation: Int,
    nightMode: Boolean,
    stereoDownmix: Boolean,
    onSetDeband: (Boolean) -> Unit,
    onSetBrightness: (Int) -> Unit,
    onSetContrast: (Int) -> Unit,
    onSetSaturation: (Int) -> Unit,
    onResetColors: () -> Unit,
    onSetNightMode: (Boolean) -> Unit,
    onSetStereoDownmix: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp)
    ) {
        // Group 1: 画面色彩调节
        Text(
            text = stringResource(R.string.effects_color_group),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF64B5F6),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )

        FilterSwitchRow(
            title = stringResource(R.string.effects_deband),
            description = stringResource(R.string.effects_deband_desc),
            checked = deband,
            onCheckedChange = onSetDeband
        )

        Spacer(modifier = Modifier.height(4.dp))

        ColorSliderRow(
            label = stringResource(R.string.effects_color_brightness),
            value = brightness,
            onValueChange = onSetBrightness
        )

        ColorSliderRow(
            label = stringResource(R.string.effects_color_contrast),
            value = contrast,
            onValueChange = onSetContrast
        )

        ColorSliderRow(
            label = stringResource(R.string.effects_color_saturation),
            value = saturation,
            onValueChange = onSetSaturation
        )

        val hasColorModifications = brightness != 0 || contrast != 0 || saturation != 0
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = onResetColors,
                enabled = hasColorModifications,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64B5F6), disabledContentColor = Color.White.copy(alpha = 0.3f))
            ) {
                Text(text = stringResource(R.string.effects_color_reset))
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = Color.White.copy(alpha = 0.1f)
        )

        // Group 2: 音频增强滤镜
        Text(
            text = stringResource(R.string.effects_audio_group),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF64B5F6),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )

        FilterSwitchRow(
            title = stringResource(R.string.effects_night_dialogue),
            description = stringResource(R.string.effects_night_dialogue_desc),
            checked = nightMode,
            onCheckedChange = onSetNightMode
        )

        FilterSwitchRow(
            title = stringResource(R.string.effects_stereo_downmix),
            description = stringResource(R.string.effects_stereo_downmix_desc),
            checked = stereoDownmix,
            onCheckedChange = onSetStereoDownmix
        )
    }
}

@Composable
private fun FilterSwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            if (!description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF64B5F6),
                checkedTrackColor = Color(0xFF64B5F6).copy(alpha = 0.35f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
            )
        )
    }
}

@Composable
private fun ColorSliderRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Normal
            )
            Text(
                text = if (value > 0) "+$value" else "$value",
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (value != 0) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.5f),
                    fontFeatureSettings = "tnum"
                )
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(Math.round(it)) },
            valueRange = -100f..100f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF64B5F6),
                activeTrackColor = Color(0xFF64B5F6),
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            )
        )
    }
}

