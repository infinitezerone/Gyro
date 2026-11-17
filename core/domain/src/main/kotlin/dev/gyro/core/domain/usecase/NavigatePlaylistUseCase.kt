package dev.gyro.core.domain.usecase

import dev.gyro.core.domain.model.PlaybackSource
import dev.gyro.core.domain.model.PlaylistStep
import dev.gyro.core.domain.model.contentUri
import dev.gyro.engine.api.PlayerEngine
import javax.inject.Inject

/**
 * Single-method UseCase strictly responsible for stepping the cursor within the active playlist
 * ([PlaylistStep.Next], [PlaylistStep.Previous], [PlaylistStep.AtIndex]).
 *
 * Returns `true` when the target step existed within playlist bounds and loading began, or `false`
 * when at the boundary / out of range.
 */
class NavigatePlaylistUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val loadMediaUseCase: LoadMediaUseCase,
) {
    operator fun invoke(step: PlaylistStep): Boolean {
        val target = when (step) {
            PlaylistStep.Next -> engine.advanceToNextItem()
            PlaylistStep.Previous -> engine.advanceToPreviousItem()
            is PlaylistStep.AtIndex -> engine.selectItemAt(step.index)
        } ?: return false

        loadMediaUseCase(
            PlaybackSource.UriItem(
                uri = target.contentUri,
                displayTitle = target.displayName,
                initialWidth = target.width,
                initialHeight = target.height,
            )
        )
        return true
    }
}
