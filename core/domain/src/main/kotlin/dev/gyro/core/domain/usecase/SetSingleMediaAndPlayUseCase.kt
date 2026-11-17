package dev.gyro.core.domain.usecase

import android.net.Uri
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.engine.api.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UseCase to set a single video media item into the player queue and load it.
 */
class SetSingleMediaAndPlayUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val loadMediaUseCase: LoadMediaUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    operator fun invoke(uri: Uri, displayName: String = uri.lastPathSegment ?: "Video"): Job {
        engine.setSingleItem(uri, displayName)
        return scope.launch {
            loadMediaUseCase(uri, displayTitle = displayName)
        }
    }
}
