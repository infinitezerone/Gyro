package dev.gyro.player.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gyro.player.data.model.VideoMediaItem
import dev.gyro.player.data.repository.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false,
    val rawVideos: List<VideoMediaItem> = emptyList(),
    val filteredVideos: List<VideoMediaItem> = emptyList(),
    val folders: List<String> = emptyList(),
    val selectedFolder: String? = null,
    val searchQuery: String = "",
    val isSearching: Boolean = false
)

private data class FilterState(
    val selectedFolder: String? = null,
    val searchQuery: String = "",
    val isSearching: Boolean = false
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)

    private val _isLoading = MutableStateFlow(false)
    private val _hasPermission = MutableStateFlow(false)
    private val _rawVideos = MutableStateFlow<List<VideoMediaItem>>(emptyList())
    private val _filters = MutableStateFlow(FilterState())

    val uiState: StateFlow<HomeUiState> = combine(
        _isLoading,
        _hasPermission,
        _rawVideos,
        _filters
    ) { isLoading, hasPermission, rawVideos, filters ->
        val folders = rawVideos.map { it.folderName }.distinct().sorted()

        val filtered = rawVideos.filter { video ->
            val matchesFolder = filters.selectedFolder == null || video.folderName == filters.selectedFolder
            val matchesQuery = filters.searchQuery.isBlank() || video.displayName.contains(filters.searchQuery, ignoreCase = true)
            matchesFolder && matchesQuery
        }

        HomeUiState(
            isLoading = isLoading,
            hasPermission = hasPermission,
            rawVideos = rawVideos,
            filteredVideos = filtered,
            folders = folders,
            selectedFolder = filters.selectedFolder,
            searchQuery = filters.searchQuery,
            isSearching = filters.isSearching
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState(isLoading = true)
    )

    fun onPermissionResult(granted: Boolean) {
        _hasPermission.value = granted
        if (granted) {
            refreshVideos()
        }
    }

    fun refreshVideos() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val list = repository.getVideos()
                _rawVideos.value = list
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectFolder(folder: String?) {
        _filters.update { it.copy(selectedFolder = folder) }
    }

    fun onSearchQueryChange(query: String) {
        _filters.update { it.copy(searchQuery = query) }
    }

    fun toggleSearch(active: Boolean) {
        _filters.update {
            it.copy(
                isSearching = active,
                searchQuery = if (!active) "" else it.searchQuery
            )
        }
    }
}
