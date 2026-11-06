package dev.gyro.engine.model

import android.net.Uri
import dev.gyro.core.model.ChapterInfo
import dev.gyro.core.model.GlslShaderState

/**
 * One consistent view of every slow-moving playback fact the UI renders.
 *
 * Built by [dev.gyro.engine.snapshot.PlaybackSnapshotAggregator] from one `combine` chain, so each
 * emission is a coherent read of every source at a single instant: no field can be a frame behind
 * another, which a set of independent `StateFlow`s cannot promise once the UI collects them at
 * different times. It may still show a mid-handler intermediate state (two writes in one event are
 * two emissions), but every emission is at least internally truthful.
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
    val subtitlePositionPercent: Long = 100L
)
