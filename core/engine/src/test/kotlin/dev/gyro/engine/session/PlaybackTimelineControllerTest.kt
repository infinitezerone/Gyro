package dev.gyro.engine.session

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.tracks.RealTrackAndChapterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackTimelineControllerTest {

    private lateinit var bridge: FakeMpvNativeBridge
    private lateinit var geometry: RealVideoGeometryCoordinator
    private lateinit var trackManager: RealTrackAndChapterManager
    private lateinit var timeline: PlaybackTimelineController
    private var nowMs = 10_000L

    @Before
    fun setUp() {
        bridge = FakeMpvNativeBridge()
        geometry = RealVideoGeometryCoordinator(bridge)
        trackManager = RealTrackAndChapterManager(bridge)
        nowMs = 10_000L
        timeline = RealPlaybackTimelineController(
            bridge = bridge,
            geometry = geometry,
            trackManager = trackManager,
            elapsedRealtimeMs = { nowMs }
        )
    }

    @Test
    fun initialStateAndLoadedFlag_followGeometryWithoutIssuingSeeks() {
        assertEquals(0L, timeline.timePosMs.value)
        assertEquals(0L, timeline.durationMs.value)
        assertEquals(0L, timeline.bufferedPosMs.value)
        assertFalse(timeline.isFileLoaded)

        geometry.onSwitchStarted("test://video", 0L)
        assertFalse(timeline.isFileLoaded)
        fileLoaded()
        assertTrue(geometry.sessionState.value is PlaybackSessionState.Demuxed)
        assertTrue(timeline.isFileLoaded)
        geometry.onFirstFramePresented()
        assertTrue(timeline.isFileLoaded)
        geometry.onSessionEnded()
        assertFalse(timeline.isFileLoaded)
        geometry.reset()
        assertFalse(timeline.isFileLoaded)
        assertTrue(bridge.commands.isEmpty())
    }

    @Test
    fun propertyReports_convertToNonNegativeMillisecondsAndClampBufferToDisplay() {
        timeline.onPositionChanged(1.2345)
        timeline.onDurationChanged(12.3456)
        timeline.onBufferedPositionChanged(4.5678)
        assertEquals(1234L, timeline.timePosMs.value)
        assertEquals(12345L, timeline.durationMs.value)
        assertEquals(4567L, timeline.bufferedPosMs.value)

        timeline.onPositionChanged(-2.0)
        timeline.onDurationChanged(-3.0)
        timeline.onBufferedPositionChanged(-4.0)
        assertEquals(0L, timeline.timePosMs.value)
        assertEquals(0L, timeline.durationMs.value)
        assertEquals(0L, timeline.bufferedPosMs.value)

        fileLoaded()
        timeline.seekTo(8000L)
        timeline.onPositionChanged(1.0)
        timeline.onBufferedPositionChanged(2.0)
        assertEquals(8000L, timeline.bufferedPosMs.value)
        timeline.onBufferedPositionChanged(9.5)
        assertEquals(9500L, timeline.bufferedPosMs.value)
    }

    @Test
    fun absoluteSeek_publishesClampedTargetButSendsOriginalPosition() {
        fileLoaded()
        timeline.seekTo(5250L)
        assertEquals(5250L, timeline.timePosMs.value)
        timeline.seekTo(-2250L)
        assertEquals(0L, timeline.timePosMs.value)
        assertEquals(
            listOf(listOf("seek", "5.25", "absolute"), listOf("seek", "-2.25", "absolute")),
            bridge.commands
        )
    }

    @Test
    fun lowerToleranceBoundary_rejects1501msAndAccepts1500msThenClearsPending() {
        fileLoaded()
        timeline.seekTo(10_000L)
        timeline.onPositionChanged(8.499)
        assertEquals(10_000L, timeline.timePosMs.value)
        timeline.onPositionChanged(8.5)
        assertEquals(8500L, timeline.timePosMs.value)
        timeline.onPositionChanged(1.0)
        assertEquals(1000L, timeline.timePosMs.value)
    }

    @Test
    fun upperToleranceBoundary_rejects1501msAndAccepts1500msThenClearsPending() {
        fileLoaded()
        timeline.seekTo(10_000L)
        timeline.onPositionChanged(11.501)
        assertEquals(10_000L, timeline.timePosMs.value)
        timeline.onPositionChanged(11.5)
        assertEquals(11_500L, timeline.timePosMs.value)
        timeline.onPositionChanged(20.0)
        assertEquals(20_000L, timeline.timePosMs.value)
    }

    @Test
    fun forwardAndBackwardSeeks_ignoreStaleReportsUntilLanding() {
        fileLoaded()
        timeline.onPositionChanged(2.0)
        timeline.seekTo(20_000L)
        timeline.onPositionChanged(2.0)
        assertEquals(20_000L, timeline.timePosMs.value)
        timeline.onPositionChanged(20.25)
        assertEquals(20_250L, timeline.timePosMs.value)

        timeline.seekTo(3000L)
        timeline.onPositionChanged(20.25)
        assertEquals(3000L, timeline.timePosMs.value)
        timeline.onPositionChanged(3.25)
        assertEquals(3250L, timeline.timePosMs.value)
    }

    @Test
    fun seekTimeout_rejectsAt2499msAndAcceptsAt2500msAndAfter() {
        fileLoaded()
        timeline.seekTo(20_000L)
        nowMs += 2499L
        timeline.onPositionChanged(2.0)
        assertEquals(20_000L, timeline.timePosMs.value)
        nowMs += 1L
        timeline.onPositionChanged(2.0)
        assertEquals(2000L, timeline.timePosMs.value)
        nowMs += 1L
        timeline.onPositionChanged(3.0)
        assertEquals(3000L, timeline.timePosMs.value)
    }

    @Test
    fun consecutiveAbsoluteSeeks_replaceTargetAndRestartTimeout() {
        fileLoaded()
        timeline.seekTo(10_000L)
        nowMs += 2400L
        timeline.seekTo(20_000L)
        nowMs += 100L
        timeline.onPositionChanged(10.0)
        assertEquals(20_000L, timeline.timePosMs.value)
        nowMs += 2399L
        timeline.onPositionChanged(1.0)
        assertEquals(20_000L, timeline.timePosMs.value)
        nowMs += 1L
        timeline.onPositionChanged(1.0)
        assertEquals(1000L, timeline.timePosMs.value)
        assertEquals(2, bridge.commands.size)
    }

    @Test
    fun relativeSeeks_accumulateOnDisplayedTargetAndSendUnclampedOffsets() {
        fileLoaded()
        timeline.seekTo(5000L)
        timeline.seekRelative(3000L)
        assertEquals(8000L, timeline.timePosMs.value)
        timeline.onPositionChanged(1.0)
        timeline.seekRelative(-2000L)
        assertEquals(6000L, timeline.timePosMs.value)
        timeline.seekRelative(-10_000L)
        assertEquals(0L, timeline.timePosMs.value)
        assertEquals(
            listOf(
                listOf("seek", "5.0", "absolute"),
                listOf("seek", "3.0", "relative"),
                listOf("seek", "-2.0", "relative"),
                listOf("seek", "-10.0", "relative")
            ),
            bridge.commands
        )
    }

    @Test
    fun consecutiveRelativeSeeks_restartTimeout() {
        fileLoaded()
        timeline.seekRelative(10_000L)
        nowMs += 2400L
        timeline.seekRelative(10_000L)
        nowMs += 100L
        timeline.onPositionChanged(10.0)
        assertEquals(20_000L, timeline.timePosMs.value)
        nowMs += 2399L
        timeline.onPositionChanged(1.0)
        assertEquals(20_000L, timeline.timePosMs.value)
        nowMs += 1L
        timeline.onPositionChanged(1.0)
        assertEquals(1000L, timeline.timePosMs.value)
    }

    @Test
    fun relativeSeeks_whenNotLoadedAreIgnoredAndDoNotReplaceDeferredSeek() {
        timeline.onPositionChanged(2.0)
        timeline.seekTo(7000L)
        timeline.seekRelative(5000L)
        assertEquals(2000L, timeline.timePosMs.value)
        geometry.onSwitchStarted("test://video", 0L)
        timeline.seekRelative(-5000L)
        assertEquals(2000L, timeline.timePosMs.value)
        geometry.onSessionEnded()
        timeline.seekRelative(9000L)
        assertEquals(2000L, timeline.timePosMs.value)
        assertTrue(bridge.commands.isEmpty())

        fileLoaded()
        assertEquals(7000L, timeline.timePosMs.value)
        assertEquals(listOf(listOf("seek", "7.0", "absolute")), bridge.commands)
    }

    @Test
    fun deferredSeek_lastRequestIncludingZeroWinsAndIsConsumedOnlyOnce() {
        timeline.onPositionChanged(2.0)
        timeline.seekTo(5000L)
        timeline.seekTo(9000L)
        timeline.seekTo(0L)
        assertEquals(2000L, timeline.timePosMs.value)
        assertTrue(bridge.commands.isEmpty())

        fileLoaded()
        assertEquals(0L, timeline.timePosMs.value)
        timeline.onPositionChanged(0.25)
        fileLoaded()
        assertEquals(250L, timeline.timePosMs.value)
        assertEquals(listOf(listOf("seek", "0.0", "absolute")), bridge.commands)
    }

    @Test
    fun negativeDeferredSeek_replacesEarlierRequestWithoutIssuingACommand() {
        timeline.onLoadStarted(2000L)
        timeline.seekTo(5000L)
        timeline.seekTo(-250L)
        fileLoaded()
        fileLoaded()
        assertEquals(2000L, timeline.timePosMs.value)
        assertTrue(bridge.commands.isEmpty())
    }

    @Test
    fun loadStarted_resetsPositionDurationAndBufferPreventingStaleBufferLeak() {
        fileLoaded()
        timeline.onDurationChanged(100.0)
        timeline.onBufferedPositionChanged(50.0)
        timeline.seekTo(20_000L)

        geometry.onSwitchStarted("test://next", 4000L)
        timeline.onLoadStarted(4000L)
        assertEquals(4000L, timeline.timePosMs.value)
        assertEquals(0L, timeline.durationMs.value)
        assertEquals(4000L, timeline.bufferedPosMs.value)
        timeline.onPositionChanged(1.0)
        assertEquals(1000L, timeline.timePosMs.value)
        timeline.onLoadStarted(-500L)
        assertEquals(0L, timeline.timePosMs.value)
        assertEquals(0L, timeline.bufferedPosMs.value)
        assertEquals(1, bridge.commands.size)
    }

    @Test
    fun loadStarted_preservesDeferredSeekAcrossPositionReset() {
        timeline.seekTo(9000L)
        geometry.onSwitchStarted("test://video", 3000L)
        timeline.onLoadStarted(3000L)
        assertEquals(3000L, timeline.timePosMs.value)
        assertTrue(bridge.commands.isEmpty())

        fileLoaded()
        assertEquals(9000L, timeline.timePosMs.value)
        fileLoaded()
        assertEquals(listOf(listOf("seek", "9.0", "absolute")), bridge.commands)
    }

    @Test
    fun loadCommandIssued_clearsOnlyDeferredSeekNotFlowsOrInFlightTarget() {
        timeline.onLoadStarted(2000L)
        timeline.onDurationChanged(100.0)
        timeline.onBufferedPositionChanged(50.0)
        timeline.seekTo(9000L)
        timeline.onLoadCommandIssued()
        assertEquals(2000L, timeline.timePosMs.value)
        assertEquals(100_000L, timeline.durationMs.value)
        assertEquals(50_000L, timeline.bufferedPosMs.value)
        fileLoaded()
        assertTrue(bridge.commands.isEmpty())
        assertEquals(2000L, timeline.timePosMs.value)

        timeline.seekTo(20_000L)
        timeline.onLoadCommandIssued()
        timeline.onPositionChanged(1.0)
        assertEquals(20_000L, timeline.timePosMs.value)
        assertEquals(100_000L, timeline.durationMs.value)
        assertEquals(50_000L, timeline.bufferedPosMs.value)
        assertEquals(listOf(listOf("seek", "20.0", "absolute")), bridge.commands)
    }

    @Test
    fun loadedChapterSeek_updatesPositionPropertyAndManagerWithoutAbsoluteCommand() {
        chapters(2.25, 20.5)
        fileLoaded()
        timeline.seekToChapter(0)
        assertEquals(2250L, timeline.timePosMs.value)
        assertEquals(0L, bridge.properties["chapter"])
        assertEquals(0, trackManager.currentChapter.value)
        timeline.seekToChapter(1)
        assertEquals(20_500L, timeline.timePosMs.value)
        assertEquals(1L, bridge.properties["chapter"])
        assertEquals(1, trackManager.currentChapter.value)
        timeline.onPositionChanged(2.25)
        assertEquals(20_500L, timeline.timePosMs.value)
        timeline.onPositionChanged(20.5)
        timeline.onPositionChanged(3.0)
        assertEquals(3000L, timeline.timePosMs.value)
        assertTrue(bridge.commands.isEmpty())
    }

    @Test
    fun invalidChapterIndices_leavePositionPropertyAndManagerUnchanged() {
        fileLoaded()
        timeline.onPositionChanged(3.0)
        timeline.seekToChapter(0)
        assertEquals(3000L, timeline.timePosMs.value)
        assertFalse(bridge.properties.containsKey("chapter"))
        assertEquals(-1, trackManager.currentChapter.value)

        chapters(2.0, 20.0)
        timeline.seekToChapter(1)
        for (index in listOf(-1, 2, Int.MAX_VALUE)) {
            timeline.seekToChapter(index)
            assertEquals(20_000L, timeline.timePosMs.value)
            assertEquals(1L, bridge.properties["chapter"])
            assertEquals(1, trackManager.currentChapter.value)
        }
        assertTrue(bridge.commands.isEmpty())
    }

    @Test
    fun deferredChapterSeek_storesLastValidTimeAndIssuesOneAbsoluteSeekAfterLoad() {
        chapters(2.25, 20.5)
        timeline.seekTo(5000L)
        timeline.seekToChapter(0)
        timeline.seekToChapter(1)
        timeline.seekToChapter(-1)
        timeline.seekToChapter(2)
        assertEquals(0L, timeline.timePosMs.value)
        assertEquals(-1, trackManager.currentChapter.value)
        assertFalse(bridge.properties.containsKey("chapter"))
        assertTrue(bridge.commands.isEmpty())

        chapters(1.0, 40.0)
        fileLoaded()
        assertEquals(20_500L, timeline.timePosMs.value)
        fileLoaded()
        assertEquals(listOf(listOf("seek", "20.5", "absolute")), bridge.commands)
        assertEquals(-1, trackManager.currentChapter.value)
        assertFalse(bridge.properties.containsKey("chapter"))
    }

    @Test
    fun deferredAbsoluteSeek_overridesEarlierChapterRequest() {
        chapters(2.0, 20.0)
        timeline.seekToChapter(1)
        timeline.seekTo(7250L)
        fileLoaded()
        assertEquals(7250L, timeline.timePosMs.value)
        fileLoaded()
        assertEquals(listOf(listOf("seek", "7.25", "absolute")), bridge.commands)
        assertEquals(-1, trackManager.currentChapter.value)
        assertFalse(bridge.properties.containsKey("chapter"))
    }

    @Test
    fun deferredSeek_doesNotDependOnGeometrySubscriptionOrder() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            timeline.observeEvents(scope)
            timeline.seekTo(12_000L)
            geometry.observeEvents(scope)

            bridge.emitFileLoaded()

            assertEquals(listOf(listOf("seek", "12.0", "absolute")), bridge.commands)
            assertEquals(12_000L, timeline.timePosMs.value)
            assertTrue(timeline.isFileLoaded)
            bridge.emitFileLoaded()
            assertEquals(1, bridge.commands.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun eventSubscription_receivesTimelinePropertiesAndStopsAfterCancellation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        timeline.observeEvents(scope)
        try {
            bridge.emitPropertyDouble("time-pos", 12.5)
            bridge.emitPropertyDouble("duration", 120.0)
            bridge.emitPropertyDouble("demuxer-cache-time", 30.0)
            assertEquals(12_500L, timeline.timePosMs.value)
            assertEquals(120_000L, timeline.durationMs.value)
            assertEquals(30_000L, timeline.bufferedPosMs.value)
        } finally {
            scope.cancel()
        }
        bridge.emitPropertyDouble("time-pos", 1.0)
        assertEquals(12_500L, timeline.timePosMs.value)
    }

    private fun fileLoaded() {
        geometry.onFileLoaded("test://video", hasVideo = true)
        timeline.onFileLoaded()
    }

    private fun chapters(vararg timesSeconds: Double) {
        bridge.properties["chapter-list/count"] = timesSeconds.size.toLong()
        timesSeconds.forEachIndexed { index, seconds ->
            bridge.properties["chapter-list/$index/title"] = "Chapter $index"
            bridge.properties["chapter-list/$index/time"] = seconds
        }
        trackManager.refreshChapters()
        assertEquals(timesSeconds.size, trackManager.chapters.value.size)
    }
}
