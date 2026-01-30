package com.example.ndireceiver

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.util.Log
import com.example.ndireceiver.ndi.NdiManager

/**
 * Application class for initializing NDI SDK at app startup.
 */
class NdiReceiverApplication : Application() {
    companion object {
        private const val TAG = "NdiReceiverApp"

        @Volatile
        var ndiInitializationError: String? = null
            private set

        // NsdManager instance - required for Android NDI operations
        var nsdManager: NsdManager? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()

        // CRITICAL: Initialize NsdManager BEFORE any NDI operations
        // Android requires this for mDNS/Bonjour discovery used by NDI
        try {
            nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
            Log.i(TAG, "NsdManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NsdManager", e)
        }

        // Initialize NDI SDK with error handling
        try {
            val initialized = NdiManager.initialize()
            if (initialized) {
                Log.i(TAG, "NDI SDK initialized successfully")
                ndiInitializationError = null
            } else {
                val errorMsg = "Failed to initialize NDI SDK - libraries not found"
                Log.e(TAG, errorMsg)
                ndiInitializationError = errorMsg
            }
        } catch (e: UnsatisfiedLinkError) {
            val errorMsg = "NDI native library not available: ${e.message}"
            Log.e(TAG, errorMsg, e)
            ndiInitializationError = errorMsg
        } catch (e: Exception) {
            val errorMsg = "NDI initialization error: ${e.message}"
            Log.e(TAG, errorMsg, e)
            ndiInitializationError = errorMsg
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        NdiManager.destroy()
    }
}
