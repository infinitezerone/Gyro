package dev.gyro.engine.model

import android.net.Uri
import dev.gyro.core.model.ChapterInfo
import dev.gyro.core.model.GlslShaderState
import dev.gyro.engine.session.PlaybackSessionState

/**
 * One consistent view of every slow-moving playback fact the UI renders.
 *
 * Owned by [dev.gyro.engine.state.PlaybackStore] as one container. Controllers write through the
 * store and expose only read-only projections of these fields, so there is no second source of
 * truth and no pair of fields read from different events.
 *
 * Deliberately excluded: [dev.gyro.engine.GyroPlayerEngine.timePosMs] and
 * [dev.gyro.engine.GyroPlayerEngine.bufferedPosMs]. They change at report frequency, and folding
 * them in would recompose the whole player surface several times a second for a value only the
 * scrub bar reads.
 */
data class PlaybackSnapshot(
    // Playback identity & lifecycle
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val isPlaying: Boolean = false,
    val currentUri: Uri? = null,
    val mediaTitle: String = "",
    val durationMs: Long = 0L,

    // Decode / timing diagnostics
    val hwdecCurrent: String = "",
    val speed: Float = 1.0f,
    val fps: Double = 0.0,
    val droppedFrames: Long = 0L,
    val vsyncRatio: Double = 0.0,

    // Video geometry
    val videoDimensions: VideoDimensions = VideoDimensions(0, 0),
    val videoAspect: Float = 0f,
    val videoFrameRate: Float = 0f,
    val geometryReady: Boolean = false,
    val firstFrameRendered: Boolean = false,
    val resizeMode: ResizeMode = ResizeMode.FIT,

    // Tracks, chapters & shaders
    val tracks: List<TrackInfo> = emptyList(),
    val chapters: List<ChapterInfo> = emptyList(),
    val currentChapter: Int = -1,
    val shaderState: GlslShaderState = GlslShaderState(),

    // 次字幕轨 id（双语同屏；0 = 关闭）
    val secondarySubId: Int = 0,

    // A-B loop（秒；-1.0 = 未设置）
    val abLoopASeconds: Double = -1.0,
    val abLoopBSeconds: Double = -1.0,

    // Picture & audio effects
    val deband: Boolean = false,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val nightMode: Boolean = false,
    val stereoDownmix: Boolean = false,

    // A/V sync calibration
    val subtitleDelaySec: Double = 0.0,
    val audioDelaySec: Double = 0.0,
    val subtitleScale: Double = 1.0,
    val subtitlePositionPercent: Long = 100L,

    // Fields owned by controllers that were not part of the original UI snapshot
    val softwareVolume: Double = 100.0,
    val displayRefreshRate: Float = 0f,
    val sessionState: PlaybackSessionState = PlaybackSessionState.Idle,

    // Our own orchestration state, not a mirror of mpv
    val session: SessionPhase = SessionPhase.Idle,
    val error: Throwable? = null
)
