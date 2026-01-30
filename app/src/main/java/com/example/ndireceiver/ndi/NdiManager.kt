package com.example.ndireceiver.ndi

import android.util.Log

/**
 * Singleton manager for NDI SDK initialization and lifecycle.
 * Uses the native JNI wrapper (NdiNative) instead of Devolay.
 */
object NdiManager {
    private const val TAG = "NdiManager"

    @Volatile
    private var initialized = false

    @Volatile
    private var nativeLibraryLoaded = false

    @Volatile
    var lastError: String? = null
        private set

    /**
     * Initialize the NDI SDK. Must be called before using any NDI functionality.
     * @return true if initialization succeeded, false otherwise
     */
    @Synchronized
    fun initialize(): Boolean {
        if (initialized) {
            Log.d(TAG, "NDI already initialized")
            return true
        }

        return try {
            // First, try to load the native libraries
            if (!nativeLibraryLoaded) {
                try {
                    // This triggers NdiNative's static initializer which loads the libraries
                    val isNativeInit = NdiNative.isInitialized()
                    nativeLibraryLoaded = true
                    Log.d(TAG, "Native library loaded, isInitialized=$isNativeInit")
                } catch (e: UnsatisfiedLinkError) {
                    lastError = "Native library not found: ${e.message}"
                    Log.e(TAG, lastError!!, e)
                    return false
                }
            }

            // Initialize NDI SDK via native wrapper
            initialized = NdiNative.initialize()

            if (initialized) {
                val version = NdiNative.getVersion()
                Log.i(TAG, "NDI SDK initialized successfully (version: $version)")
                lastError = null
            } else {
                lastError = "NDI SDK initialization failed"
                Log.e(TAG, lastError!!)
            }
            initialized
        } catch (e: Exception) {
            lastError = "Exception during NDI initialization: ${e.message}"
            Log.e(TAG, lastError!!, e)
            false
        }
    }

    /**
     * Check if NDI SDK is initialized.
     */
    fun isInitialized(): Boolean = initialized && nativeLibraryLoaded

    /**
     * Get NDI SDK version string.
     */
    fun getVersion(): String {
        return if (nativeLibraryLoaded) {
            try {
                NdiNative.getVersion()
            } catch (e: Exception) {
                "Unknown"
            }
        } else {
            "Not loaded"
        }
    }

    /**
     * Cleanup NDI resources. Call when the app is being destroyed.
     */
    @Synchronized
    fun destroy() {
        if (initialized) {
            try {
                NdiNative.destroy()
                Log.i(TAG, "NDI SDK destroyed")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying NDI SDK", e)
            }
            initialized = false
        }
    }
}
