package dev.gyro.core.domain.usecase

import dev.gyro.core.data.repository.CustomFolderRepository
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.domain.model.MediaFeed
import dev.gyro.core.domain.model.MediaFeedQuery
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

private data class FilteredMediaFeed(
    val isLoading: Boolean,
    val rawVideos: List<VideoMediaItem>,
    val filteredVideos: List<VideoMediaItem>,
    val folders: List<String>,
    val effectiveSelectedFolder: String?,
)

/**
 * Single-method UseCase coordinating [MediaRepository], [CustomFolderRepository], and
 * [PlaybackHistoryRepository] into a reactive [MediaFeed]:
 * - Re-scans storage only when media permission, manual refresh version, or persisted custom SAF
 *   folder grants change (e.g. folders added/revoked in Settings)
 * - Performs folder and search-query filtering purely in memory without redundant disk/MediaStore IO
 * - Automatically resets stale folder selection if a custom folder was revoked
 */
class ObserveMediaFeedUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val customFolderRepository: CustomFolderRepository,
    private val historyRepository: PlaybackHistoryRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(queryFlow: Flow<MediaFeedQuery>): Flow<MediaFeed> = flow {
        var cachedVideos: List<VideoMediaItem> = emptyList()

        val scanTriggerFlow = combine(
            queryFlow.map { it.hasPermission to it.refreshVersion }.distinctUntilChanged(),
            customFolderRepository.savedFolderUris.distinctUntilChanged(),
        ) { (hasPermission, refreshVersion), customUris ->
            Triple(hasPermission, refreshVersion, customUris)
        }.distinctUntilChanged()

        val scanResultFlow = scanTriggerFlow.transformLatest { (hasPermission, _, customUris) ->
            if (!hasPermission && customUris.isEmpty()) {
                cachedVideos = emptyList()
                emit(false to emptyList())
            } else {
                emit(true to cachedVideos)
                val loaded = mediaRepository.getVideos()
                cachedVideos = loaded
                emit(false to loaded)
            }
        }

        val filteredFeedFlow = combine(
            scanResultFlow,
            queryFlow.map { it.selectedFolder to it.searchQuery }.distinctUntilChanged(),
        ) { (isLoading, rawVideos), (selectedFolder, searchQuery) ->
            val folders = rawVideos.map { it.folderName }.distinct().sorted()
            val effectiveFolder = selectedFolder?.takeIf { it in folders }
            val filtered = rawVideos.filter { video ->
                val matchesFolder = effectiveFolder == null || video.folderName == effectiveFolder
                val matchesQuery = searchQuery.isBlank() ||
                    video.displayName.contains(searchQuery, ignoreCase = true)
                matchesFolder && matchesQuery
            }
            FilteredMediaFeed(
                isLoading = isLoading,
                rawVideos = rawVideos,
                filteredVideos = filtered,
                folders = folders,
                effectiveSelectedFolder = effectiveFolder,
            )
        }.distinctUntilChanged()

        emitAll(
            combine(
                filteredFeedFlow,
                historyRepository.progressMap.distinctUntilChanged(),
            ) { feed, progressMap ->
                MediaFeed(
                    isLoading = feed.isLoading,
                    rawVideos = feed.rawVideos,
                    filteredVideos = feed.filteredVideos,
                    folders = feed.folders,
                    effectiveSelectedFolder = feed.effectiveSelectedFolder,
                    playbackMap = progressMap,
                )
            }
        )
    }
}
