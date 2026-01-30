package com.example.ndireceiver.ndi

import android.view.Surface
import java.nio.ByteBuffer

/**
 * JNI interface for the official NDI SDK.
 *
 * This class provides Kotlin bindings to the native NDI SDK functions
 * through JNI. The native implementation is in ndi_wrapper.cpp.
 *
 * Usage:
 * 1. Download the official NDI SDK from https://ndi.video/
 * 2. Place libndi.so in app/src/main/jniLibs/arm64-v8a/ and/or armeabi-v7a/
 * 3. Call NdiNative.initialize() before using any other functions
 *
 * Thread Safety:
 * - initialize() and destroy() must be called from the main thread
 * - Finder operations are thread-safe
 * - Receiver operations should be called from a single thread
 */
object NdiNative {

    /**
     * Load the native libraries.
     * Must be called before any other native method.
     */
    init {
        try {
            // Load the NDI SDK library first (dependency)
            System.loadLibrary("ndi")
            // Load our JNI wrapper
            System.loadLibrary("ndi_wrapper")
        } catch (e: UnsatisfiedLinkError) {
            throw RuntimeException(
                "Failed to load NDI native libraries. " +
                "Make sure libndi.so is placed in jniLibs/arm64-v8a/ or jniLibs/armeabi-v7a/",
                e
            )
        }
    }

    // ============================================================
    // NDI Library Initialization
    // ============================================================

    /**
     * Initialize the NDI SDK.
     * Must be called once before any other NDI operations.
     *
     * @return true if initialization was successful, false otherwise
     */
    external fun initialize(): Boolean

    /**
     * Destroy/cleanup the NDI SDK.
     * Call this when the app is shutting down.
     */
    external fun destroy()

    /**
     * Check if the NDI SDK is initialized.
     *
     * @return true if initialized, false otherwise
     */
    external fun isInitialized(): Boolean

    /**
     * Get the NDI SDK version string.
     *
     * @return version string (e.g., "5.6.0")
     */
    external fun getVersion(): String

    // ============================================================
    // NDI Source Discovery (Finder)
    // ============================================================

    /**
     * Create a new NDI finder instance for source discovery.
     *
     * @param showLocalSources whether to include sources on the local machine
     * @param groups comma-separated list of groups to search (empty for all)
     * @param extraIps comma-separated list of extra IP addresses to search
     * @return native pointer to finder instance, or 0 on failure
     */
    external fun finderCreate(
        showLocalSources: Boolean,
        groups: String,
        extraIps: String
    ): Long

    /**
     * Destroy a finder instance and release resources.
     *
     * @param finderPtr native pointer from finderCreate()
     */
    external fun finderDestroy(finderPtr: Long)

    /**
     * Wait for sources to change.
     * Blocks until sources change or timeout occurs.
     *
     * @param finderPtr native pointer from finderCreate()
     * @param timeoutMs timeout in milliseconds (0 = no wait)
     * @return true if sources changed, false on timeout
     */
    external fun finderWaitForSources(finderPtr: Long, timeoutMs: Int): Boolean

    /**
     * Get the current list of discovered NDI sources.
     *
     * @param finderPtr native pointer from finderCreate()
     * @return array of source names in format "SourceName (MachineName)"
     */
    external fun finderGetSources(finderPtr: Long): Array<String>

    // ============================================================
    // NDI Receiver
    // ============================================================

    /**
     * Create a new NDI receiver instance.
     *
     * @param receiverName name to identify this receiver on the network
     * @param bandwidth bandwidth mode: 0=metadata only, 1=audio only, 2=lowest, 3=highest
     * @param colorFormat color format: 0=BGRX/BGRA, 1=UYVY/BGRA, 2=RGBX/RGBA, 3=UYVY/RGBA
     * @param allowVideoFields whether to allow interlaced video
     * @return native pointer to receiver instance, or 0 on failure
     */
    external fun receiverCreate(
        receiverName: String,
        bandwidth: Int,
        colorFormat: Int,
        allowVideoFields: Boolean
    ): Long

    /**
     * Destroy a receiver instance and release resources.
     *
     * @param receiverPtr native pointer from receiverCreate()
     */
    external fun receiverDestroy(receiverPtr: Long)

    /**
     * Connect the receiver to an NDI source.
     *
     * @param receiverPtr native pointer from receiverCreate()
     * @param sourceName source name (from finderGetSources)
     * @return true if connection initiated successfully
     */
    external fun receiverConnect(receiverPtr: Long, sourceName: String): Boolean

    /**
     * Disconnect the receiver from current source.
     *
     * @param receiverPtr native pointer from receiverCreate()
     */
    external fun receiverDisconnect(receiverPtr: Long)

    /**
     * Capture a video frame from the connected source.
     *
     * @param receiverPtr native pointer from receiverCreate()
     * @param timeoutMs timeout in milliseconds
     * @return VideoFrame object with frame data, or null if no frame/timeout
     */
    external fun receiverCaptureVideo(receiverPtr: Long, timeoutMs: Int): VideoFrame?

    /**
     * Free a captured video frame.
     * Must be called after processing each frame from receiverCaptureVideo().
     *
     * @param receiverPtr native pointer from receiverCreate()
     * @param framePtr native pointer from VideoFrame.nativePtr
     */
    external fun receiverFreeVideo(receiverPtr: Long, framePtr: Long)

    /**
     * Capture an audio frame from the connected source.
     *
     * @param receiverPtr native pointer from receiverCreate()
     * @param timeoutMs timeout in milliseconds
     * @return AudioFrame object with audio data, or null if no frame/timeout
     */
    external fun receiverCaptureAudio(receiverPtr: Long, timeoutMs: Int): AudioFrame?

    /**
     * Free a captured audio frame.
     *
     * @param receiverPtr native pointer from receiverCreate()
     * @param framePtr native pointer from AudioFrame.nativePtr
     */
    external fun receiverFreeAudio(receiverPtr: Long, framePtr: Long)

    /**
     * Get connection status and performance metrics.
     *
     * @param receiverPtr native pointer from receiverCreate()
     * @return ReceiverPerformance with current stats, or null on error
     */
    external fun receiverGetPerformance(receiverPtr: Long): ReceiverPerformance?

    /**
     * Check if receiver is currently connected to a source.
     *
     * @param receiverPtr native pointer from receiverCreate()
     * @return true if connected, false otherwise
     */
    external fun receiverIsConnected(receiverPtr: Long): Boolean

    // ============================================================
    // Hardware Decoding Support
    // ============================================================

    /**
     * Set the Android Surface for hardware-accelerated video rendering.
     * When set, compressed video (H.264/H.265) will be decoded directly to this surface.
     *
     * @param receiverPtr native pointer from receiverCreate()
     * @param surface Android Surface for rendering, or null to disable
     * @return true if surface was set successfully
     */
    external fun receiverSetSurface(receiverPtr: Long, surface: Surface?): Boolean

    // ============================================================
    // Data Classes for JNI Return Types
    // ============================================================

    /**
     * Video frame data from NDI source.
     *
     * @property nativePtr native pointer to the frame (for freeing)
     * @property width frame width in pixels
     * @property height frame height in pixels
     * @property lineStrideBytes bytes per line (may include padding)
     * @property frameRateN frame rate numerator
     * @property frameRateD frame rate denominator
     * @property fourCC pixel format FourCC code
     * @property timestamp NDI timestamp
     * @property data pixel data buffer (direct ByteBuffer)
     * @property isProgressive true if progressive, false if interlaced
     */
    data class VideoFrame(
        val nativePtr: Long,
        val width: Int,
        val height: Int,
        val lineStrideBytes: Int,
        val frameRateN: Int,
        val frameRateD: Int,
        val fourCC: Int,
        val timestamp: Long,
        val data: ByteBuffer,
        val isProgressive: Boolean
    ) {
        val frameRate: Float
            get() = if (frameRateD != 0) frameRateN.toFloat() / frameRateD else 0f

        val dataSize: Int
            get() = data.remaining()
    }

    /**
     * Audio frame data from NDI source.
     *
     * @property nativePtr native pointer to the frame (for freeing)
     * @property sampleRate audio sample rate in Hz
     * @property numChannels number of audio channels
     * @property numSamples number of samples per channel
     * @property timestamp NDI timestamp
     * @property data interleaved float audio samples (direct ByteBuffer)
     */
    data class AudioFrame(
        val nativePtr: Long,
        val sampleRate: Int,
        val numChannels: Int,
        val numSamples: Int,
        val timestamp: Long,
        val data: ByteBuffer
    )

    /**
     * Receiver performance metrics.
     *
     * @property videoFramesTotal total video frames received
     * @property videoFramesDropped video frames dropped
     * @property audioFramesTotal total audio frames received
     * @property audioFramesDropped audio frames dropped
     * @property metadataFramesTotal total metadata frames received
     * @property quality connection quality (0-100)
     */
    data class ReceiverPerformance(
        val videoFramesTotal: Long,
        val videoFramesDropped: Long,
        val audioFramesTotal: Long,
        val audioFramesDropped: Long,
        val metadataFramesTotal: Long,
        val quality: Int
    ) {
        val videoDropRate: Float
            get() = if (videoFramesTotal > 0) {
                (videoFramesDropped.toFloat() / videoFramesTotal) * 100f
            } else 0f
    }

    // ============================================================
    // Constants
    // ============================================================

    object Bandwidth {
        const val METADATA_ONLY = 0
        const val AUDIO_ONLY = 1
        const val LOWEST = 2
        const val HIGHEST = 3
    }

    object ColorFormat {
        const val BGRX_BGRA = 0
        const val UYVY_BGRA = 1
        const val RGBX_RGBA = 2
        const val UYVY_RGBA = 3
        const val FASTEST = 100  // Let NDI choose fastest format
        const val BEST = 101     // Let NDI choose best quality format
    }

    object FourCC {
        const val UYVY = 0x59565955  // 'UYVY' - YUV 4:2:2
        const val BGRA = 0x41524742  // 'BGRA' - 32-bit BGRA
        const val BGRX = 0x58524742  // 'BGRX' - 32-bit BGR (no alpha)
        const val RGBA = 0x41424752  // 'RGBA' - 32-bit RGBA
        const val RGBX = 0x58424752  // 'RGBX' - 32-bit RGB (no alpha)
        const val NV12 = 0x3231564E  // 'NV12' - YUV 4:2:0 planar
        const val I420 = 0x30323449  // 'I420' - YUV 4:2:0 planar
        const val H264 = 0x34363248  // 'H264' - Compressed H.264
        const val HEVC = 0x43564548  // 'HEVC' - Compressed H.265
    }
}
