package com.example.ndireceiver.ndi

import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connection state for NDI receiver.
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val source: NdiSource) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Video frame data received from NDI source.
 */
data class VideoFrameData(
    val width: Int,
    val height: Int,
    val frameRateN: Int,
    val frameRateD: Int,
    val data: ByteBuffer,
    val lineStrideBytes: Int,
    val timestamp: Long,
    val fourCC: FourCC,
    val isCompressed: Boolean = false
)

/**
 * Callback interface for receiving video frames.
 */
interface NdiFrameCallback {
    fun onVideoFrame(frame: VideoFrameData)
    fun onConnectionLost()
}

/**
 * NDI stream receiver that connects to NDI sources and receives video frames.
 * Uses the native JNI wrapper (NdiNative) for NDI operations.
 * 
 * Thread safety:
 * - receiverPtr is managed with AtomicLong to prevent race conditions
 * - isReceiving is volatile to ensure visibility across threads
 * - cleanup() uses getAndSet(0) to atomically get and clear the pointer
 */
class NdiReceiver {
    companion object {
        private const val TAG = "NdiReceiver"
        private const val RECEIVE_TIMEOUT_MS = 1000
        private const val THREAD_JOIN_TIMEOUT_MS = 3000L
        private const val SYNC_JOIN_TIMEOUT_MS = 500L // Short timeout for sync disconnect
        private const val CONNECTION_LOST_THRESHOLD = 5
    }

    // Use AtomicLong for thread-safe access to receiver pointer
    private val receiverPtrAtomic = AtomicLong(0)
    
    @Volatile
    private var receiveThread: Thread? = null

    @Volatile
    private var isReceiving = false
    
    // Prevent concurrent cleanup operations
    private val cleanupInProgress = AtomicBoolean(false)

    // Tracking for connection lost detection - prevents false positives
    @Volatile
    private var hasReceivedFrame = false
    @Volatile
    private var consecutiveNullFrames = 0

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var frameCallback: NdiFrameCallback? = null
    private var connectedSourceName: String? = null

    /**
     * Set the callback for receiving video frames.
     */
    fun setFrameCallback(callback: NdiFrameCallback?) {
        frameCallback = callback
    }

    /**
     * Connect to an NDI source and start receiving frames.
     */
    suspend fun connect(source: NdiSource) = withContext(Dispatchers.IO) {
        if (!NdiManager.isInitialized()) {
            _connectionState.value = ConnectionState.Error("NDI SDK not initialized")
            return@withContext
        }

        if (isReceiving) {
            disconnect()
        }

        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "Connecting to NDI source: ${source.name}")

        // Reset connection lost tracking for new connection
        hasReceivedFrame = false
        consecutiveNullFrames = 0

        try {
            // Create receiver
            val newPtr = NdiNative.receiverCreate(
                receiverName = "Android NDI Receiver",
                bandwidth = NdiNative.Bandwidth.HIGHEST,
                colorFormat = NdiNative.ColorFormat.BGRX_BGRA,
                allowVideoFields = true
            )

            if (newPtr == 0L) {
                _connectionState.value = ConnectionState.Error("Failed to create receiver")
                return@withContext
            }
            
            receiverPtrAtomic.set(newPtr)

            // Connect to the source
            val connected = NdiNative.receiverConnect(newPtr, source.name)
            if (!connected) {
                Log.w(TAG, "receiverConnect returned false, continuing anyway")
            }

            connectedSourceName = source.name
            _connectionState.value = ConnectionState.Connected(source)
            Log.i(TAG, "Connected to NDI source: ${source.name}")

            startReceiving()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to NDI source", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            cleanup()
        }
    }

    /**
     * Start the receive loop in a background thread.
     */
    private fun startReceiving() {
        isReceiving = true

        receiveThread = Thread({
            Log.d(TAG, "Receive loop started")

            while (isReceiving) {
                val ptr = receiverPtrAtomic.get()
                if (ptr == 0L) {
                    Log.d(TAG, "Receiver pointer is null, exiting receive loop")
                    break
                }
                
                try {
                    // Capture video frame using the current pointer
                    val videoFrame = NdiNative.receiverCaptureVideo(ptr, RECEIVE_TIMEOUT_MS)

                    if (videoFrame != null) {
                        // Reset connection lost tracking - we're receiving frames
                        hasReceivedFrame = true
                        consecutiveNullFrames = 0

                        // Process the video frame
                        val fourCC = FourCC.fromInt(videoFrame.fourCC)
                        val isCompressed = fourCC == FourCC.H264 ||
                                          fourCC == FourCC.HEVC

                        val frameData = VideoFrameData(
                            width = videoFrame.width,
                            height = videoFrame.height,
                            frameRateN = videoFrame.frameRateN,
                            frameRateD = videoFrame.frameRateD,
                            data = videoFrame.data,
                            lineStrideBytes = videoFrame.lineStrideBytes,
                            timestamp = videoFrame.timestamp,
                            fourCC = fourCC,
                            isCompressed = isCompressed
                        )

                        frameCallback?.onVideoFrame(frameData)

                        // Free the frame - check pointer is still valid
                        val currentPtr = receiverPtrAtomic.get()
                        if (currentPtr != 0L) {
                            NdiNative.receiverFreeVideo(currentPtr, videoFrame.nativePtr)
                        }
                    } else {
                        // No frame received - increment counter and check if connection truly lost
                        consecutiveNullFrames++

                        // Only consider connection lost if:
                        // 1. We've exceeded the threshold of consecutive null frames
                        // 2. NDI SDK reports not connected
                        // 3. We were previously receiving frames
                        val currentPtr = receiverPtrAtomic.get()
                        if (isReceiving &&
                            currentPtr != 0L &&
                            consecutiveNullFrames >= CONNECTION_LOST_THRESHOLD &&
                            !NdiNative.receiverIsConnected(currentPtr) &&
                            hasReceivedFrame) {
                            Log.w(TAG, "Connection lost after $consecutiveNullFrames consecutive null frames")
                            frameCallback?.onConnectionLost()
                        }
                    }

                } catch (e: Exception) {
                    if (isReceiving) {
                        Log.e(TAG, "Error in receive loop", e)
                    }
                }
            }

            Log.d(TAG, "Receive loop ended")
        }, "NDI-Receive-Thread")

        receiveThread?.start()
    }

    /**
     * Set an Android Surface for hardware-accelerated video rendering.
     */
    fun setSurface(surface: Surface?): Boolean {
        val ptr = receiverPtrAtomic.get()
        if (ptr == 0L) return false
        return NdiNative.receiverSetSurface(ptr, surface)
    }

    /**
     * Synchronous disconnect for use in onCleared().
     * Waits briefly for receive thread to stop before cleanup.
     */
    fun disconnectSync() {
        if (_connectionState.value == ConnectionState.Disconnected) {
            return
        }

        Log.d(TAG, "Synchronous disconnect initiated")
        
        // Signal receive thread to stop
        isReceiving = false
        
        // Wait briefly for receive thread to notice the flag and exit
        // This prevents cleanup while the thread is still using the receiver
        try {
            receiveThread?.join(SYNC_JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for receive thread")
        }
        
        // Now safe to cleanup - thread should have exited or is about to
        cleanup()
        receiveThread = null
        connectedSourceName = null
        _connectionState.value = ConnectionState.Disconnected
        Log.d(TAG, "Synchronous disconnect completed")
    }

    /**
     * Disconnect from the current NDI source.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        if (_connectionState.value == ConnectionState.Disconnected) {
            return@withContext
        }

        Log.d(TAG, "Disconnecting from NDI source")
        isReceiving = false

        // Wait for receive thread to finish
        try {
            receiveThread?.join(THREAD_JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for receive thread")
        }

        if (receiveThread?.isAlive == true) {
            Log.w(TAG, "Receive thread did not stop in time, interrupting")
            receiveThread?.interrupt()
        }
        receiveThread = null

        cleanup()

        connectedSourceName = null
        _connectionState.value = ConnectionState.Disconnected
        Log.i(TAG, "Disconnected from NDI source")
    }

    /**
     * Clean up resources.
     * Uses AtomicLong.getAndSet(0) to atomically get and clear the pointer,
     * ensuring only one cleanup can run at a time.
     */
    private fun cleanup() {
        // Prevent concurrent cleanup
        if (!cleanupInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Cleanup already in progress, skipping")
            return
        }
        
        try {
            // Atomically get and clear the pointer
            val ptr = receiverPtrAtomic.getAndSet(0)
            if (ptr != 0L) {
                Log.d(TAG, "Destroying NDI receiver")
                NdiNative.receiverDestroy(ptr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        } finally {
            cleanupInProgress.set(false)
        }
    }

    /**
     * Check if currently connected.
     */
    fun isConnected(): Boolean {
        return _connectionState.value is ConnectionState.Connected
    }

    /**
     * Get the currently connected source, if any.
     */
    fun getConnectedSource(): NdiSource? {
        return (_connectionState.value as? ConnectionState.Connected)?.source
    }
}
