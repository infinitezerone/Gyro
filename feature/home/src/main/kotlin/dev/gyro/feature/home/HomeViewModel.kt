package dev.gyro.feature.home

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.model.MediaDeleteResult
import dev.gyro.core.data.repository.CustomFolderRepository
import dev.gyro.core.domain.model.MediaFeedQuery
import dev.gyro.core.domain.model.MediaFeedState
import dev.gyro.core.domain.model.VideoSortOrder
import dev.gyro.core.domain.usecase.DeleteMediaUseCase
import dev.gyro.core.domain.usecase.ObserveMediaFeedUseCase
import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface HomeUiEvent {
    data class RequestDeleteConsent(val intentSender: IntentSender) : HomeUiEvent
    data class ShowMessage(val message: String) : HomeUiEvent
}

data class HomeUiState(
    /** Starts as [MediaFeedState.Loading]: the library is unknown until the first snapshot. */
    val mediaFeed: MediaFeedState = MediaFeedState.Loading,
    val hasPermission: Boolean = false,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val sortOrder: VideoSortOrder = VideoSortOrder.DATE_MODIFIED_DESC,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeMediaFeedUseCase: ObserveMediaFeedUseCase,
    private val deleteMediaUseCase: DeleteMediaUseCase,
    private val customFolderRepository: CustomFolderRepository,
) : ViewModel() {

    private val _query = MutableStateFlow(MediaFeedQuery())
    private val _isSearching = MutableStateFlow(false)
    private val _events = MutableSharedFlow<HomeUiEvent>()

    val events: SharedFlow<HomeUiEvent> = _events.asSharedFlow()

    val uiState: StateFlow<HomeUiState> = combine(
        observeMediaFeedUseCase(_query),
        _query,
        _isSearching,
    ) { feed, query, isSearching ->
        HomeUiState(
            mediaFeed = feed,
            hasPermission = query.hasPermission,
            searchQuery = query.searchQuery,
            isSearching = isSearching,
            sortOrder = query.sortOrder,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun onPermissionResult(granted: Boolean) {
        _query.update { it.copy(hasPermission = granted) }
    }

    fun refreshVideos() {
        _query.update { it.copy(refreshVersion = it.refreshVersion + 1) }
    }

    fun selectFolder(folder: String?) {
        _query.update { it.copy(selectedFolder = folder) }
    }

    fun setSortOrder(order: VideoSortOrder) {
        _query.update { it.copy(sortOrder = order) }
    }

    fun deleteVideo(item: VideoMediaItem) {
        viewModelScope.launch {
            val uri = runCatching { Uri.parse(item.uriString) }.getOrNull() ?: return@launch
            when (val result = deleteMediaUseCase(uri)) {
                is MediaDeleteResult.Deleted -> {
                    _events.emit(HomeUiEvent.ShowMessage("已删除 \"${item.displayName}\""))
                }
                is MediaDeleteResult.RequiresIntentSender -> {
                    _events.emit(HomeUiEvent.RequestDeleteConsent(result.intentSender))
                }
                is MediaDeleteResult.Failed -> {
                    _events.emit(HomeUiEvent.ShowMessage(result.message ?: "删除失败"))
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _query.update { it.copy(searchQuery = query) }
    }

    fun toggleSearch(active: Boolean) {
        _isSearching.value = active
        if (!active) {
            _query.update { it.copy(searchQuery = "") }
        }
    }

    fun addCustomFolder(treeUri: Uri) {
        viewModelScope.launch {
            customFolderRepository.addFolder(treeUri)
        }
    }
}
