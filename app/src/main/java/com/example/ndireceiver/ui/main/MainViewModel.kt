package com.example.ndireceiver.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ndireceiver.NdiReceiverApplication
import com.example.ndireceiver.ndi.NdiFinder
import com.example.ndireceiver.ndi.NdiManager
import com.example.ndireceiver.ndi.NdiSource
import com.example.ndireceiver.ndi.NdiSourceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * UI state for the main screen.
 */
data class MainUiState(
    val isLoading: Boolean = true,
    val sources: List<NdiSource> = emptyList(),
    val error: String? = null
)

/**
 * ViewModel for the main screen - handles NDI source discovery.
 */
class MainViewModel : ViewModel() {
    private var ndiFinder: NdiFinder? = null

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var discoveryJob: Job? = null

    init {
        // Check for NDI initialization error first
        val initError = NdiReceiverApplication.ndiInitializationError
        if (initError != null) {
            _uiState.value = MainUiState(
                isLoading = false,
                error = "NDI unavailable: $initError"
            )
        } else if (NdiManager.isInitialized()) {
            ndiFinder = NdiFinder()
            startDiscovery()
        } else {
            _uiState.value = MainUiState(
                isLoading = false,
                error = "NDI SDK not initialized"
            )
        }
    }

    /**
     * Start NDI source discovery.
     */
    fun startDiscovery() {
        val finder = ndiFinder ?: return

        if (discoveryJob?.isActive == true) return

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        discoveryJob = viewModelScope.launch {
            finder.startDiscovery()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Discovery failed"
                    )
                }
                .collect { sources ->
                    // Store in repository so PlayerFragment can access with DevolaySource
                    NdiSourceRepository.updateDiscoveredSources(sources)

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        sources = sources,
                        error = null
                    )
                }
        }
    }

    /**
     * Refresh the source list.
     */
    fun refresh() {
        stopDiscovery()
        startDiscovery()
    }

    /**
     * Stop NDI source discovery.
     */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        ndiFinder?.stopDiscovery()
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }
}
