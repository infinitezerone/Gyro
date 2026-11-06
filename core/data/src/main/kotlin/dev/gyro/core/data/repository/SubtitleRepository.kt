package dev.gyro.core.data.repository

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

class DefaultSubtitleRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SubtitleRepository {

    override suspend fun findCompanionSubtitles(
        videoUri: Uri,
        videoDisplayName: String?
    ): List<String> = withContext(ioDispatcher) {
        CompanionSubtitleScanner.findCompanionSubtitles(context, videoUri, videoDisplayName)
    }
}
