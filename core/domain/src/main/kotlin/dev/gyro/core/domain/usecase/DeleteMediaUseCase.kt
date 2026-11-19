package dev.gyro.core.domain.usecase

import android.net.Uri
import dev.gyro.core.data.model.MediaDeleteResult
import dev.gyro.core.data.repository.MediaRepository
import javax.inject.Inject

/**
 * UseCase coordinating media file deletion via [MediaRepository].
 * Handles Scoped Storage consent logic through [MediaDeleteResult].
 */
class DeleteMediaUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(uri: Uri): MediaDeleteResult {
        return mediaRepository.deleteVideo(uri)
    }
}
