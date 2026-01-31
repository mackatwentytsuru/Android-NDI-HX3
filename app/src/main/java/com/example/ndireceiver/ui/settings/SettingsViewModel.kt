package com.example.ndireceiver.ui.settings

import android.app.Application
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ndireceiver.data.AppSettings
import com.example.ndireceiver.data.RecordingRepository
import com.example.ndireceiver.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * UI state for the settings screen.
 */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val storageLocation: String = "",
    val storageInfo: String = "",
    val versionInfo: String = "1.0"
)

/**
 * ViewModel for the settings screen.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository.getInstance(application)
    private val recordingRepository = RecordingRepository(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // Observe settings changes
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(settings = settings)
            }
        }

        // Load initial state
        loadStorageInfo()
        loadVersionInfo()
    }

    /**
     * Load storage location and info.
     */
    private fun loadStorageInfo() {
        viewModelScope.launch {
            val context = getApplication<Application>()

            // Get storage location
            val storageLocation = settingsRepository.getStorageLocationInfo(context)

            // Get storage info (recordings count and size)
            val recordings = recordingRepository.getRecordings()
            val totalSize = recordingRepository.getTotalSize()
            val storageInfo = formatStorageInfo(recordings.size, totalSize)

            _uiState.value = _uiState.value.copy(
                storageLocation = storageLocation,
                storageInfo = storageInfo
            )
        }
    }

    /**
     * Load app version info.
     */
    private fun loadVersionInfo() {
        val context = getApplication<Application>()
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = packageInfo.versionName ?: "1.0"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            _uiState.value = _uiState.value.copy(
                versionInfo = "$versionName ($versionCode)"
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(versionInfo = "1.0")
        }
    }

    /**
     * Format storage info as "X recordings | Y.Y GB".
     */
    private fun formatStorageInfo(count: Int, bytes: Long): String {
        val sizeStr = when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
            else -> "$bytes bytes"
        }
        val recordingText = if (count == 1) "recording" else "recordings"
        return "$count $recordingText | $sizeStr"
    }

    /**
     * Set auto-reconnect preference.
     */
    fun setAutoReconnect(enabled: Boolean) {
        settingsRepository.setAutoReconnect(enabled)
    }

    /**
     * Set screen always-on preference.
     */
    fun setScreenAlwaysOn(enabled: Boolean) {
        settingsRepository.setScreenAlwaysOn(enabled)
    }

    /**
     * Set OSD display preference.
     */
    fun setShowOsd(enabled: Boolean) {
        settingsRepository.setShowOsd(enabled)
    }

    /**
     * Clear last connected source.
     */
    fun clearLastConnectedSource() {
        settingsRepository.clearLastConnectedSource()
    }

    /**
     * Set language preference.
     */
    fun setLanguage(language: com.example.ndireceiver.data.AppLanguage) {
        settingsRepository.setLanguage(language)
    }

    /**
     * Refresh storage info.
     */
    fun refreshStorageInfo() {
        loadStorageInfo()
    }

    /**
     * Check if there is a last connected source.
     */
    fun hasLastConnectedSource(): Boolean {
        return settingsRepository.hasLastConnectedSource()
    }

    /**
     * Get last connected source name.
     */
    fun getLastConnectedSourceName(): String? {
        return settingsRepository.getLastConnectedSourceName()
    }
}
