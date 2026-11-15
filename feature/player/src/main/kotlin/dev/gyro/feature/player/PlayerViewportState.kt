package dev.gyro.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * Single owner of the player viewport's measured geometry - the state cluster that gestures,
 * PiP window params, the shutter-reveal policy and the persistent-surface transform all read.
 *
 * This cluster was the breeding ground of the 2026-11-08 "corner jump" regression: five
 * independent `remember { mutableStateOf }` slots scattered across the screen body, written from
 * three different callbacks. They now live here so the write sites are named events instead of
 * bare assignments, and every consumer reads one object.
 *
 * The holder owns only the *measured* sources; everything derived from them (content bounds,
 * cover size, sheet shift) stays keyed `remember` in the composition - that keeps recomposition
 * behavior identical to the pre-extraction code.
 */
internal class PlayerViewportState {
    /** Size of the fullscreen player Box, as reported by `onSizeChanged`. */
    var containerSize by mutableStateOf(IntSize.Zero)
        private set

    /**
     * The PiP source-rect hint describes where the video sits in the *fullscreen* window and only
     * matters for the enter transition. Sizing it from the container while already in picture in
     * picture would rebuild the params for the shrunk window, and every re-applied params set
     * makes the system run the resize again - that second pass is the flash seen on entering PiP.
     */
    var fullscreenContainerSize by mutableStateOf(IntSize.Zero)
        private set

    /** Position of the video container inside the screen Box, from `onGloballyPositioned`. */
    var videoContainerOrigin by mutableStateOf(Offset.Zero)
        private set

    /** Layout size of the video container (the persistent GyroPlayerSurface host). */
    var videoContainerSize by mutableStateOf(IntSize.Zero)
        private set

    /**
     * Buffer dims actually applied by the platform, forwarded from GyroPlayerSurface's
     * `onAppliedBufferSizeChanged`. Unlike the requested lock these reflect the live BufferQueue.
     */
    var appliedBufferSize by mutableStateOf(IntSize.Zero)
        private set

    fun onContainerSized(size: IntSize, inPipLayout: Boolean) {
        containerSize = size
        if (!inPipLayout) fullscreenContainerSize = size
    }

    /** @return true when the container's layout size changed (drives the CONTAINER_LAYOUT trace). */
    fun onVideoContainerPlaced(origin: Offset, size: IntSize): Boolean {
        val sizeChanged = videoContainerSize != size
        videoContainerOrigin = origin
        videoContainerSize = size
        return sizeChanged
    }

    fun onAppliedBufferSizeChanged(size: IntSize) {
        appliedBufferSize = size
    }
}

@Composable
internal fun rememberPlayerViewportState(): PlayerViewportState = remember { PlayerViewportState() }
