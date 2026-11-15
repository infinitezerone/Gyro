package dev.gyro.engine.video

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.api.session.PlaybackSessionState
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
        coordinator = VideoGeometryCoordinator(bridge)
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

    @Test
    fun testExpiredEpochEventIsSilentlyDropped() {
        // Advance the session (bridge-owned epoch) until epoch = 10
        repeat(10) { bridge.nextEpoch() }
        coordinator.onSwitchStarted("test://video2.mp4", 0L)
        assertEquals(10L, bridge.currentEpoch())
        assertFalse(coordinator.firstFrameRendered.value)

        // Old event from epoch 9 arrives
        coordinator.onEvent(dev.gyro.engine.MpvEvent.Lifecycle(dev.gyro.engine.MpvNativeBridge.Event.FIRST_FRAME, epoch = 9L))

        // Must be silently dropped, firstFrameRendered remains false!
        assertFalse(coordinator.firstFrameRendered.value)
        assertTrue(coordinator.sessionState.value is PlaybackSessionState.Switching)

        // Valid event from epoch 10 arrives
        coordinator.onEvent(dev.gyro.engine.MpvEvent.Lifecycle(dev.gyro.engine.MpvNativeBridge.Event.FIRST_FRAME, epoch = 10L))

        // Now firstFrameRendered is true and state is Presenting!
        assertTrue(coordinator.firstFrameRendered.value)
        assertTrue(coordinator.sessionState.value is PlaybackSessionState.Presenting)
    }

    @Test
    fun testSwitchResetsFirstFrameFromPreviousSession() {
        // Video 1 plays to a fully revealed state
        coordinator.onSwitchStarted("test://video1.mp4", 0L)
        coordinator.onFileLoaded("test://video1.mp4", hasVideo = true)
        coordinator.onFirstFramePresented()
        assertTrue(coordinator.firstFrameRendered.value)
        assertTrue(coordinator.sessionState.value is PlaybackSessionState.Presenting)

        // Switching to video 2 must pull the first-frame flag back down IMMEDIATELY, before any
        // event of the new session arrives - otherwise the UI shutter would open over video 2
        // with video 1's stale buffer (the "corner jump" class).
        coordinator.onSwitchStarted("test://video2.mp4", 0L)
        assertFalse(coordinator.firstFrameRendered.value)
        assertTrue(coordinator.sessionState.value is PlaybackSessionState.Switching)
    }

    @Test
    fun testPrewarmedDimensionsAreReadyBeforeFileLoads() {
        // Dimension pre-warm (bd223b4): playlist metadata flows in at switch start, so the UI
        // has the correct aspect BEFORE demux lands and the shutter can evaluate geometry
        // without waiting for the file to load.
        coordinator.onSwitchStarted("test://prewarmed.mp4", 0L, initialWidth = 1920, initialHeight = 1080)

        assertTrue(coordinator.geometryReady.value)
        assertEquals(VideoDimensions(1920, 1080), coordinator.videoDimensions.value)
        assertEquals(1920f / 1080f, coordinator.videoAspect.value, 0.001f)
        assertFalse(coordinator.firstFrameRendered.value)
        assertTrue(coordinator.sessionState.value is PlaybackSessionState.Switching)
    }

    @Test
    fun testStartFileToGeometryToFirstFrameSequenceWithOutOfOrderArrival() {
        // 1. 模拟旧视频 (1920x1080) 处于 Presenting 稳定播放态
        coordinator.onSwitchStarted("test://video1.mp4", 0L)
        bridge.properties["track-list/count"] = 1L
        bridge.properties["track-list/0/type"] = "video"
        bridge.properties["track-list/0/demux-w"] = 1920L
        bridge.properties["track-list/0/demux-h"] = 1080L
        coordinator.onFileLoaded("test://video1.mp4", hasVideo = true)
        coordinator.onFirstFramePresented()

        assertEquals(VideoDimensions(1920, 1080), coordinator.videoDimensions.value)
        assertTrue(coordinator.firstFrameRendered.value)
        assertTrue(coordinator.sessionState.value is PlaybackSessionState.Presenting)

        // 2. 切换到新视频（竖屏 720x1280），触发 START_FILE / onSwitchStarted
        bridge.nextEpoch()
        val newEpoch = bridge.currentEpoch()
        coordinator.onSwitchStarted("test://vertical_reel.mp4", 0L)

        // 时序不变量 1：首帧标记与尺寸就绪状态必须立即拉低，严防旧视频首帧残影泄露
        assertFalse("换片开始必须立即拉低首帧状态", coordinator.firstFrameRendered.value)
        assertFalse("换片开始必须重置几何就绪状态", coordinator.geometryReady.value)
        assertTrue(coordinator.sessionState.value is PlaybackSessionState.Switching)

        // 3. 乱序到达场景：上一部残留的 VO 属性在 T+5ms 到达
        bridge.properties["video-out-params/dw"] = 1920L
        bridge.properties["video-out-params/dh"] = 1080L
        coordinator.onVoParamsChanged()

        // 时序不变量 2：Switching 状态下必须彻底压制旧 VO 属性，尺寸不得发布
        assertEquals(VideoDimensions(0, 0), coordinator.videoDimensions.value)
        assertFalse(coordinator.geometryReady.value)
        assertFalse(coordinator.firstFrameRendered.value)

        // 4. 新视频 Demuxer 尺寸就绪并触发 FILE_LOADED
        bridge.properties["track-list/count"] = 1L
        bridge.properties["track-list/0/type"] = "video"
        bridge.properties["track-list/0/demux-w"] = 720L
        bridge.properties["track-list/0/demux-h"] = 1280L
        coordinator.onFileLoaded("test://vertical_reel.mp4", hasVideo = true)

        // 时序不变量 3：Demux 尺寸确立，状态机进入 Demuxed，但首帧必须仍在等待解码
        assertTrue(coordinator.sessionState.value is PlaybackSessionState.Demuxed)
        assertEquals(VideoDimensions(720, 1280), coordinator.videoDimensions.value)
        assertTrue(coordinator.geometryReady.value)
        assertFalse("首帧仍未绘制，不得提前置 true", coordinator.firstFrameRendered.value)

        // 5. 跨纪元旧首帧事件到达（上一部视频在旧 epoch 延迟发出的 FIRST_FRAME）
        coordinator.onEvent(dev.gyro.engine.MpvEvent.Lifecycle(
            dev.gyro.engine.MpvNativeBridge.Event.FIRST_FRAME,
            epoch = newEpoch - 1
        ))
        // 时序不变量 4：过期纪元事件必须被丢弃
        assertFalse("过期纪元事件不得放行首帧", coordinator.firstFrameRendered.value)
        assertTrue(coordinator.sessionState.value is PlaybackSessionState.Demuxed)

        // 6. 真实首帧在新 epoch 完成绘制并上屏
        coordinator.onEvent(dev.gyro.engine.MpvEvent.Lifecycle(
            dev.gyro.engine.MpvNativeBridge.Event.FIRST_FRAME,
            epoch = newEpoch
        ))

        // 时序不变量 5：状态机进入 Presenting，首帧已呈现，几何完备且为新尺寸
        assertTrue("新纪元首帧成功放行", coordinator.firstFrameRendered.value)
        val presentingState = coordinator.sessionState.value as PlaybackSessionState.Presenting
        assertEquals(VideoDimensions(720, 1280), presentingState.dimensions)
    }

    @Test
    fun testHdrStateSuppressedDuringSwitchAndResetToSdr() {
        // 1. 模拟上一部视频为 HLG HDR，处于正常 Presenting 状态
        coordinator.onSwitchStarted("test://hdr_video.mp4", 0L)
        bridge.properties["video-params/gamma"] = "hlg"
        bridge.properties["video-params/colormatrix"] = "bt.2020-ncl"
        bridge.properties["track-list/count"] = 1L
        bridge.properties["track-list/0/type"] = "video"
        bridge.properties["track-list/0/demux-w"] = 1920L
        bridge.properties["track-list/0/demux-h"] = 1080L
        coordinator.onFileLoaded("test://hdr_video.mp4", hasVideo = true)
        coordinator.onFirstFramePresented()

        // 验证 HDR 已被点亮
        assertTrue(coordinator.isHdr.value)
        assertEquals("HLG", coordinator.hdrFormat.value)
        assertEquals("yes", bridge.properties["hdr-output"])

        // 2. 切换到 SDR 视频（onSwitchStarted 换片起点）
        coordinator.onSwitchStarted("test://sdr_video.mp4", 0L)

        // 时序不变量 1：换片开始必须立即复位 HDR UI 状态，并主动将底层 hdr-output 置回 "no"
        assertFalse("换片开始必须立即拉低 isHdr", coordinator.isHdr.value)
        assertEquals("", coordinator.hdrFormat.value)
        assertEquals("no", bridge.properties["hdr-output"])
        assertEquals("auto", bridge.properties["target-trc"])
        assertEquals("auto", bridge.properties["target-prim"])

        // 3. 模拟换片真空期内，底层残留的旧 HLG VO 属性在 T+5ms 再次发来
        bridge.properties["video-params/gamma"] = "hlg"
        coordinator.onVoParamsChanged()

        // 时序不变量 2：在 Switching 期间，即使收到残留的 hlg 属性，也必须被严格抑制！
        assertFalse("换片真空期内旧 HDR 属性不得翻转状态", coordinator.isHdr.value)
        assertEquals("", coordinator.hdrFormat.value)
        assertEquals("no", bridge.properties["hdr-output"])

        // 4. 新 SDR 视频加载完成 (onFileLoaded)，此时新视频 gamma 为标准 sdr
        bridge.properties["video-params/gamma"] = "bt.709"
        bridge.properties["video-params/colormatrix"] = "bt.709"
        coordinator.onFileLoaded("test://sdr_video.mp4", hasVideo = true)

        // 时序不变量 3：新视频加载完成后，真实裁决为 SDR，绝不发生误判为 HDR
        assertFalse("新视频装载后稳定为 SDR", coordinator.isHdr.value)
        assertEquals("", coordinator.hdrFormat.value)
        assertEquals("no", bridge.properties["hdr-output"])
    }
}
