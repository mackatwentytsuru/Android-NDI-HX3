package com.example.ndireceiver.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Data class representing app settings.
 */
data class AppSettings(
    val autoReconnect: Boolean = true,
    val screenAlwaysOn: Boolean = true,
    val showOsd: Boolean = true,
    val lastConnectedSourceName: String? = null,
    val lastConnectedSourceUrl: String? = null
)

/**
 * Repository for managing app settings using SharedPreferences.
 * Provides reactive updates via StateFlow.
 */
class SettingsRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "ndi_receiver_settings"

        // Preference keys
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_SCREEN_ALWAYS_ON = "screen_always_on"
        private const val KEY_SHOW_OSD = "show_osd"
        private const val KEY_LAST_SOURCE_NAME = "last_source_name"
        private const val KEY_LAST_SOURCE_URL = "last_source_url"

        // Default values
        private const val DEFAULT_AUTO_RECONNECT = true
        private const val DEFAULT_SCREEN_ALWAYS_ON = true
        private const val DEFAULT_SHOW_OSD = true

        @Volatile
        private var instance: SettingsRepository? = null

        /**
         * Get singleton instance of SettingsRepository.
         */
        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /**
     * Load settings from SharedPreferences.
     */
    private fun loadSettings(): AppSettings {
        return AppSettings(
            autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT),
            screenAlwaysOn = prefs.getBoolean(KEY_SCREEN_ALWAYS_ON, DEFAULT_SCREEN_ALWAYS_ON),
            showOsd = prefs.getBoolean(KEY_SHOW_OSD, DEFAULT_SHOW_OSD),
            lastConnectedSourceName = prefs.getString(KEY_LAST_SOURCE_NAME, null),
            lastConnectedSourceUrl = prefs.getString(KEY_LAST_SOURCE_URL, null)
        )
    }

    /**
     * Set auto-reconnect preference.
     * Uses commit() for synchronous write to ensure thread-safety with StateFlow update.
     */
    fun setAutoReconnect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).commit()
        _settings.value = _settings.value.copy(autoReconnect = enabled)
    }

    /**
     * Set screen always-on preference.
     * Uses commit() for synchronous write to ensure thread-safety with StateFlow update.
     */
    fun setScreenAlwaysOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREEN_ALWAYS_ON, enabled).commit()
        _settings.value = _settings.value.copy(screenAlwaysOn = enabled)
    }

    /**
     * Set OSD display preference.
     * Uses commit() for synchronous write to ensure thread-safety with StateFlow update.
     */
    fun setShowOsd(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_OSD, enabled).commit()
        _settings.value = _settings.value.copy(showOsd = enabled)
    }

    /**
     * Save last connected source for auto-reconnect.
     * Uses commit() for synchronous write to ensure thread-safety with StateFlow update.
     */
    fun setLastConnectedSource(name: String?, url: String?) {
        prefs.edit()
            .putString(KEY_LAST_SOURCE_NAME, name)
            .putString(KEY_LAST_SOURCE_URL, url)
            .commit()
        _settings.value = _settings.value.copy(
            lastConnectedSourceName = name,
            lastConnectedSourceUrl = url
        )
    }

    /**
     * Clear last connected source.
     */
    fun clearLastConnectedSource() {
        setLastConnectedSource(null, null)
    }

    /**
     * Check if auto-reconnect is enabled.
     */
    fun isAutoReconnectEnabled(): Boolean = _settings.value.autoReconnect

    /**
     * Check if screen always-on is enabled.
     */
    fun isScreenAlwaysOnEnabled(): Boolean = _settings.value.screenAlwaysOn

    /**
     * Check if OSD is enabled.
     */
    fun isOsdEnabled(): Boolean = _settings.value.showOsd

    /**
     * Get last connected source name.
     */
    fun getLastConnectedSourceName(): String? = _settings.value.lastConnectedSourceName

    /**
     * Get last connected source URL.
     */
    fun getLastConnectedSourceUrl(): String? = _settings.value.lastConnectedSourceUrl

    /**
     * Check if there is a last connected source.
     */
    fun hasLastConnectedSource(): Boolean = _settings.value.lastConnectedSourceName != null

    /**
     * Get current settings snapshot.
     */
    fun getSettings(): AppSettings = _settings.value

    /**
     * Get storage directory path for display.
     */
    fun getStorageLocationInfo(context: Context): String {
        val recordingsDir = context.getExternalFilesDir(null)?.let {
            java.io.File(it, "recordings")
        }
        return recordingsDir?.absolutePath ?: "Not available"
    }
}
