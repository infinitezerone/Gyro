package dev.gyro.feature.player

import dev.gyro.engine.model.ResizeMode

/**
 * Pure decision for when the black shutter over the surface may be pulled down after a switch.
 *
 * This is the UI half of the first-frame protocol (commit chain 95cfc92..bd223b4): native only
 * reports facts ("an authentic first frame hit the screen"), and THIS policy decides whether the
 * Compose container has converged to the video's geometry before the old buffer is uncovered.
 * Revealing too early is the "corner jump": the previous file's buffer appears at the old aspect
 * and then visibly snaps once layout settles.
 *
 * Kept as a pure function so the week's hard-won invariants are CI-asserted rather than
 * re-verified on device:
 * - never reveal before the engine confirms firstFrameRendered AND geometryReady
 * - never reveal with an unset video aspect (except audio-only, where there is none)
 * - flexible modes (CROP/STRETCH) need no container convergence, the surface is scaled
 * - FIT-like modes require the container aspect to have settled within [ASPECT_TOLERANCE]
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
        containerHeight: Int
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
        return if (isLayoutSettled) Decision.Reveal else Decision.Hold
    }
}
