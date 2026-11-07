package dev.gyro.engine.media

import dev.gyro.core.model.MediaInfo
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.state.PlaybackStore

object MediaInfoExtractor {
    fun extract(
        bridge: MpvNativeBridge,
        store: PlaybackStore,
        videoDimensions: VideoDimensions,
        durationMs: Long,
        fallbackTitle: String = "",
        fallbackUri: String = "",
        fallbackSize: Long = 0L
    ): MediaInfo {
        val snapshot = store.current
        val title = snapshot.mediaTitle.ifBlank { fallbackTitle }
        val resStr = if (videoDimensions.width > 0 && videoDimensions.height > 0) {
            "${videoDimensions.width} x ${videoDimensions.height}"
        } else {
            ""
        }
        val format = bridge.getPropertyString("file-format") ?: ""
        val vCodec = bridge.getPropertyString("video-codec") ?: bridge.getPropertyString("video-format") ?: ""
        val vBitrate = bridge.getLong("video-bitrate") ?: 0L
        val aCodec = bridge.getPropertyString("audio-codec-name") ?: bridge.getPropertyString("audio-format") ?: ""
        val aChannels = bridge.getPropertyString("audio-params/channels") ?: ""
        val aSampleRate = bridge.getLong("audio-params/samplerate") ?: 0L
        val aBitrate = bridge.getLong("audio-bitrate") ?: 0L
        val size = bridge.getLong("file-size")?.takeIf { it > 0 } ?: fallbackSize

        return MediaInfo(
            title = title,
            pathOrUri = fallbackUri,
            containerFormat = format,
            fileSize = size,
            durationMs = durationMs,
            videoCodec = vCodec,
            videoResolution = resStr,
            videoFps = snapshot.fps,
            videoBitrate = vBitrate,
            audioCodec = aCodec,
            audioChannels = aChannels,
            audioSampleRate = aSampleRate,
            audioBitrate = aBitrate,
            hwdecCurrent = snapshot.hwdecCurrent,
            droppedFrames = snapshot.droppedFrames
        )
    }
}
