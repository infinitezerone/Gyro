package dev.gyro.feature.player.component.sheet

import androidx.compose.runtime.Stable
import dev.gyro.core.model.ChapterInfo
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.GlslShaderState
import dev.gyro.core.model.HdrOutputMode
import dev.gyro.core.model.HwdecMode
import dev.gyro.core.model.MediaInfo
import dev.gyro.core.model.PowerMetrics
import dev.gyro.core.model.ToneMappingAlgorithm
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.model.VideoScale
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.TrackInfo
import dev.gyro.engine.model.TrackType
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
    /** Five band gains in dB, aligned with [dev.gyro.core.model.EQUALIZER_BANDS]. */
    val eqGainsDb: List<Double> = List(5) { 0.0 },
    val subScale: Double = 1.0,
    val subPos: Long = 100L,
    val hwdec: String = "",
    val hwdecMode: HwdecMode = HwdecMode.SOFTWARE,
    val fps: Double = 0.0,
    val droppedFrames: Long = 0L,
    val videoBitrate: Long = 0L,
    val audioBitrate: Long = 0L,
    val bitrateHistory: List<Long> = emptyList(),
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
    val softwareVolume: Double = 100.0,
    val nightMode: Boolean = false,
    val stereoDownmix: Boolean = false,
    val isHdr: Boolean = false,
    val hdrFormat: String = "",
    val hdrOutputMode: HdrOutputMode = HdrOutputMode.AUTO,
    val toneMappingAlgorithm: ToneMappingAlgorithm = ToneMappingAlgorithm.AUTO,
    val videoScale: VideoScale = VideoScale.SPLINE36,
    val interpolation: Boolean = false,
    val audioPassthrough: Boolean = false,
    val abLoopASeconds: Double = -1.0,
    val abLoopBSeconds: Double = -1.0,
    val sleepTimerSecondsRemaining: Long = 0L,
    val subAssOverride: Boolean = false
) {
    val audioTracks: List<TrackInfo> by lazy { tracks.filter { it.type == TrackType.AUDIO } }
    val subtitleTracks: List<TrackInfo> by lazy { tracks.filter { it.type == TrackType.SUBTITLE } }
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
    val onSetSoftwareVolume: (Double) -> Unit = {},
    val onSetNightMode: (Boolean) -> Unit = {},
    val onSetEqualizerGains: (List<Double>) -> Unit = {},
    val onSetStereoDownmix: (Boolean) -> Unit = {},
    val onSelectTrack: (type: TrackType, id: Int) -> Unit = { _, _ -> },
    val onSelectResizeMode: (ResizeMode) -> Unit = {},
    val onSelectSpeed: (Float) -> Unit = {},
    val onSetSubDelay: (Double) -> Unit = {},
    val onSetAudioDelay: (Double) -> Unit = {},
    val onSetSubScale: (Double) -> Unit = {},
    val onSetSubPos: (Long) -> Unit = {},
    val onToggleHwdec: () -> Unit = {},
    val onSetHwdec: (HwdecMode) -> Unit = {},
    val onToggleStatsOverlay: (Boolean) -> Unit = {},
    val onAddExternalSubtitle: () -> Unit = {},
    val onSetHdrOutputMode: (HdrOutputMode) -> Unit = {},
    val onSetToneMappingAlgorithm: (ToneMappingAlgorithm) -> Unit = {},
    val onSetVideoScale: (VideoScale) -> Unit = {},
    val onToggleInterpolation: (Boolean) -> Unit = {},
    val onToggleAudioPassthrough: (Boolean) -> Unit = {},
    val onCycleAbLoop: () -> Unit = {},
    val onClearAbLoop: () -> Unit = {},
    val onOpenFullSettings: () -> Unit = {},
    val onEnterPip: () -> Unit = {},
    val onSelectSecondarySubtitle: (Int) -> Unit = {},
    val onSetSubAssOverride: (Boolean) -> Unit = {},
    val onFrameStep: () -> Unit = {},
    val onFrameBackStep: () -> Unit = {},
    val onSetSleepTimer: (Int) -> Unit = {}
)
