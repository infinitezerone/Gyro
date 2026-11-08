package dev.gyro.feature.player.component.sheet

import androidx.compose.runtime.Stable
import dev.gyro.core.model.ChapterInfo
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.GlslShaderState
import dev.gyro.core.model.MediaInfo
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.TrackInfo
import dev.gyro.feature.player.PowerMetrics
import java.io.File

/**
 * Sheet 的只读显示状态快照 (PlayerSheetUiState)。
 * 由 PlayerScreen 每次进入 sheet 时构建，tabs 统一以此读取，替代原先 ~60 个散落参数。
 */
@Stable
data class PlayerSheetUiState(
    val playlist: List<VideoMediaItem> = emptyList(),
    val currentPlaylistIndex: Int = -1,
    val chapters: List<ChapterInfo> = emptyList(),
    val currentChapterIndex: Int = -1,
    val currentTimePosMs: Long = 0L,
    val tracks: List<TrackInfo> = emptyList(),
    val secondarySubId: Int = 0,
    val statsOverlayEnabled: Boolean = false,
    val currentResizeMode: ResizeMode = ResizeMode.FIT,
    val currentSpeed: Float = 1.0f,
    val subDelaySec: Double = 0.0,
    val audioDelaySec: Double = 0.0,
    val subScale: Double = 1.0,
    val subPos: Long = 100L,
    val hwdec: String = "",
    val fps: Double = 0.0,
    val droppedFrames: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val powerMetrics: PowerMetrics? = null,
    val mediaInfo: MediaInfo? = null,
    val shaderState: GlslShaderState = GlslShaderState(),
    val customShaders: List<File> = emptyList(),
    val deband: Boolean = false,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val nightMode: Boolean = false,
    val stereoDownmix: Boolean = false,
    val voRenderer: String = dev.gyro.core.model.VO_LIBMPV
) {
    val audioTracks: List<TrackInfo> by lazy { tracks.filter { it.type == "audio" } }
    val subtitleTracks: List<TrackInfo> by lazy { tracks.filter { it.type == "sub" } }
}

/**
 * Sheet 的全部回调 (PlayerSheetCallbacks)。
 * 与 [PlayerSheetUiState] 配对，由 PlayerScreen 构建，tabs 统一以此回传意图。
 */
@Stable
class PlayerSheetCallbacks(
    val onSelectPlaylistItem: (Int) -> Unit = {},
    val onSelectChapter: (Int) -> Unit = {},
    val onSelectShader: (GlslShader) -> Unit = {},
    val onClearShader: () -> Unit = {},
    val onSelectCustomShaderFile: (File) -> Unit = {},
    val onImportCustomShader: () -> Unit = {},
    val onSetDeband: (Boolean) -> Unit = {},
    val onSetBrightness: (Int) -> Unit = {},
    val onSetContrast: (Int) -> Unit = {},
    val onSetSaturation: (Int) -> Unit = {},
    val onResetColors: () -> Unit = {},
    val onSetNightMode: (Boolean) -> Unit = {},
    val onSetStereoDownmix: (Boolean) -> Unit = {},
    val onSelectTrack: (type: String, id: Int) -> Unit = { _, _ -> },
    val onSelectResizeMode: (ResizeMode) -> Unit = {},
    val onSelectSpeed: (Float) -> Unit = {},
    val onSetSubDelay: (Double) -> Unit = {},
    val onSetAudioDelay: (Double) -> Unit = {},
    val onSetSubScale: (Double) -> Unit = {},
    val onSetSubPos: (Long) -> Unit = {},
    val onToggleHwdec: () -> Unit = {},
    val onToggleStatsOverlay: (Boolean) -> Unit = {},
    val onToggleVoRenderer: (() -> Unit)? = null,
    val onAddExternalSubtitle: () -> Unit = {},
    val onOpenFullSettings: () -> Unit = {}
)
