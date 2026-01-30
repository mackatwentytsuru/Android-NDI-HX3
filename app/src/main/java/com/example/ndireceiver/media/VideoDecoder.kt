package com.example.ndireceiver.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.example.ndireceiver.ndi.VideoFrameData
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Video decoder using MediaCodec for hardware-accelerated H.264/H.265 decoding.
 * Renders decoded frames directly to a Surface.
 */
class VideoDecoder {
    companion object {
        private const val TAG = "VideoDecoder"
        private const val TIMEOUT_US = 10000L
        private const val MAX_QUEUE_SIZE = 5

        // MIME types
        const val MIME_H264 = "video/avc"
        const val MIME_H265 = "video/hevc"
    }

    private var decoder: MediaCodec? = null
    private var surface: Surface? = null
    private var inputThread: Thread? = null
    private var outputThread: Thread? = null

    @Volatile
    private var isRunning = false

    private val frameQueue = LinkedBlockingQueue<VideoFrameData>(MAX_QUEUE_SIZE)

    private var currentWidth = 0
    private var currentHeight = 0
    private var currentMimeType = MIME_H265

    /**
     * Frame statistics for OSD display.
     */
    data class FrameStats(
        val width: Int,
        val height: Int,
        val frameRate: Float,
        val decodedFrames: Long
    )

    private var decodedFrameCount = 0L
    private var lastFrameRateN = 30
    private var lastFrameRateD = 1

    /**
     * Initialize the decoder with a surface for rendering.
     */
    fun initialize(surface: Surface, width: Int, height: Int, mimeType: String = MIME_H265): Boolean {
        this.surface = surface
        this.currentWidth = width
        this.currentHeight = height
        this.currentMimeType = mimeType

        return try {
            createDecoder(width, height, mimeType)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize decoder", e)
            false
        }
    }

    /**
     * Create and configure the MediaCodec decoder.
     */
    private fun createDecoder(width: Int, height: Int, mimeType: String) {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            // Low latency mode for real-time streaming
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

        decoder = MediaCodec.createDecoderByType(mimeType).apply {
            configure(format, surface, null, 0)
            start()
        }

        Log.i(TAG, "Decoder created: $mimeType ${width}x${height}")
    }

    /**
     * Start the decoder threads.
     */
    fun start() {
        if (isRunning) return

        isRunning = true

        inputThread = Thread({
            processInputBuffers()
        }, "Decoder-Input").apply { start() }

        outputThread = Thread({
            processOutputBuffers()
        }, "Decoder-Output").apply { start() }

        Log.d(TAG, "Decoder started")
    }

    /**
     * Submit a video frame for decoding.
     */
    fun submitFrame(frame: VideoFrameData) {
        if (!isRunning) return

        lastFrameRateN = frame.frameRateN
        lastFrameRateD = frame.frameRateD

        // Drop oldest frame if queue is full
        if (frameQueue.remainingCapacity() == 0) {
            frameQueue.poll()
            Log.w(TAG, "Frame queue full, dropping frame")
        }

        frameQueue.offer(frame)
    }

    /**
     * Process input buffers - feed frames to decoder.
     */
    private fun processInputBuffers() {
        while (isRunning) {
            try {
                val frame = frameQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue

                val inputIndex = decoder?.dequeueInputBuffer(TIMEOUT_US) ?: -1
                if (inputIndex >= 0) {
                    val inputBuffer = decoder?.getInputBuffer(inputIndex) ?: continue

                    inputBuffer.clear()

                    // Copy frame data
                    val data = frame.data
                    data.rewind()
                    inputBuffer.put(data)

                    decoder?.queueInputBuffer(
                        inputIndex,
                        0,
                        data.limit(),
                        frame.timestamp,
                        0
                    )
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Error processing input buffer", e)
                }
            }
        }
    }

    /**
     * Process output buffers - render decoded frames.
     */
    private fun processOutputBuffers() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning) {
            try {
                val outputIndex = decoder?.dequeueOutputBuffer(bufferInfo, TIMEOUT_US) ?: -1

                when {
                    outputIndex >= 0 -> {
                        // Release buffer to surface for rendering
                        decoder?.releaseOutputBuffer(outputIndex, true)
                        decodedFrameCount++
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder?.outputFormat
                        Log.d(TAG, "Output format changed: $newFormat")
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available yet
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Error processing output buffer", e)
                }
            }
        }
    }

    /**
     * Reconfigure decoder for new video dimensions.
     */
    fun reconfigure(width: Int, height: Int, mimeType: String = currentMimeType) {
        if (width == currentWidth && height == currentHeight && mimeType == currentMimeType) {
            return
        }

        Log.d(TAG, "Reconfiguring decoder: ${currentWidth}x${currentHeight} -> ${width}x${height}")

        stop()

        surface?.let { s ->
            currentWidth = width
            currentHeight = height
            currentMimeType = mimeType

            createDecoder(width, height, mimeType)
            start()
        }
    }

    /**
     * Get current frame statistics.
     */
    fun getFrameStats(): FrameStats {
        return FrameStats(
            width = currentWidth,
            height = currentHeight,
            frameRate = if (lastFrameRateD > 0) lastFrameRateN.toFloat() / lastFrameRateD else 0f,
            decodedFrames = decodedFrameCount
        )
    }

    /**
     * Stop the decoder threads.
     */
    fun stop() {
        isRunning = false

        // Interrupt threads to unblock any waiting operations
        inputThread?.interrupt()
        outputThread?.interrupt()

        // Wait for threads with timeout
        try {
            inputThread?.join(2000)
            if (inputThread?.isAlive == true) {
                Log.w(TAG, "Input thread did not stop in time")
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for input thread")
        }

        try {
            outputThread?.join(2000)
            if (outputThread?.isAlive == true) {
                Log.w(TAG, "Output thread did not stop in time")
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for output thread")
        }

        inputThread = null
        outputThread = null

        frameQueue.clear()

        Log.d(TAG, "Decoder stopped")
    }

    /**
     * Release all decoder resources.
     */
    fun release() {
        stop()

        try {
            decoder?.stop()
            decoder?.release()
            decoder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing decoder", e)
        }

        surface = null
        decodedFrameCount = 0

        Log.i(TAG, "Decoder released")
    }
}
