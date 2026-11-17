package dev.gyro.core.data.repository

import android.net.Uri

/**
 * Repository interface for scanning and opening external/companion subtitle files.
 * Implementations must guarantee all suspend operations are main-safe and leak-free on cancellation.
 */
interface SubtitleRepository {
    /**
     * Scans for companion subtitles (.srt, .ass, .ssa, .vtt) matching [videoUri] without
     * prematurely opening file descriptors.
     */
    suspend fun findCompanionSubtitles(videoUri: Uri, videoDisplayName: String? = null): List<Uri>

    /**
     * Opens [subtitleUri] on the IO dispatcher and passes the mpv target path or `fdclose://`
     * descriptor to [consume]. If [consume] returns `false` or throws (including cancellation),
     * any detached descriptor is immediately reclaimed and closed so native FDs never leak.
     */
    suspend fun openSubtitle(subtitleUri: Uri, consume: suspend (String) -> Boolean): Boolean = false
}
