package dev.gyro.core.domain.session

import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only view of the playback queue owned by `:core:domain`.
 *
 * Callers observe [items] and [currentIndex]; cursor mutations happen exclusively through domain
 * UseCases ([dev.gyro.core.domain.usecase.LoadMediaUseCase] / [dev.gyro.core.domain.usecase.NavigatePlaylistUseCase])
 * so queue traversal and session resume logic never diverge.
 */
interface Playlist {
    val items: StateFlow<List<VideoMediaItem>>
    val currentIndex: StateFlow<Int>
    val currentItem: VideoMediaItem?
    val hasNext: Boolean
    val hasPrevious: Boolean
    val repeatMode: StateFlow<PlaylistRepeatMode>
    /** 播完后是否有下一个动作（含单集循环/列表循环）。 */
    val hasAutoAdvanceTarget: Boolean
}
