package dev.gyro.feature.settings.mpv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.designsystem.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MpvConfigEditorViewModel @Inject constructor(
    private val repository: MpvConfigRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MpvConfigEditorUiState(
            content = repository.configText.value,
            originalContent = repository.configText.value,
            isModified = false
        )
    )
    val uiState: StateFlow<MpvConfigEditorUiState> = _uiState.asStateFlow()

    fun updateContent(newContent: String) {
        _uiState.update { current ->
            current.copy(
                content = newContent,
                isModified = newContent != current.originalContent
            )
        }
    }

    fun insertOption(snippet: String) {
        _uiState.update { current ->
            val currentText = current.content
            val newText = buildString {
                append(currentText)
                if (currentText.isNotEmpty() && !currentText.endsWith("\n")) {
                    append("\n")
                }
                append(snippet)
                append("\n")
            }
            current.copy(
                content = newText,
                isModified = newText != current.originalContent
            )
        }
    }

    fun saveConfig() {
        val textToSave = _uiState.value.content
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                repository.saveConfig(textToSave)
                _uiState.update {
                    it.copy(
                        originalContent = textToSave,
                        isModified = it.content != textToSave,
                        isSaving = false,
                        userMessageRes = R.string.mpv_config_saved_toast,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = e.localizedMessage ?: "Failed to save configuration"
                    )
                }
            }
        }
    }

    fun resetToDefault() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, showResetDialog = false) }
            try {
                repository.resetToDefault()
                val resetText = repository.configText.value
                _uiState.update {
                    it.copy(
                        content = resetText,
                        originalContent = resetText,
                        isModified = false,
                        isSaving = false,
                        userMessageRes = R.string.mpv_config_reset_success
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = e.localizedMessage ?: "Failed to reset configuration"
                    )
                }
            }
        }
    }

    fun showResetConfirmation(show: Boolean) {
        _uiState.update { it.copy(showResetDialog = show) }
    }

    fun showDiscardConfirmation(show: Boolean) {
        _uiState.update { it.copy(showDiscardDialog = show) }
    }

    fun showHelp(show: Boolean) {
        _uiState.update { it.copy(showHelpDialog = show) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(userMessageRes = null, errorMessage = null) }
    }
}
