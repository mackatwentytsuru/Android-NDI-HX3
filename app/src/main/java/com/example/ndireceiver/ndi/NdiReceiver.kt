package com.example.ndireceiver.ndi

import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

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
    val fourCC: Int,
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
 */
class NdiReceiver {
    companion object {
        private const val TAG = "NdiReceiver"
        private const val RECEIVE_TIMEOUT_MS = 1000
        private const val THREAD_JOIN_TIMEOUT_MS = 3000L
        private const val CONNECTION_LOST_THRESHOLD = 5 // Number of consecutive null frames before considering connection lost
    }

    private var receiverPtr: Long = 0
    private var receiveThread: Thread? = null

    @Volatile
    private var isReceiving = false

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
            receiverPtr = NdiNative.receiverCreate(
                receiverName = "Android NDI Receiver",
                bandwidth = NdiNative.Bandwidth.HIGHEST,
                colorFormat = NdiNative.ColorFormat.BGRX_BGRA,
                allowVideoFields = true
            )

            if (receiverPtr == 0L) {
                _connectionState.value = ConnectionState.Error("Failed to create receiver")
                return@withContext
            }

            // Connect to the source
            val connected = NdiNative.receiverConnect(receiverPtr, source.name)
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
                try {
                    // Capture video frame
                    val videoFrame = NdiNative.receiverCaptureVideo(receiverPtr, RECEIVE_TIMEOUT_MS)

                    if (videoFrame != null) {
                        // Reset connection lost tracking - we're receiving frames
                        hasReceivedFrame = true
                        consecutiveNullFrames = 0

                        // Process the video frame
                        val fourCC = videoFrame.fourCC
                        val isCompressed = fourCC == NdiNative.FourCC.H264 ||
                                          fourCC == NdiNative.FourCC.HEVC

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

                        // Free the frame
                        NdiNative.receiverFreeVideo(receiverPtr, videoFrame.nativePtr)
                    } else {
                        // No frame received - increment counter and check if connection truly lost
                        consecutiveNullFrames++

                        // Only consider connection lost if:
                        // 1. We've exceeded the threshold of consecutive null frames
                        // 2. NDI SDK reports not connected
                        // 3. We were previously receiving frames (prevents false positive during initial connection)
                        if (isReceiving &&
                            consecutiveNullFrames >= CONNECTION_LOST_THRESHOLD &&
                            !NdiNative.receiverIsConnected(receiverPtr) &&
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
        if (receiverPtr == 0L) return false
        return NdiNative.receiverSetSurface(receiverPtr, surface)
    }

    /**
     * Synchronous non-blocking disconnect for use in onCleared().
     * Does not join the receive thread to avoid blocking the main thread.
     */
    fun disconnectSync() {
        if (_connectionState.value == ConnectionState.Disconnected) {
            return
        }

        Log.d(TAG, "Synchronous disconnect initiated")
        isReceiving = false
        // Do NOT join thread - let it finish on its own to avoid blocking main thread
        // The thread checks isReceiving flag and will exit gracefully
        cleanup()
        receiveThread = null
        connectedSourceName = null
        _connectionState.value = ConnectionState.Disconnected
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
     */
    private fun cleanup() {
        try {
            if (receiverPtr != 0L) {
                NdiNative.receiverDestroy(receiverPtr)
                receiverPtr = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
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
