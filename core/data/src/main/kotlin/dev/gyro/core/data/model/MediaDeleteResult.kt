package dev.gyro.core.data.model

import android.content.IntentSender

/**
 * Result of requesting a video deletion across shared MediaStore or SAF storage.
 */
sealed interface MediaDeleteResult {
    /** The file was deleted directly (SAF document or app-owned file). */
    data object Deleted : MediaDeleteResult

    /**
     * User confirmation required via system dialog (Scoped Storage on Android 10+).
     * The UI must launch this via an `IntentSenderRequest`.
     */
    data class RequiresIntentSender(val intentSender: IntentSender) : MediaDeleteResult

    /** The deletion could not be performed. */
    data class Failed(val message: String? = null, val cause: Throwable? = null) : MediaDeleteResult
}
