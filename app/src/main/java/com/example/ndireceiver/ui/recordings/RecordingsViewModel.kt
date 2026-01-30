package com.example.ndireceiver.ui.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ndireceiver.data.Recording
import com.example.ndireceiver.data.RecordingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * UI state for the recordings screen.
 */
data class RecordingsUiState(
    val recordings: List<Recording> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val totalSize: Long = 0,
    val deleteConfirmation: Recording? = null,
    val deletedRecording: Recording? = null
) {
    /**
     * Get formatted storage info text.
     */
    fun getStorageInfo(): String {
        val count = recordings.size
        val countText = if (count == 1) "1 recording" else "$count recordings"

        val sizeText = when {
            totalSize >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", totalSize / (1024.0 * 1024.0 * 1024.0))
            totalSize >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", totalSize / (1024.0 * 1024.0))
            totalSize >= 1024 -> String.format(Locale.US, "%.0f KB", totalSize / 1024.0)
            else -> "$totalSize bytes"
        }

        return "$countText | $sizeText"
    }
}

/**
 * ViewModel for the recordings list screen.
 *
 * Handles loading, displaying, and deleting recorded video files.
 */
class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RecordingRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(RecordingsUiState())
    val uiState: StateFlow<RecordingsUiState> = _uiState.asStateFlow()

    init {
        loadRecordings()
    }

    /**
     * Load all recordings from storage.
     */
    fun loadRecordings() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val recordings = repository.getRecordings()
                val totalSize = repository.getTotalSize()

                _uiState.value = _uiState.value.copy(
                    recordings = recordings,
                    totalSize = totalSize,
                    isLoading = false,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load recordings: ${e.message}"
                )
            }
        }
    }

    /**
     * Refresh the recordings list.
     */
    fun refresh() {
        loadRecordings()
    }

    /**
     * Request confirmation to delete a recording.
     */
    fun requestDelete(recording: Recording) {
        _uiState.value = _uiState.value.copy(deleteConfirmation = recording)
    }

    /**
     * Cancel deletion confirmation.
     */
    fun cancelDelete() {
        _uiState.value = _uiState.value.copy(deleteConfirmation = null)
    }

    /**
     * Confirm and perform deletion of the recording.
     */
    fun confirmDelete() {
        val recording = _uiState.value.deleteConfirmation ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(deleteConfirmation = null)

            try {
                val success = repository.deleteRecording(recording)
                if (success) {
                    // Show deletion notification briefly
                    _uiState.value = _uiState.value.copy(deletedRecording = recording)

                    // Reload the list
                    loadRecordings()

                    // Clear deletion notification after delay
                    kotlinx.coroutines.delay(2000)
                    _uiState.value = _uiState.value.copy(deletedRecording = null)
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Failed to delete ${recording.name}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Error deleting recording: ${e.message}"
                )
            }
        }
    }

    /**
     * Delete a recording directly without confirmation.
     * Use requestDelete() for user-initiated deletion.
     */
    fun deleteRecording(recording: Recording) {
        requestDelete(recording)
    }

    /**
     * Clear the error state.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Clear the deleted recording notification.
     */
    fun clearDeletedNotification() {
        _uiState.value = _uiState.value.copy(deletedRecording = null)
    }
}
