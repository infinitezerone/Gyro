package dev.gyro.feature.player

import dev.gyro.engine.model.ResizeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the UI half of the first-frame protocol: the invariants the on-device fix chain
 * (95cfc92..bd223b4) converged on, now CI-asserted instead of re-verified on hardware.
 */
class ShutterRevealPolicyTest {

    private val w1080 = 1920
    private val h1080 = 1080

    private fun evaluate(
        firstFrame: Boolean = true,
        geometryReady: Boolean = true,
        videoAspect: Float = 16f / 9f,
        videoWidth: Int = w1080,
        videoHeight: Int = h1080,
        resizeMode: ResizeMode = ResizeMode.FIT,
        containerWidth: Int = w1080,
        containerHeight: Int = h1080,
        bufferWidth: Int = 2261,
        bufferHeight: Int = 1272
    ) = ShutterRevealPolicy.evaluate(
        firstFrameRendered = firstFrame,
        geometryReady = geometryReady,
        videoAspect = videoAspect,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        resizeMode = resizeMode,
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        bufferWidth = bufferWidth,
        bufferHeight = bufferHeight
    )

    @Test
    fun holdsUntilFirstFrameRendered() {
        assertEquals(ShutterRevealPolicy.Decision.Hold, evaluate(firstFrame = false))
    }

    @Test
    fun holdsUntilGeometryReady() {
        assertEquals(ShutterRevealPolicy.Decision.Hold, evaluate(geometryReady = false))
    }

    @Test
    fun holdsWhileVideoAspectIsUnset() {
        // A nonzero-video file with aspect 0 is an engine state that never reached geometry;
        // revealing here would show the old buffer at the old container.
        assertEquals(ShutterRevealPolicy.Decision.Hold, evaluate(videoAspect = 0f))
    }

    @Test
    fun audioOnlyRevealsWithoutAspectOrContainer() {
        assertEquals(
            ShutterRevealPolicy.Decision.Reveal,
            evaluate(videoWidth = 0, videoHeight = 0, videoAspect = 0f, containerWidth = 0, containerHeight = 0)
        )
    }

    @Test
    fun flexibleModesRevealWithoutContainerConvergence() {
        // CROP/STRETCH scale the surface itself; container aspect convergence is irrelevant.
        for (mode in listOf(ResizeMode.CROP, ResizeMode.STRETCH)) {
            assertEquals(
                "flexible mode $mode",
                ShutterRevealPolicy.Decision.Reveal,
                evaluate(resizeMode = mode, containerWidth = 0, containerHeight = 0)
            )
        }
    }

    @Test
    fun fitRevealsOnceContainerMatchesVideoAspect() {
        assertEquals(ShutterRevealPolicy.Decision.Reveal, evaluate())
    }

    @Test
    fun fitHoldsWhileContainerStillConverging() {
        // 4:3 container vs 16:9 video target: layout has not settled, keep the shutter up.
        assertEquals(ShutterRevealPolicy.Decision.Hold, evaluate(containerWidth = 1440, containerHeight = 1080))
    }

    @Test
    fun aspectOverrideModesConvergeOnTargetAspectNotVideoAspect() {
        // 4:3 video forced into a 16:9 container with ASPECT_16_9: the container matches the
        // override target and the buffer is locked to the video's own aspect, so reveal even
        // though the container diverges from the video's own aspect.
        assertEquals(
            ShutterRevealPolicy.Decision.Reveal,
            evaluate(
                videoAspect = 4f / 3f,
                videoWidth = 1440,
                videoHeight = 1080,
                resizeMode = ResizeMode.ASPECT_16_9,
                containerWidth = 1920,
                containerHeight = 1080,
                bufferWidth = 1440,
                bufferHeight = 1080
            )
        )
    }

    @Test
    fun toleranceBoundaryMatchesFixedThreshold() {
        // Exactly at the 5% tolerance edge (inclusive) reveals; beyond it holds.
        val target = 16f / 9f
        val atEdge = target * (1f + ShutterRevealPolicy.ASPECT_TOLERANCE)
        assertEquals(
            ShutterRevealPolicy.Decision.Reveal,
            evaluate(containerWidth = (atEdge * 1080f).toInt(), containerHeight = 1080)
        )
        val beyondEdge = target * (1f + ShutterRevealPolicy.ASPECT_TOLERANCE + 0.01f)
        assertEquals(
            ShutterRevealPolicy.Decision.Hold,
            evaluate(containerWidth = (beyondEdge * 1080f).toInt(), containerHeight = 1080)
        )
    }

    @Test
    fun emptyContainerInFitModeHolds() {
        // No layout yet: container 0x0 cannot have converged.
        assertEquals(ShutterRevealPolicy.Decision.Hold, evaluate(containerWidth = 0, containerHeight = 0))
    }

    @Test
    fun holdsWhileBufferStillCarriesPreviousFilesAspect() {
        // The 2026-11-08 regression: switching from a landscape 16:9 file to a tall portrait
        // one. Dimension pre-warm converges the container instantly, but setFixedSize is an
        // async transaction - the buffer still carries the previous file's geometry, so mpv's
        // first frame lands in the stale BufferQueue and the picture zooms out of a corner.
        assertEquals(
            ShutterRevealPolicy.Decision.Hold,
            evaluate(
                videoAspect = 0.4586f,
                videoWidth = 1080,
                videoHeight = 2354,
                containerWidth = 1271,
                containerHeight = 2772,
                bufferWidth = 2261,
                bufferHeight = 1272
            )
        )
    }

    @Test
    fun revealsOnceRelockedBufferMatchesTarget() {
        // Same switch after the re-lock transaction has landed (surfaceChanged applied).
        assertEquals(
            ShutterRevealPolicy.Decision.Reveal,
            evaluate(
                videoAspect = 0.4586f,
                videoWidth = 1080,
                videoHeight = 2354,
                containerWidth = 1271,
                containerHeight = 2772,
                bufferWidth = 1272,
                bufferHeight = 2772
            )
        )
    }

    @Test
    fun holdsWhileBufferNotYetApplied() {
        // No surfaceChanged has run yet (0x0): the live BufferQueue geometry is unknown.
        assertEquals(
            ShutterRevealPolicy.Decision.Hold,
            evaluate(bufferWidth = 0, bufferHeight = 0)
        )
    }

    @Test
    fun sameAspectBufferOfDifferentSizeStillReveals() {
        // Same-aspect playlist switches intentionally skip the re-lock (epsilon); the equally
        // shaped buffer renders the new file without distortion.
        assertEquals(
            ShutterRevealPolicy.Decision.Reveal,
            evaluate(bufferWidth = 1920, bufferHeight = 1080)
        )
    }
}
