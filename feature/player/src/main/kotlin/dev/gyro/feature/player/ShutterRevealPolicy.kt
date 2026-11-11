package dev.gyro.feature.player

import dev.gyro.engine.model.ResizeMode

/**
 * Pure decision for when the black shutter over the surface may be pulled down after a switch.
 *
 * This is the UI half of the first-frame protocol (commit chain 95cfc92..bd223b4): native only
 * reports facts ("an authentic first frame hit the screen"), and THIS policy decides whether the
 * host geometry has converged before the old buffer is uncovered. Revealing too early is the
 * "corner jump": the previous file's buffer appears at the old aspect and then visibly snaps
 * once layout settles.
 *
 * Three convergence facts are required for FIT-like modes, in the order they actually land on a
 * switch: (1) the engine confirmed firstFrameRendered and geometryReady, (2) the Compose
 * container settled to the target aspect, (3) the **surface buffer** was re-locked to an aspect
 * matching the video. Fact 3 was the 2026-11-08 regression: dimension pre-warm converges the
 * container instantly, but `setFixedSize` is an async transaction, so the reveal raced the
 * re-lock and displayed the new video through the previous file's buffer.
 *
 * Flexible modes (CROP/STRETCH) scale whatever buffer exists, and audio-only has no buffer
 * content requirement, so both bypass the geometry checks.
 */
object ShutterRevealPolicy {

    /** Container-vs-target aspect divergence tolerated before reveal. Matches the value the
     *  on-device fix chain converged on; changing it re-opens the corner-jump window. */
    const val ASPECT_TOLERANCE = 0.05f

    sealed interface Decision {
        /** Container geometry has converged; the shutter may be revealed after one frame. */
        data object Reveal : Decision

        /** Keep the shutter up; geometry not settled yet (or prerequisites missing). */
        data object Hold : Decision
    }

    fun evaluate(
        firstFrameRendered: Boolean,
        geometryReady: Boolean,
        videoAspect: Float,
        videoWidth: Int,
        videoHeight: Int,
        resizeMode: ResizeMode,
        containerWidth: Int,
        containerHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int
    ): Decision {
        val isAudioOnly = videoWidth == 0 && videoHeight == 0
        if (!firstFrameRendered || !geometryReady) return Decision.Hold
        if (!isAudioOnly && videoAspect <= 0f) return Decision.Hold

        if (isAudioOnly) return Decision.Reveal

        val isFlexibleMode = resizeMode == ResizeMode.CROP || resizeMode == ResizeMode.STRETCH
        if (isFlexibleMode) return Decision.Reveal

        val targetAspect = when (resizeMode) {
            ResizeMode.FIT -> videoAspect
            ResizeMode.ASPECT_16_9 -> 16f / 9f
            ResizeMode.ASPECT_21_9 -> 21f / 9f
            else -> videoAspect
        }
        val containerAspect = if (containerHeight > 0) {
            containerWidth.toFloat() / containerHeight
        } else 0f
        val isLayoutSettled = containerWidth > 0 && containerHeight > 0 &&
            kotlin.math.abs(containerAspect - targetAspect) <= targetAspect * ASPECT_TOLERANCE

        // Fact 3 (the surface buffer itself): `setFixedSize` is an async transaction, so the buffer can
        // still carry the previous geometry when the first frame lands. Revealing then shows a picture
        // drawn into a mismatched buffer — on the single-file VIEW-intent path that is the full-screen
        // portrait buffer from before the video size is known, and the result is a black picture.
        val bufferAspect = if (bufferHeight > 0) bufferWidth.toFloat() / bufferHeight else 0f
        val isBufferSettled = bufferWidth > 0 && bufferHeight > 0 &&
            kotlin.math.abs(bufferAspect - targetAspect) <= targetAspect * ASPECT_TOLERANCE

        return if (isLayoutSettled && isBufferSettled) Decision.Reveal else Decision.Hold
    }
}
