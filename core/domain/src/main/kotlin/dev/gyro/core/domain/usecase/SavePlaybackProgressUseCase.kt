package dev.gyro.core.domain.usecase

import android.net.Uri
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.engine.api.PlayerEngine
import javax.inject.Inject

/**
 * Reusable single-method domain UseCase to snapshot and persist the active media's playback
 * position into [PlaybackHistoryRepository].
 *
 * When [expectedUri] is supplied, validates that the engine has not already transitioned to a
 * different URI before reading position/duration, preventing cross-video progress corruption.
 */
class SavePlaybackProgressUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val historyRepository: PlaybackHistoryRepository,
) {
    operator fun invoke(expectedUri: Uri? = null) {
        val snapshot = engine.state.value
        val activeUri = snapshot.currentUri ?: return
        if (expectedUri != null && activeUri != expectedUri) return

        val curPos = engine.timePosMs.value
        val curDur = snapshot.durationMs
        if (curDur > 0L) {
            historyRepository.saveProgress(activeUri, curPos, curDur, snapshot.mediaTitle)
        }
    }
}
