package dev.gyro.core.domain.model

/**
 * Cursor traversal steps within the active playlist for [dev.gyro.core.domain.usecase.NavigatePlaylistUseCase].
 */
sealed interface PlaylistStep {
    data object Next : PlaylistStep
    data object Previous : PlaylistStep
    data class AtIndex(val index: Int) : PlaylistStep
}
