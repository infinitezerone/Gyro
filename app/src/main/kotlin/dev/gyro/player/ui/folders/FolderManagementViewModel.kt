package dev.gyro.player.ui.folders

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.model.CustomFolderDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FolderManagementUiState(
    val isLoading: Boolean = false,
    val folders: List<CustomFolderDetail> = emptyList()
)

class FolderManagementViewModel(application: Application) : AndroidViewModel(application) {

    private val mediaRepository = MediaRepository(application)

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
