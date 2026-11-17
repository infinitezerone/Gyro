package dev.gyro.feature.settings.folders

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.CustomFolderRepository
import dev.gyro.core.model.CustomFolderDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderManagementUiState(
    val isLoading: Boolean = false,
    val folders: List<CustomFolderDetail> = emptyList(),
)

@HiltViewModel
class FolderManagementViewModel @Inject constructor(
    private val customFolderRepository: CustomFolderRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderManagementUiState(isLoading = true))
    val uiState: StateFlow<FolderManagementUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        loadFolders()
    }

    fun loadFolders() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val details = customFolderRepository.getFolderDetails()
                _uiState.value = FolderManagementUiState(isLoading = false, folders = details)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            if (customFolderRepository.addFolder(uri)) {
                loadFolders()
            }
        }
    }

    fun revokeFolder(uri: Uri) {
        viewModelScope.launch {
            customFolderRepository.removeFolder(uri)
            loadFolders()
        }
    }
}
