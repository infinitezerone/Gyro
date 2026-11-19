package dev.gyro.core.domain.usecase

import dev.gyro.core.data.repository.CustomFolderRepository
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.domain.model.MediaFeedQuery
import dev.gyro.core.domain.model.MediaFeedState
import dev.gyro.core.domain.model.VideoSortOrder
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single-method UseCase turning [MediaRepository]'s cached library into the state the home screen
 * renders, merged with playback progress and narrowed by the selected folder and search query.
 *
 * This class no longer scans storage. MediaStore is external data: its owner ([MediaRepository])
 * keeps the cache and decides when it is stale (provider observer, generation check), while this
 * use case only reacts to the inputs it actually owns — permission, manual refresh, granted
 * folders, folder selection and the search query. That keeps "revisiting the screen" from being a
 * reason to query the media provider.
 */
class ObserveMediaFeedUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val customFolderRepository: CustomFolderRepository,
    private val historyRepository: PlaybackHistoryRepository,
) {
    operator fun invoke(queryFlow: Flow<MediaFeedQuery>): Flow<MediaFeedState> = flow {
        val triggerFlow = combine(
            queryFlow.map { it.hasPermission to it.refreshVersion }.distinctUntilChanged(),
            customFolderRepository.savedFolderUris.distinctUntilChanged(),
        ) { (hasPermission, refreshVersion), customUris ->
            Triple(hasPermission, refreshVersion, customUris)
        }.distinctUntilChanged()

        // First read of the library on this process; later triggers mean a scan input changed.
        mediaRepository.ensureLoaded()

        coroutineScope {
            launch {
                triggerFlow.drop(1).collect { mediaRepository.requestRefresh() }
            }

            val filteredFlow = combine(
                mediaRepository.observeVideos(),
                queryFlow.map { Triple(it.selectedFolder, it.searchQuery, it.sortOrder) }.distinctUntilChanged(),
            ) { videos, (selectedFolder, searchQuery, sortOrder) ->
                if (videos == null) {
                    // Nothing scanned yet: report "unknown" rather than an empty library.
                    null
                } else {
                    val folders = videos.map { it.folderName }.distinct().sorted()
                    val effectiveFolder = selectedFolder?.takeIf { it in folders }
                    val filtered = videos.filter { video ->
                        val matchesFolder = effectiveFolder == null || video.folderName == effectiveFolder
                        val matchesQuery = searchQuery.isBlank() ||
                            video.displayName.contains(searchQuery, ignoreCase = true)
                        matchesFolder && matchesQuery
                    }
                    val sorted = when (sortOrder) {
                        VideoSortOrder.DATE_MODIFIED_DESC -> filtered.sortedByDescending { it.dateModified }
                        VideoSortOrder.DATE_MODIFIED_ASC -> filtered.sortedBy { it.dateModified }
                        VideoSortOrder.NAME_ASC -> filtered.sortedBy { it.displayName.lowercase() }
                        VideoSortOrder.NAME_DESC -> filtered.sortedByDescending { it.displayName.lowercase() }
                        VideoSortOrder.SIZE_DESC -> filtered.sortedByDescending { it.sizeBytes }
                        VideoSortOrder.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
                    }
                    MediaFeedState.Ready(
                        rawVideos = videos,
                        filteredVideos = sorted,
                        folders = folders,
                        effectiveSelectedFolder = effectiveFolder,
                        sortOrder = sortOrder,
                    )
                }
            }.distinctUntilChanged()

            emitAll(
                combine(
                    filteredFlow,
                    historyRepository.progressMap.distinctUntilChanged(),
                ) { feed, progressMap ->
                    feed?.copy(playbackMap = progressMap) ?: MediaFeedState.Loading
                }
            )
        }
    }
}
