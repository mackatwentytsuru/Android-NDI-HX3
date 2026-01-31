package com.example.ndireceiver.ndi

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NDI source discovery service using Kotlin Flow.
 * Discovers NDI sources on the network using mDNS/Bonjour.
 * Uses the native JNI wrapper (NdiNative) for NDI operations.
 * 
 * Thread safety:
 * - finderPtr is managed with AtomicLong to prevent race conditions
 * - stopDiscovery uses getAndSet(0) to atomically clear the pointer
 */
class NdiFinder {
    companion object {
        private const val TAG = "NdiFinder"
        private const val DISCOVERY_TIMEOUT_MS = 1000
    }

    // Use AtomicLong for thread-safe access to finder pointer
    private val finderPtrAtomic = AtomicLong(0)
    
    @Volatile
    private var isDiscovering = false
    
    // Prevent concurrent cleanup operations
    private val cleanupInProgress = AtomicBoolean(false)

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
            val newPtr = NdiNative.finderCreate(
                showLocalSources = true,
                groups = "",
                extraIps = ""
            )

            if (newPtr == 0L) {
                Log.e(TAG, "Failed to create NDI Finder")
                close(IllegalStateException("Failed to create NDI Finder"))
                return@callbackFlow
            }
            
            finderPtrAtomic.set(newPtr)

            Log.d(TAG, "NDI Finder created, starting discovery")

            var lastSourceNames = emptySet<String>()

            while (isActive && isDiscovering) {
                val ptr = finderPtrAtomic.get()
                if (ptr == 0L) {
                    Log.d(TAG, "Finder pointer is null, exiting discovery loop")
                    break
                }
                
                try {
                    // Wait for source changes with timeout
                    val changed = NdiNative.finderWaitForSources(ptr, DISCOVERY_TIMEOUT_MS)

                    // Get current sources - check pointer again
                    val currentPtr = finderPtrAtomic.get()
                    if (currentPtr == 0L) break
                    
                    val sourceNames = NdiNative.finderGetSources(currentPtr)
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
     * Uses AtomicLong.getAndSet(0) to atomically get and clear the pointer,
     * ensuring only one cleanup can run at a time.
     */
    fun stopDiscovery() {
        isDiscovering = false
        
        // Prevent concurrent cleanup
        if (!cleanupInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Cleanup already in progress, skipping")
            return
        }
        
        try {
            // Atomically get and clear the pointer
            val ptr = finderPtrAtomic.getAndSet(0)
            if (ptr != 0L) {
                Log.d(TAG, "Destroying NDI Finder")
                NdiNative.finderDestroy(ptr)
            }
            Log.d(TAG, "NDI Finder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping NDI Finder", e)
        } finally {
            cleanupInProgress.set(false)
        }
    }

    /**
     * Check if discovery is currently running.
     */
    fun isDiscovering(): Boolean = isDiscovering
}
