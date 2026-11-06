package dev.gyro.core.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Repository interface for scanning and managing companion subtitle files.
 * Implementations must ensure all suspend operations are main-safe.
 */
interface SubtitleRepository {
    /**
     * Scans for companion subtitles (.srt, .ass, .ssa, .vtt) matching [videoUri].
     * Returns a list of paths or content URIs that can be passed to mpv `sub-add`.
     */
    suspend fun findCompanionSubtitles(videoUri: Uri, videoDisplayName: String? = null): List<String>
}

class DefaultSubtitleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SubtitleRepository {

    override suspend fun findCompanionSubtitles(
        videoUri: Uri,
        videoDisplayName: String?
    ): List<String> = withContext(ioDispatcher) {
        CompanionSubtitleScanner.findCompanionSubtitles(context, videoUri, videoDisplayName)
    }
}
