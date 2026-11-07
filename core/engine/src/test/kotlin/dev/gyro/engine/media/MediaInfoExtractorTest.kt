package dev.gyro.engine.media

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.state.PlaybackStore
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaInfoExtractorTest {

    @Test
    fun extract_buildsCompleteMediaInfoFromBridgeAndStore() {
        val bridge = FakeMpvNativeBridge().apply {
            properties["file-format"] = "matroska,webm"
            properties["video-codec"] = "h264"
            properties["video-bitrate"] = 5_000_000L
            properties["audio-codec-name"] = "aac"
            properties["audio-params/channels"] = "stereo"
            properties["audio-params/samplerate"] = 48_000L
            properties["audio-bitrate"] = 192_000L
            properties["file-size"] = 100_000_000L
        }
        val store = PlaybackStore().apply {
            update {
                it.copy(
                    mediaTitle = "Big Buck Bunny",
                    fps = 60.0,
                    hwdecCurrent = "mediacodec-copy",
                    droppedFrames = 5L
                )
            }
        }

        val info = MediaInfoExtractor.extract(
            bridge = bridge,
            store = store,
            videoDimensions = VideoDimensions(1920, 1080),
            durationMs = 60_000L,
            fallbackTitle = "Fallback",
            fallbackUri = "content://media/1",
            fallbackSize = 50L
        )

        assertEquals("Big Buck Bunny", info.title)
        assertEquals("content://media/1", info.pathOrUri)
        assertEquals("matroska,webm", info.containerFormat)
        assertEquals(100_000_000L, info.fileSize)
        assertEquals(60_000L, info.durationMs)
        assertEquals("h264", info.videoCodec)
        assertEquals("1920 x 1080", info.videoResolution)
        assertEquals(60.0, info.videoFps, 0.001)
        assertEquals(5_000_000L, info.videoBitrate)
        assertEquals("aac", info.audioCodec)
        assertEquals("stereo", info.audioChannels)
        assertEquals(48_000L, info.audioSampleRate)
        assertEquals(192_000L, info.audioBitrate)
        assertEquals("mediacodec-copy", info.hwdecCurrent)
        assertEquals(5L, info.droppedFrames)
    }
}
