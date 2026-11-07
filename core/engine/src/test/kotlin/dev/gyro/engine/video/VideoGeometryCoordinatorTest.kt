package dev.gyro.engine.video

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.session.PlaybackSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VideoGeometryCoordinatorTest {

    private lateinit var bridge: FakeMpvNativeBridge
    private lateinit var coordinator: VideoGeometryCoordinator

    @Before
    fun setUp() {
        bridge = FakeMpvNativeBridge()
        coordinator = RealVideoGeometryCoordinator(bridge)
    }

    @Test
    fun testInitialState() {
        assertEquals(PlaybackSessionState.Idle, coordinator.sessionState.value)
        assertFalse(coordinator.firstFrameRendered.value)
        assertFalse(coordinator.geometryReady.value)
        assertEquals(VideoDimensions(0, 0), coordinator.videoDimensions.value)
    }

    @Test
    fun testSwitchingSuppressesStaleVoParams() {
        // Given previous playback had 1920x1080 in VO
        bridge.properties["video-out-params/dw"] = 1920L
        bridge.properties["video-out-params/dh"] = 1080L

        // When switch starts to a new video
        coordinator.onSwitchStarted("test://video2.mp4", 0L)
        assertTrue(coordinator.sessionState.value is PlaybackSessionState.Switching)
        assertFalse(coordinator.firstFrameRendered.value)
        assertFalse(coordinator.geometryReady.value)

        // When VO fires stale property update at T+7ms
        coordinator.onVoParamsChanged()

        // Then stale VO dims must NOT be published
        assertEquals(VideoDimensions(0, 0), coordinator.videoDimensions.value)
        assertFalse(coordinator.geometryReady.value)
    }

    @Test
    fun testFileLoadedPublishesDemuxDimensionsImmediately() {
        coordinator.onSwitchStarted("test://vertical.mp4", 0L)

        // Given demuxer parsed vertical track (720x1570)
        bridge.properties["track-list/count"] = 1L
        bridge.properties["track-list/0/type"] = "video"
        bridge.properties["track-list/0/demux-w"] = 720L
        bridge.properties["track-list/0/demux-h"] = 1570L
        bridge.properties["track-list/0/demux-rotation"] = 0L

        // And VO still holds old horizontal dimensions (1920x1080)
        bridge.properties["video-out-params/dw"] = 1920L
        bridge.properties["video-out-params/dh"] = 1080L

        // When FILE_LOADED arrives
        coordinator.onFileLoaded("test://vertical.mp4", hasVideo = true)

        // Then demux dimensions are prioritized!
        val state = coordinator.sessionState.value as PlaybackSessionState.Demuxed
        assertEquals(720, state.dimensions.width)
        assertEquals(1570, state.dimensions.height)
        assertEquals(VideoDimensions(720, 1570), coordinator.videoDimensions.value)
        assertTrue(coordinator.geometryReady.value)
        // First frame is still waiting for decoder
        assertFalse(coordinator.firstFrameRendered.value)
    }

    @Test
    fun testFirstFramePresentedTransitionsToPresenting() {
        coordinator.onSwitchStarted("test://video.mp4", 0L)
        bridge.properties["track-list/count"] = 1L
        bridge.properties["track-list/0/type"] = "video"
        bridge.properties["track-list/0/demux-w"] = 1280L
        bridge.properties["track-list/0/demux-h"] = 720L
        coordinator.onFileLoaded("test://video.mp4", hasVideo = true)

        coordinator.onFirstFramePresented()

        val state = coordinator.sessionState.value as PlaybackSessionState.Presenting
        assertEquals(VideoDimensions(1280, 720), state.dimensions)
        assertTrue(coordinator.firstFrameRendered.value)
        assertTrue(coordinator.geometryReady.value)
    }

    @Test
    fun testDynamicAbrResolutionChangeWhenPresenting() {
        // Setup in Presenting state at 720p
        coordinator.onSwitchStarted("test://abr_stream.m3u8", 0L)
        bridge.properties["track-list/count"] = 1L
        bridge.properties["track-list/0/type"] = "video"
        bridge.properties["track-list/0/demux-w"] = 1280L
        bridge.properties["track-list/0/demux-h"] = 720L
        coordinator.onFileLoaded("test://abr_stream.m3u8", hasVideo = true)
        coordinator.onFirstFramePresented()

        // Dynamic ABR switches to 1080p
        bridge.properties["video-out-params/dw"] = 1920L
        bridge.properties["video-out-params/dh"] = 1080L
        coordinator.onVoParamsChanged()

        // Authoritative VO update accepted during Presenting!
        assertEquals(VideoDimensions(1920, 1080), coordinator.videoDimensions.value)
    }

    @Test
    fun testAudioOnlyFile() {
        coordinator.onSwitchStarted("test://audio.mp3", 0L)
        coordinator.onFileLoaded("test://audio.mp3", hasVideo = false)

        assertTrue(coordinator.firstFrameRendered.value)
        assertTrue(coordinator.geometryReady.value)
        assertTrue(coordinator.sessionState.value is PlaybackSessionState.Presenting)
    }
}
