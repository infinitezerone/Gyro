package dev.gyro.feature.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.CustomFolderRepository
import dev.gyro.core.domain.model.MediaFeedQuery
import dev.gyro.core.domain.model.MediaFeedState
import dev.gyro.core.domain.usecase.ObserveMediaFeedUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    /** Starts as [MediaFeedState.Loading]: the library is unknown until the first snapshot. */
    val mediaFeed: MediaFeedState = MediaFeedState.Loading,
    val hasPermission: Boolean = false,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeMediaFeedUseCase: ObserveMediaFeedUseCase,
    private val customFolderRepository: CustomFolderRepository,
) : ViewModel() {

    private val _query = MutableStateFlow(MediaFeedQuery())
    private val _isSearching = MutableStateFlow(false)

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
