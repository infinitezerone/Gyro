package dev.gyro.engine.api.controller

import dev.gyro.core.model.ChapterInfo
import dev.gyro.engine.model.TrackInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * Controller interface for audio, video, subtitle tracks and chapter navigation.
 */
interface TrackController {
    val tracks: StateFlow<List<TrackInfo>>
    val chapters: StateFlow<List<ChapterInfo>>
    val currentChapter: StateFlow<Int>
    val secondarySubId: StateFlow<Int>

    fun selectTrack(type: String, id: Int)
    fun addSubtitle(path: String)
}
