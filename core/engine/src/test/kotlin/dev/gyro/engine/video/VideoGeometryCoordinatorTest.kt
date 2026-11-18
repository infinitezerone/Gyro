package dev.gyro.engine.video

import dev.gyro.core.model.HdrOutputMode
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.model.HdrDisplayCapabilities
import dev.gyro.engine.model.HdrDisplayCapabilities.HdrType
import dev.gyro.engine.model.VideoDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VideoGeometryCoordinatorTest {

    /** A panel that understands HDR10 and HLG and claims 900 nits. */
    private val HDR_PANEL = HdrDisplayCapabilities(
        supportedHdrTypes = setOf(HdrType.HDR10, HdrType.HLG),
        peakLuminanceNits = 900f,
    )

    private lateinit var bridge: FakeMpvNativeBridge
    private lateinit var coordinator: VideoGeometryCoordinator

    @Before
    fun setUp() {
        bridge = FakeMpvNativeBridge()
        coordinator = VideoGeometryCoordinator(bridge)
    }

    @Test
    fun testInitialState() {
        assertEquals(GeometrySessionState.Idle, coordinator.sessionState.value)
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
        assertTrue(coordinator.sessionState.value is GeometrySessionState.Switching)
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
        val state = coordinator.sessionState.value as GeometrySessionState.Demuxed
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

        val state = coordinator.sessionState.value as GeometrySessionState.Presenting
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
        assertTrue(coordinator.sessionState.value is GeometrySessionState.Presenting)
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
        assertTrue(coordinator.sessionState.value is GeometrySessionState.Switching)

        // Valid event from epoch 10 arrives
        coordinator.onEvent(dev.gyro.engine.MpvEvent.Lifecycle(dev.gyro.engine.MpvNativeBridge.Event.FIRST_FRAME, epoch = 10L))

        // Now firstFrameRendered is true and state is Presenting!
        assertTrue(coordinator.firstFrameRendered.value)
        assertTrue(coordinator.sessionState.value is GeometrySessionState.Presenting)
    }

    @Test
    fun testSwitchResetsFirstFrameFromPreviousSession() {
        // Video 1 plays to a fully revealed state
        coordinator.onSwitchStarted("test://video1.mp4", 0L)
        coordinator.onFileLoaded("test://video1.mp4", hasVideo = true)
        coordinator.onFirstFramePresented()
        assertTrue(coordinator.firstFrameRendered.value)
        assertTrue(coordinator.sessionState.value is GeometrySessionState.Presenting)

        // Switching to video 2 must pull the first-frame flag back down IMMEDIATELY, before any
        // event of the new session arrives - otherwise the UI shutter would open over video 2
        // with video 1's stale buffer (the "corner jump" class).
        coordinator.onSwitchStarted("test://video2.mp4", 0L)
        assertFalse(coordinator.firstFrameRendered.value)
        assertTrue(coordinator.sessionState.value is GeometrySessionState.Switching)
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
        assertTrue(coordinator.sessionState.value is GeometrySessionState.Switching)
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
        assertTrue(coordinator.sessionState.value is GeometrySessionState.Presenting)

        // 2. 切换到新视频（竖屏 720x1280），触发 START_FILE / onSwitchStarted
        bridge.nextEpoch()
        val newEpoch = bridge.currentEpoch()
        coordinator.onSwitchStarted("test://vertical_reel.mp4", 0L)

        // 时序不变量 1：首帧标记与尺寸就绪状态必须立即拉低，严防旧视频首帧残影泄露
        assertFalse("换片开始必须立即拉低首帧状态", coordinator.firstFrameRendered.value)
        assertFalse("换片开始必须重置几何就绪状态", coordinator.geometryReady.value)
        assertTrue(coordinator.sessionState.value is GeometrySessionState.Switching)

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
        assertTrue(coordinator.sessionState.value is GeometrySessionState.Demuxed)
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
        assertTrue(coordinator.sessionState.value is GeometrySessionState.Demuxed)

        // 6. 真实首帧在新 epoch 完成绘制并上屏
        coordinator.onEvent(dev.gyro.engine.MpvEvent.Lifecycle(
            dev.gyro.engine.MpvNativeBridge.Event.FIRST_FRAME,
            epoch = newEpoch
        ))

        // 时序不变量 5：状态机进入 Presenting，首帧已呈现，几何完备且为新尺寸
        assertTrue("新纪元首帧成功放行", coordinator.firstFrameRendered.value)
        val presentingState = coordinator.sessionState.value as GeometrySessionState.Presenting
        assertEquals(VideoDimensions(720, 1280), presentingState.dimensions)
    }

    @Test
    fun testHdrStateSuppressedDuringSwitchAndResetToSdr() {
        // 1. 模拟上一部视频为 HLG HDR，处于正常 Presenting 状态
        coordinator.setDisplayCapabilities(HDR_PANEL)
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
        assertEquals("pq", bridge.properties["hdr-output"])

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

    @Test
    fun testHdrOutputPublishesReportedPanelPeakAsTargetPeak() {
        coordinator.setDisplayCapabilities(HdrDisplayCapabilities(setOf(HdrType.HDR10), 900f))
        coordinator.onSwitchStarted("test://hlg.mp4", 0L)
        bridge.properties["video-params/gamma"] = "hlg"
        bridge.properties["video-params/colormatrix"] = "bt.2020-ncl"
        bridge.properties["track-list/count"] = 0L
        coordinator.onFileLoaded("test://hlg.mp4", hasVideo = true)

        assertTrue("HDR 应当被点亮", coordinator.isHdr.value)
        assertEquals("pq", bridge.properties["target-trc"])
        assertEquals("bt.2020", bridge.properties["target-prim"])
        // 没有峰值时 mpv 会在编码 PQ 前先做一遍 tone mapping，直接把对比度压平
        assertEquals(900.0, (bridge.properties["target-peak"] as Number).toDouble(), 0.01)
    }

    @Test
    fun testHdrOutputDegradesToSdrToneMappingWhenPqSurfaceUnsupported() {
        // 驱动未声明 EGL_EXT_gl_colorspace_bt2020_pq：申请 PQ surface 只会静默退回 SDR，
        // 把 PQ 码值塞进 SDR buffer 反而更糟，因此必须主动降级。
        coordinator.setDisplayCapabilities(HDR_PANEL)
        bridge.hdrColorSpaceSupportResult = HdrColorSpaceSupport(pq = false)
        coordinator.onSwitchStarted("test://hlg.mp4", 0L)
        bridge.properties["video-params/gamma"] = "hlg"
        bridge.properties["video-params/colormatrix"] = "bt.2020-ncl"
        bridge.properties["track-list/count"] = 0L
        coordinator.onFileLoaded("test://hlg.mp4", hasVideo = true)

        assertFalse("PQ surface 不可用时不得声称 HDR", coordinator.isHdr.value)
        assertEquals("HLG", coordinator.hdrFormat.value)
        assertEquals("no", bridge.properties["hdr-output"])
        assertEquals("auto", bridge.properties["target-trc"])
        // AUTO keeps the engine's configured curve; the fold must not silently swap in another one.
        assertEquals("hable", bridge.properties["tone-mapping"])
        // And it must not hand the panel's HDR peak to an SDR tone mapper.
        assertEquals("auto", bridge.properties["target-peak"])
    }

    @Test
    fun testHlgPreferenceTakesEffectOnlyWhenThePanelAndDriverBothUnderstandHlg() {
        coordinator.setDisplayCapabilities(HDR_PANEL)
        bridge.hdrColorSpaceSupportResult = HdrColorSpaceSupport(pq = true, hlg = true)
        coordinator.setHdrOutputMode(HdrOutputMode.HLG)
        coordinator.onSwitchStarted("test://hlg.mp4", 0L)
        bridge.properties["video-params/gamma"] = "hlg"
        bridge.properties["video-params/colormatrix"] = "bt.2020-ncl"
        bridge.properties["track-list/count"] = 0L
        coordinator.onFileLoaded("test://hlg.mp4", hasVideo = true)

        assertTrue(coordinator.isHdr.value)
        // 用户选了 HLG 且两端都支持：这次不再被写死成 pq
        assertEquals("hlg", bridge.properties["hdr-output"])
        assertEquals("hlg", bridge.properties["target-trc"])
    }

    @Test
    fun testRegisterObservers_observesTrackList() {
        coordinator.registerObservers()
        assertTrue(bridge.isPropertyObserved("track-list", MpvNativeBridge.Format.NONE))
    }

    @Test
    fun testDoviTrackCache_avoidsRescanningTracksOnGeometryEventsAndRefreshesOnTrackListChange() {
        var trackListScanCount = 0
        val countingBridge = object : MpvNativeBridge by bridge {
            override fun getPropertyLong(name: String): Long? {
                if (name == "track-list/count") trackListScanCount++
                return bridge.getPropertyLong(name)
            }

            override fun getLong(name: String): Long? {
                if (name == "track-list/count") trackListScanCount++
                return bridge.getLong(name)
            }
        }
        val coord = VideoGeometryCoordinator(countingBridge)
        coord.registerObservers()

        bridge.properties["track-list/count"] = 1L
        bridge.properties["track-list/0/type"] = "video"
        bridge.properties["track-list/0/codec"] = "hevc"
        bridge.properties["track-list/0/demux-w"] = 1920L
        bridge.properties["track-list/0/demux-h"] = 1080L
        bridge.properties["video-params/gamma"] = "pq"
        bridge.properties["video-out-params/dw"] = 1920L
        bridge.properties["video-out-params/dh"] = 1080L

        coord.setDisplayCapabilities(HDR_PANEL)
        coord.onSwitchStarted("test://hdr10.mkv", 0L)
        coord.onFileLoaded("test://hdr10.mkv", hasVideo = true)
        assertTrue(coord.isHdr.value)
        assertEquals("HDR10", coord.hdrFormat.value)

        // Reset counter after initial file load; subsequent dimension events must reuse cached track scan
        trackListScanCount = 0
        repeat(5) {
            coord.onEvent(MpvEvent.LongProperty("width", 1920L, bridge.currentEpoch()))
            coord.onEvent(MpvEvent.LongProperty("height", 1080L, bridge.currentEpoch()))
            coord.onEvent(MpvEvent.LongProperty("dwidth", 1920L, bridge.currentEpoch()))
            coord.onEvent(MpvEvent.LongProperty("dheight", 1080L, bridge.currentEpoch()))
            coord.onEvent(MpvEvent.PropertyChanged("video-out-params", bridge.currentEpoch()))
        }
        assertEquals(0, trackListScanCount)

        // When track-list changes to Dolby Vision, cache refreshes and disables PQ lighting
        bridge.properties["track-list/0/codec"] = "dvhe"
        coord.onEvent(MpvEvent.PropertyChanged("track-list", bridge.currentEpoch()))
        assertEquals(1, trackListScanCount)
        assertEquals("Dolby Vision", coord.hdrFormat.value)
        assertFalse(coord.isHdr.value)

        // TRACKS_CHANGED also refreshes cached Dolby Vision state
        bridge.properties["track-list/0/codec"] = "hevc"
        coord.onEvent(MpvEvent.Lifecycle(MpvNativeBridge.Event.TRACKS_CHANGED, bridge.currentEpoch()))
        assertEquals(2, trackListScanCount)
        assertEquals("HDR10", coord.hdrFormat.value)
        assertTrue(coord.isHdr.value)

        // Reset clears cache so standalone updateHdrState computes once and reuses
        coord.reset()
        trackListScanCount = 0
        coord.onVoParamsChanged()
        coord.onVoParamsChanged()
        assertEquals(1, trackListScanCount)
        assertTrue(coord.isHdr.value)
        assertEquals("HDR10", coord.hdrFormat.value)
    }
}
