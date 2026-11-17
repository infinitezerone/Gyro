package dev.gyro.core.domain.usecase

import android.net.Uri
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.engine.api.PlayerEngine
import javax.inject.Inject

/**
 * UseCase to take a snapshot of the current engine playback position and persist it
 * into [PlaybackHistoryRepository].
 */
class SavePlaybackProgressUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val historyRepository: PlaybackHistoryRepository,
) {
    operator fun invoke(targetUri: Uri? = null) {
        val snapshot = engine.state.value
        val uri = targetUri ?: snapshot.currentUri ?: return
        val curPos = engine.timePosMs.value
        val curDur = snapshot.durationMs
        if (curDur > 0L) {
            historyRepository.saveProgress(uri, curPos, curDur)
        }
    }
}
