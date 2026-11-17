package dev.gyro.core.data.repository

import android.graphics.Bitmap

/**
 * Repository interface for persisting captured video frame screenshots.
 * Implementations must guarantee main-safety.
 */
interface ScreenshotRepository {
    suspend fun saveScreenshot(
        bitmap: Bitmap,
        mediaTitle: String,
        positionMs: Long,
    ): Boolean
}
