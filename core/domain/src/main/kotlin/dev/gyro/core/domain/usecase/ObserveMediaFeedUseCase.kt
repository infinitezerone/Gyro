package dev.gyro.core.domain.usecase

import dev.gyro.core.data.repository.CustomFolderRepository
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.domain.model.MediaFeedQuery
import dev.gyro.core.domain.model.MediaFeedState
import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import javax.inject.Inject

/**
 * Single-method UseCase coordinating [MediaRepository], [CustomFolderRepository], and
 * [PlaybackHistoryRepository] into a reactive [MediaFeedState]:
 * - Re-scans storage only when media permission, manual refresh version, or persisted custom SAF
 *   folder grants change (e.g. folders added/revoked in Settings)
 * - Performs folder and search-query filtering purely in memory without redundant disk/MediaStore IO
 * - Automatically resets stale folder selection if a custom folder was revoked
 *
 * The stream never emits a "loading" value: it stays silent until a snapshot exists, so whoever
 * owns the state decides when the UI is allowed to fall back to a spinner. Emitting an empty list
 * while a scan was still running is exactly what made an unknown library look like an empty one.
 */
class ObserveMediaFeedUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val customFolderRepository: CustomFolderRepository,
    private val historyRepository: PlaybackHistoryRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(queryFlow: Flow<MediaFeedQuery>): Flow<MediaFeedState> = flow {
        // Per-collection on purpose: two collectors must not observe each other's results, so a
        // no-permission collector never inherits a permitted one's list (pinned by a unit test).
        var cachedVideos: List<VideoMediaItem> = emptyList()

        val scanTriggerFlow = combine(
            queryFlow.map { it.hasPermission to it.refreshVersion }.distinctUntilChanged(),
            customFolderRepository.savedFolderUris.distinctUntilChanged(),
        ) { (hasPermission, refreshVersion), customUris ->
            Triple(hasPermission, refreshVersion, customUris)
        }.distinctUntilChanged()

        val videosFlow = scanTriggerFlow.transformLatest { (hasPermission, _, customUris) ->
            if (!hasPermission && customUris.isEmpty()) {
                cachedVideos = emptyList()
                emit(emptyList<VideoMediaItem>())
            } else {
                // A collector that already has data keeps it visible while the rescan runs, so a
                // revisit shows the previous list instead of dropping to a spinner.
                if (cachedVideos.isNotEmpty()) {
                    emit(cachedVideos)
                }
                val loaded = mediaRepository.getVideos()
                cachedVideos = loaded
                emit(loaded)
            }
        }.distinctUntilChanged()

        val filteredFlow = combine(
            videosFlow,
            queryFlow.map { it.selectedFolder to it.searchQuery }.distinctUntilChanged(),
        ) { videos, (selectedFolder, searchQuery) ->
            val folders = videos.map { it.folderName }.distinct().sorted()
            val effectiveFolder = selectedFolder?.takeIf { it in folders }
            val filtered = videos.filter { video ->
                val matchesFolder = effectiveFolder == null || video.folderName == effectiveFolder
                val matchesQuery = searchQuery.isBlank() ||
                    video.displayName.contains(searchQuery, ignoreCase = true)
                matchesFolder && matchesQuery
            }
            MediaFeedState.Ready(
                rawVideos = videos,
                filteredVideos = filtered,
                folders = folders,
                effectiveSelectedFolder = effectiveFolder,
            )
        }.distinctUntilChanged()

        emitAll(
            combine(
                filteredFlow,
                historyRepository.progressMap.distinctUntilChanged(),
            ) { feed, progressMap ->
                feed.copy(playbackMap = progressMap)
            }
        )
    }
}
