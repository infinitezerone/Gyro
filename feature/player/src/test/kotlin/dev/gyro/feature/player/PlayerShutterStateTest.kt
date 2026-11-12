package dev.gyro.feature.player

import android.net.TestUri
import androidx.compose.ui.unit.IntSize
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shutter used to be an untestable `LaunchedEffect` inside `PlayerScreen`. These pin the
 * decisions that used to be re-verified on hardware.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerShutterStateTest {

    private val size = IntSize(1920, 1080)

    private suspend fun PlayerShutterState.revealPath() = evaluate(
        isSwitching = false,
        firstFrameRendered = true,
        geometryReady = true,
        videoAspect = 16f / 9f,
        videoWidth = 1920,
        videoHeight = 1080,
        resizeMode = ResizeMode.FIT,
        containerSize = size,
        bufferSize = size,
        awaitFrame = {},
    )

    @Test
    fun switchingKeepsTheShutterClosed() = runTest {
        val state = PlayerShutterState()

        state.evaluate(
            isSwitching = true,
            firstFrameRendered = true,
            geometryReady = true,
            videoAspect = 16f / 9f,
            videoWidth = 1920,
            videoHeight = 1080,
            resizeMode = ResizeMode.FIT,
            containerSize = size,
            bufferSize = size,
            awaitFrame = {},
        )

        assertFalse(state.isReady)
        assertTrue(
            state.covers(
                activeUri = TestUri("content://media/1"),
                playbackState = PlaybackState.BUFFERING,
                isPipLayout = false,
                firstFrameRendered = true,
                isSwitching = true,
            )
        )
    }

    @Test
    fun revealPathOpensTheShutterWithoutTimeouting() = runTest {
        val state = PlayerShutterState()

        state.revealPath()

        assertTrue(state.isReady)
        assertFalse(state.timedOut)
    }

    @Test
    fun stuckGeometryRevealsAfterTheDeadline() = runTest {
        val state = PlayerShutterState()

        val job = launch {
            state.evaluate(
                isSwitching = false,
                firstFrameRendered = true,
                geometryReady = false,
                videoAspect = 16f / 9f,
                videoWidth = 1920,
                videoHeight = 1080,
                resizeMode = ResizeMode.FIT,
                containerSize = size,
                bufferSize = size,
                awaitFrame = {},
            )
        }
        runCurrent()
        assertFalse("still holding before the deadline", state.isReady)

        advanceTimeBy(1_600L)
        runCurrent()
        job.join()

        assertTrue("a stuck gate must not mean a permanent black screen", state.isReady)
        assertTrue(state.timedOut)
    }

    @Test
    fun coversTracksPlaybackStateAndPip() = runTest {
        val state = PlayerShutterState()

        assertTrue(
            state.covers(
                activeUri = TestUri("content://media/1"),
                playbackState = PlaybackState.READY,
                isPipLayout = false,
                firstFrameRendered = true,
                isSwitching = false,
            )
        )
        assertFalse(
            state.covers(
                activeUri = null,
                playbackState = PlaybackState.READY,
                isPipLayout = false,
                firstFrameRendered = true,
                isSwitching = false,
            )
        )
        assertFalse(
            state.covers(
                activeUri = TestUri("content://media/1"),
                playbackState = PlaybackState.ERROR,
                isPipLayout = false,
                firstFrameRendered = true,
                isSwitching = false,
            )
        )
        assertFalse(
            state.covers(
                activeUri = TestUri("content://media/1"),
                playbackState = PlaybackState.READY,
                isPipLayout = true,
                firstFrameRendered = true,
                isSwitching = false,
            )
        )
    }
}
