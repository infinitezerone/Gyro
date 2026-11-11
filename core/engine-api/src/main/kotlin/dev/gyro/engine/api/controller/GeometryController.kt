package dev.gyro.engine.api.controller

import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.VideoDimensions
import kotlinx.coroutines.flow.StateFlow

/**
 * Controller interface for video dimensions, aspect ratio, frame rate, HDR display, and zoom/pan.
 */
interface GeometryController {
    val videoDimensions: StateFlow<VideoDimensions>
    val videoAspect: StateFlow<Float>
    val videoFrameRate: StateFlow<Float>
    val resizeMode: StateFlow<ResizeMode>
    val isHdr: StateFlow<Boolean>
    val hdrFormat: StateFlow<String>
    val geometryReady: StateFlow<Boolean>
    val firstFrameRendered: StateFlow<Boolean>

    fun setDisplayHdrCapable(capable: Boolean)
    fun updateDisplayRefreshRate(rate: Float)
    fun setResizeMode(mode: ResizeMode)
    fun resetZoomAndPan()
}
