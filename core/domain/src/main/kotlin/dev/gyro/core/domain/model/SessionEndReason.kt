package dev.gyro.core.domain.model

/**
 * Semantic reason for ending a playback session via [dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase].
 */
enum class SessionEndReason(internal val shouldStopService: Boolean) {
    /** User navigated away from the player UI (back button, predictive back, PiP close). */
    USER_EXIT(shouldStopService = true),

    /** System notification Stop/Dismiss or foreground service destruction initiated teardown. */
    SERVICE_STOPPED(shouldStopService = false),
}
