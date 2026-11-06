package dev.gyro.engine.snapshot

import android.net.Uri
import dev.gyro.engine.effects.AudioEffectsController
import dev.gyro.engine.effects.VideoEffectsController
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.session.VideoGeometryCoordinator
import dev.gyro.engine.shader.ShaderController
import dev.gyro.engine.tracks.TrackAndChapterManager
import dev.gyro.engine.tuning.SyncTuningController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The playback flows the engine facade owns itself, bundled so the aggregator can be constructed
 * without a [dev.gyro.engine.GyroPlayerEngine] instance (and therefore tested on its own).
 */
data class CorePlaybackInputs(
    val playbackState: Flow<PlaybackState>,
    val isPlaying: Flow<Boolean>,
    val currentUri: Flow<Uri?>,
    val mediaTitle: Flow<String>,
    val durationMs: Flow<Long>,
    val hwdecCurrent: Flow<String>,
    val speed: Flow<Float>,
    val fps: Flow<Double>,
    val droppedFrames: Flow<Long>
)

/**
 * Folds every controller's [kotlinx.coroutines.flow.StateFlow] into one [PlaybackSnapshot] per
 * change.
 *
 * `combine` reads all sources when it emits, so each snapshot is a coherent read of the whole
 * engine at that instant; consumers subscribe once instead of once per property, and Compose sees a
 * single state object to coalesce.
 *
 * Six groups instead of one 29-argument `combine` because the typed overloads stop at five; the
 * group partials exist only to be merged.
 */
class PlaybackSnapshotAggregator(
    private val core: CorePlaybackInputs,
    private val geometry: VideoGeometryCoordinator,
    private val audio: AudioEffectsController,
    private val video: VideoEffectsController,
    private val tracks: TrackAndChapterManager,
    private val tuning: SyncTuningController,
    private val shaders: ShaderController
) {

    fun flow(): Flow<PlaybackSnapshot> {
        val coreGroup = combine(
            core.playbackState,
            core.isPlaying,
            core.currentUri,
            core.mediaTitle,
            core.durationMs
        ) { state, playing, uri, title, duration ->
            PlaybackSnapshot(
                playbackState = state,
                isPlaying = playing,
                currentUri = uri,
                mediaTitle = title,
                durationMs = duration
            )
        }

        val pipelineGroup = combine(
            core.hwdecCurrent,
            core.speed,
            core.fps,
            core.droppedFrames,
            geometry.resizeMode
        ) { hwdec, speed, fps, dropped, resize ->
            PlaybackSnapshot(
                hwdecCurrent = hwdec,
                speed = speed,
                fps = fps,
                droppedFrames = dropped,
                resizeMode = resize
            )
        }

        val geometryGroup = combine(
            geometry.videoDimensions,
            geometry.videoAspect,
            geometry.videoFrameRate,
            geometry.geometryReady,
            geometry.firstFrameRendered
        ) { dims, aspect, frameRate, ready, firstFrame ->
            PlaybackSnapshot(
                videoDimensions = dims,
                videoAspect = aspect,
                videoFrameRate = frameRate,
                geometryReady = ready,
                firstFrameRendered = firstFrame
            )
        }

        val trackGroup = combine(
            tracks.tracks,
            tracks.chapters,
            tracks.currentChapter,
            shaders.shaderState
        ) { trackList, chapterList, currentChapter, shader ->
            PlaybackSnapshot(
                tracks = trackList,
                chapters = chapterList,
                currentChapter = currentChapter,
                shaderState = shader
            )
        }

        val pictureGroup = combine(
            video.deband,
            video.brightness,
            video.contrast,
            video.saturation,
            audio.nightMode
        ) { deband, brightness, contrast, saturation, nightMode ->
            PlaybackSnapshot(
                deband = deband,
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                nightMode = nightMode
            )
        }

        val calibrationGroup = combine(
            audio.stereoDownmix,
            tuning.subtitleDelaySec,
            tuning.audioDelaySec,
            tuning.subtitleScale,
            tuning.subtitlePositionPercent
        ) { downmix, subDelay, audioDelay, subScale, subPos ->
            PlaybackSnapshot(
                stereoDownmix = downmix,
                subtitleDelaySec = subDelay,
                audioDelaySec = audioDelay,
                subtitleScale = subScale,
                subtitlePositionPercent = subPos
            )
        }

        val merged = combine(
            coreGroup,
            pipelineGroup,
            geometryGroup,
            trackGroup,
            pictureGroup
        ) { corePart, pipelinePart, geometryPart, trackPart, picturePart ->
            corePart.copy(
                hwdecCurrent = pipelinePart.hwdecCurrent,
                speed = pipelinePart.speed,
                fps = pipelinePart.fps,
                droppedFrames = pipelinePart.droppedFrames,
                resizeMode = pipelinePart.resizeMode,
                videoDimensions = geometryPart.videoDimensions,
                videoAspect = geometryPart.videoAspect,
                videoFrameRate = geometryPart.videoFrameRate,
                geometryReady = geometryPart.geometryReady,
                firstFrameRendered = geometryPart.firstFrameRendered,
                tracks = trackPart.tracks,
                chapters = trackPart.chapters,
                currentChapter = trackPart.currentChapter,
                shaderState = trackPart.shaderState,
                deband = picturePart.deband,
                brightness = picturePart.brightness,
                contrast = picturePart.contrast,
                saturation = picturePart.saturation,
                nightMode = picturePart.nightMode
            )
        }

        return combine(merged, calibrationGroup) { part, calibration ->
            part.copy(
                stereoDownmix = calibration.stereoDownmix,
                subtitleDelaySec = calibration.subtitleDelaySec,
                audioDelaySec = calibration.audioDelaySec,
                subtitleScale = calibration.subtitleScale,
                subtitlePositionPercent = calibration.subtitlePositionPercent
            )
        }
    }
}
