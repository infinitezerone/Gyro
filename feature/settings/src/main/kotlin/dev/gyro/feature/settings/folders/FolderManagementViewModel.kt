package dev.gyro.feature.settings.folders

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.model.CustomFolderDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderManagementUiState(
    val isLoading: Boolean = false,
    val folders: List<CustomFolderDetail> = emptyList()
)

@HiltViewModel
class FolderManagementViewModel @Inject constructor(
    private val mediaRepository: MediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderManagementUiState(isLoading = true))
    val uiState: StateFlow<FolderManagementUiState> = _uiState.asStateFlow()

    init {
        loadFolders()
    }

    fun loadFolders() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val details = mediaRepository.getCustomFolderDetails()
                _uiState.value = FolderManagementUiState(isLoading = false, folders = details)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun addFolder(uri: Uri) {
        val success = mediaRepository.addCustomFolder(uri)
        if (success) {
            loadFolders()
        }
    }

    fun revokeFolder(uri: Uri) {
        mediaRepository.removeCustomFolder(uri)
        loadFolders()
    }
}
