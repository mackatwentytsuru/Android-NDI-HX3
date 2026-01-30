package com.example.ndireceiver.ndi

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * NDI source discovery service using Kotlin Flow.
 * Discovers NDI sources on the network using mDNS/Bonjour.
 * Uses the native JNI wrapper (NdiNative) for NDI operations.
 */
class NdiFinder {
    companion object {
        private const val TAG = "NdiFinder"
        private const val DISCOVERY_TIMEOUT_MS = 1000
    }

    private var finderPtr: Long = 0
    @Volatile
    private var isDiscovering = false

    /**
     * Start discovering NDI sources on the network.
     * Emits updated source lists whenever changes are detected.
     */
    fun startDiscovery(): Flow<List<NdiSource>> = callbackFlow {
        if (!NdiManager.isInitialized()) {
            Log.e(TAG, "NDI SDK not initialized")
            close(IllegalStateException("NDI SDK not initialized"))
            return@callbackFlow
        }

        isDiscovering = true

        try {
            // Create native finder
            finderPtr = NdiNative.finderCreate(
                showLocalSources = true,
                groups = "",
                extraIps = ""
            )

            if (finderPtr == 0L) {
                Log.e(TAG, "Failed to create NDI Finder")
                close(IllegalStateException("Failed to create NDI Finder"))
                return@callbackFlow
            }

            Log.d(TAG, "NDI Finder created, starting discovery")

            var lastSourceNames = emptySet<String>()

            while (isActive && isDiscovering) {
                try {
                    // Wait for source changes with timeout
                    val changed = NdiNative.finderWaitForSources(finderPtr, DISCOVERY_TIMEOUT_MS)

                    // Get current sources
                    val sourceNames = NdiNative.finderGetSources(finderPtr)
                    val sources = sourceNames.map { name ->
                        NdiSource(name = name)
                    }

                    val currentSourceNames = sources.map { it.name }.toSet()

                    // Only emit if sources actually changed or on first iteration
                    if (changed || lastSourceNames != currentSourceNames) {
                        Log.d(TAG, "Found ${sources.size} NDI sources")
                        trySend(sources)
                        lastSourceNames = currentSourceNames
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during source discovery", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create NDI Finder", e)
            close(e)
        }

        awaitClose {
            stopDiscovery()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Stop the discovery process and release resources.
     */
    fun stopDiscovery() {
        isDiscovering = false
        try {
            if (finderPtr != 0L) {
                NdiNative.finderDestroy(finderPtr)
                finderPtr = 0
            }
            Log.d(TAG, "NDI Finder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping NDI Finder", e)
        }
    }

    /**
     * Check if discovery is currently running.
     */
    fun isDiscovering(): Boolean = isDiscovering
}
