package dev.gyro.core.domain.usecase

import dev.gyro.core.domain.model.PlaybackSessionEvent
import dev.gyro.core.domain.model.PlaybackSource
import dev.gyro.core.domain.model.PlaybackTriggerSource
import dev.gyro.core.domain.model.PlaylistStep
import dev.gyro.core.domain.model.contentUri
import dev.gyro.core.domain.session.PlaybackSessionStateHolder
import dev.gyro.core.domain.session.PlaylistStateHolder
import javax.inject.Inject

/**
 * Single-method UseCase strictly responsible for stepping the cursor within the active domain
 * playlist ([PlaylistStep.Next], [PlaylistStep.Previous], [PlaylistStep.AtIndex]).
 *
 * Returns `true` when the target step existed within playlist bounds and loading began, or `false`
 * when at the boundary / out of range.
 */
class NavigatePlaylistUseCase @Inject constructor(
    private val playlistStateHolder: PlaylistStateHolder,
    private val sessionStateHolder: PlaybackSessionStateHolder,
    private val loadMediaUseCase: LoadMediaUseCase,
) {
    operator fun invoke(
        step: PlaylistStep,
        triggerSource: PlaybackTriggerSource? = null,
    ): Boolean {
        val fromIndex = playlistStateHolder.currentIndex.value
        if (step is PlaylistStep.AtIndex && triggerSource != null) {
            sessionStateHolder.emitEvent(
                PlaybackSessionEvent.SwitchInitiated(
                    fromIndex = fromIndex,
                    toIndex = step.index,
                    source = triggerSource,
                )
            )
        }

        val target = when (step) {
            PlaylistStep.Next -> playlistStateHolder.playNext()
            PlaylistStep.Previous -> playlistStateHolder.playPrevious()
            is PlaylistStep.AtIndex -> playlistStateHolder.playAtIndex(step.index)
        } ?: return false

        loadMediaUseCase(
            PlaybackSource.UriItem(
                uri = target.contentUri,
                displayTitle = target.displayName,
                initialWidth = target.width,
                initialHeight = target.height,
            )
        )

        if (step is PlaylistStep.AtIndex && triggerSource != null) {
            sessionStateHolder.emitEvent(
                PlaybackSessionEvent.SwitchCompleted(
                    index = step.index,
                    uri = target.contentUri,
                )
            )
        }
        return true
    }
}
