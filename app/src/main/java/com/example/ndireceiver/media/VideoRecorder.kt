package com.example.ndireceiver.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.example.ndireceiver.ndi.VideoFrameData
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * VideoRecorder: MediaMuxer wrapper for H.264/H.265 passthrough recording.
 *
 * Records compressed video frames directly to MP4 without re-encoding,
 * achieving zero CPU overhead for the encoding process.
 *
 * Key implementation details:
 * - Extracts CSD (SPS/PPS for H.264, VPS/SPS/PPS for H.265) from NAL units
 * - Detects keyframes from NAL unit types
 * - Uses relative timestamps starting from 0
 * - Writes frames on a background thread for non-blocking operation
 */
class VideoRecorder(private val outputDir: File) {

    companion object {
        private const val TAG = "VideoRecorder"
        private const val WRITE_QUEUE_SIZE = 30
        private const val NAL_START_CODE = 0x00000001

        // H.264 NAL unit types
        private const val H264_NAL_TYPE_MASK = 0x1F
        private const val H264_NAL_IDR = 5        // IDR frame (keyframe)
        private const val H264_NAL_SPS = 7        // Sequence Parameter Set
        private const val H264_NAL_PPS = 8        // Picture Parameter Set

        // H.265 NAL unit types
        private const val H265_NAL_TYPE_MASK = 0x3F
        private const val H265_NAL_IDR_W_RADL = 19  // IDR with RADL pictures
        private const val H265_NAL_IDR_N_LP = 20    // IDR without leading pictures
        private const val H265_NAL_VPS = 32         // Video Parameter Set
        private const val H265_NAL_SPS = 33         // Sequence Parameter Set
        private const val H265_NAL_PPS = 34         // Picture Parameter Set
    }

    /**
     * Recording state for external observation.
     */
    sealed class RecordingState {
        object Idle : RecordingState()
        data class Recording(val file: File, val durationMs: Long) : RecordingState()
        data class Error(val message: String) : RecordingState()
    }

    private var muxer: MediaMuxer? = null
    private var videoTrackIndex: Int = -1
    private var isMuxerStarted = false

    // Use AtomicBoolean for proper thread-safe recording state management
    // This prevents race conditions when checking/setting recording state
    private val isRecordingFlag = AtomicBoolean(false)

    private var outputFile: File? = null
    private var startTimeUs: Long = -1
    private var isHevc = false
    private var videoWidth = 0
    private var videoHeight = 0

    // CSD data storage
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null  // Only for H.265
    private var csdExtracted = false

    // Background write thread
    private var writeThread: Thread? = null
    private val writeQueue = LinkedBlockingQueue<FrameToWrite>(WRITE_QUEUE_SIZE)

    // Recording duration tracking
    private val recordingStartTime = AtomicLong(0)

    /**
     * Internal frame data for write queue.
     */
    private data class FrameToWrite(
        val data: ByteBuffer,
        val presentationTimeUs: Long,
        val isKeyFrame: Boolean
    )

    /**
     * Check if currently recording.
     */
    fun isRecording(): Boolean = isRecordingFlag.get()

    /**
     * Get the current recording duration in milliseconds.
     */
    fun getRecordingDurationMs(): Long {
        val startTime = recordingStartTime.get()
        return if (startTime > 0) {
            System.currentTimeMillis() - startTime
        } else {
            0
        }
    }

    /**
     * Get the current output file, if recording.
     */
    fun getOutputFile(): File? = outputFile

    /**
     * Start recording. The actual muxer will be initialized on first keyframe
     * to ensure we have proper CSD data.
     *
     * @param width Video width
     * @param height Video height
     * @param isHevc True for H.265/HEVC, false for H.264/AVC
     * @return The output file that will be created
     */
    fun startRecording(width: Int, height: Int, isHevc: Boolean): File {
        // Use compareAndSet for atomic state transition
        if (!isRecordingFlag.compareAndSet(false, true)) {
            throw IllegalStateException("Already recording")
        }

        // Create output directory if needed
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        // Generate filename with timestamp
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        val codec = if (isHevc) "H265" else "H264"
        outputFile = File(outputDir, "NDI_${timestamp}_${codec}.mp4")

        this.isHevc = isHevc
        this.videoWidth = width
        this.videoHeight = height
        this.csdExtracted = false
        this.sps = null
        this.pps = null
        this.vps = null
        this.startTimeUs = -1
        this.videoTrackIndex = -1
        this.isMuxerStarted = false

        // isRecordingFlag already set to true via compareAndSet above
        recordingStartTime.set(System.currentTimeMillis())

        // Start write thread
        writeThread = Thread({
            writeLoop()
        }, "VideoRecorder-Write").apply { start() }

        Log.i(TAG, "Recording started: ${outputFile?.absolutePath}")
        return outputFile!!
    }

    /**
     * Write a video frame. Must be called with compressed H.264/H.265 data.
     *
     * @param frame The video frame data from NDI
     */
    fun writeFrame(frame: VideoFrameData) {
        // Use atomic get for thread-safe check
        if (!isRecordingFlag.get()) return

        // CRITICAL FIX: Create a defensive copy of the ByteBuffer
        // The original frame.data may be read simultaneously by VideoDecoder,
        // so we must NOT call rewind() or any position-modifying methods on it.
        // Instead, duplicate the buffer (creates independent position/limit)
        // and then copy the data.
        val originalData = frame.data
        val dataDuplicate = originalData.duplicate()
        dataDuplicate.rewind()

        // Copy data for async processing
        val dataCopy = ByteArray(dataDuplicate.remaining())
        dataDuplicate.get(dataCopy)

        val timestampUs = frame.timestamp

        // Queue frame for writing
        val frameToWrite = FrameToWrite(
            data = ByteBuffer.wrap(dataCopy),
            presentationTimeUs = timestampUs,
            isKeyFrame = false  // Will be determined in write loop
        )

        if (!writeQueue.offer(frameToWrite)) {
            Log.w(TAG, "Write queue full, dropping frame")
        }
    }

    /**
     * Background write loop.
     */
    private fun writeLoop() {
        Log.d(TAG, "Write loop started")

        // Use atomic get for thread-safe state checking
        // Continue processing while recording OR while queue has pending frames
        // This ensures no frames are lost at the stop boundary
        while (isRecordingFlag.get() || writeQueue.isNotEmpty()) {
            try {
                val frame = writeQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                processFrame(frame.data.array(), frame.presentationTimeUs)
            } catch (e: InterruptedException) {
                // Only log warning if we're still supposed to be recording
                if (isRecordingFlag.get()) {
                    Log.w(TAG, "Write loop interrupted while still recording")
                }
                // Don't break immediately - drain remaining frames from queue
                while (writeQueue.isNotEmpty()) {
                    try {
                        val remainingFrame = writeQueue.poll() ?: break
                        processFrame(remainingFrame.data.array(), remainingFrame.presentationTimeUs)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Error processing remaining frame", e2)
                    }
                }
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in write loop", e)
            }
        }

        Log.d(TAG, "Write loop ended")
    }

    /**
     * Process and write a frame.
     */
    private fun processFrame(data: ByteArray, timestampUs: Long) {
        // Parse NAL units to extract CSD and detect keyframes
        val nalUnits = parseNalUnits(data)

        if (nalUnits.isEmpty()) {
            Log.w(TAG, "No NAL units found in frame")
            return
        }

        // Extract CSD if not yet done
        if (!csdExtracted) {
            extractCsd(nalUnits)

            // If we have CSD, initialize muxer
            if (hasCsd()) {
                initializeMuxer()
                csdExtracted = true
            } else {
                // Skip frames until we get CSD
                Log.d(TAG, "Waiting for CSD data...")
                return
            }
        }

        if (!isMuxerStarted || muxer == null) {
            return
        }

        // Calculate relative timestamp
        if (startTimeUs < 0) {
            startTimeUs = timestampUs
        }
        val presentationTimeUs = timestampUs - startTimeUs

        // Detect if this is a keyframe
        val isKeyFrame = containsKeyFrame(nalUnits)

        // Write to muxer
        val buffer = ByteBuffer.wrap(data)
        val bufferInfo = MediaCodec.BufferInfo().apply {
            offset = 0
            size = data.size
            this.presentationTimeUs = presentationTimeUs
            flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        }

        try {
            muxer?.writeSampleData(videoTrackIndex, buffer, bufferInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing sample data", e)
        }
    }

    /**
     * Parse NAL units from Annex-B byte stream.
     * Returns list of pairs: (NAL type, NAL data including start code)
     */
    private fun parseNalUnits(data: ByteArray): List<Pair<Int, ByteArray>> {
        val nalUnits = mutableListOf<Pair<Int, ByteArray>>()
        var pos = 0

        // CRITICAL FIX: Use data.size - 3 to ensure we can read at least a 3-byte start code
        // The original data.size - 4 was an off-by-one error that could miss the last NAL unit
        while (pos < data.size - 3) {
            // Find start code (0x00000001 or 0x000001)
            val startCodePos = findStartCode(data, pos)
            if (startCodePos < 0) break

            // CRITICAL FIX: Proper 4-byte start code detection
            // 1. Ensure startCodePos > 0 (not >= 1, but > 0) to safely access startCodePos - 1
            // 2. Use 'and 0xFF' to handle signed byte comparison correctly (byte 0x00 == 0)
            // The 4-byte start code is 0x00 00 00 01, so we check if the byte before 0x00 00 01 is also 0x00
            val is4ByteStartCode = startCodePos > 0 && (data[startCodePos - 1].toInt() and 0xFF) == 0
            val startCodeLen = if (is4ByteStartCode) 4 else 3
            val nalStart = if (is4ByteStartCode) startCodePos - 1 else startCodePos
            val nalDataStart = startCodePos + 3  // After 0x000001

            // Ensure we have at least one byte of NAL data
            if (nalDataStart >= data.size) break

            // Find next start code to determine NAL end
            var nalEnd = data.size
            for (i in nalDataStart until data.size - 2) {
                if ((data[i].toInt() and 0xFF) == 0 && (data[i + 1].toInt() and 0xFF) == 0 &&
                    ((data[i + 2].toInt() and 0xFF) == 1 ||
                     (i + 3 < data.size && (data[i + 2].toInt() and 0xFF) == 0 && (data[i + 3].toInt() and 0xFF) == 1))) {
                    nalEnd = i
                    break
                }
            }

            // Get NAL type
            val nalType = if (isHevc) {
                (data[nalDataStart].toInt() shr 1) and H265_NAL_TYPE_MASK
            } else {
                data[nalDataStart].toInt() and H264_NAL_TYPE_MASK
            }

            val nalData = data.copyOfRange(nalStart, nalEnd)
            nalUnits.add(Pair(nalType, nalData))

            pos = nalEnd
        }

        return nalUnits
    }

    /**
     * Find the position of 0x000001 start code.
     * Uses 'and 0xFF' for proper unsigned byte comparison.
     */
    private fun findStartCode(data: ByteArray, startPos: Int): Int {
        for (i in startPos until data.size - 2) {
            if ((data[i].toInt() and 0xFF) == 0 &&
                (data[i + 1].toInt() and 0xFF) == 0 &&
                (data[i + 2].toInt() and 0xFF) == 1) {
                return i
            }
        }
        return -1
    }

    /**
     * Extract CSD (SPS/PPS/VPS) from NAL units.
     */
    private fun extractCsd(nalUnits: List<Pair<Int, ByteArray>>) {
        for ((nalType, nalData) in nalUnits) {
            if (isHevc) {
                when (nalType) {
                    H265_NAL_VPS -> {
                        if (vps == null) {
                            vps = nalData
                            Log.d(TAG, "Extracted VPS: ${nalData.size} bytes")
                        }
                    }
                    H265_NAL_SPS -> {
                        if (sps == null) {
                            sps = nalData
                            Log.d(TAG, "Extracted SPS: ${nalData.size} bytes")
                        }
                    }
                    H265_NAL_PPS -> {
                        if (pps == null) {
                            pps = nalData
                            Log.d(TAG, "Extracted PPS: ${nalData.size} bytes")
                        }
                    }
                }
            } else {
                when (nalType) {
                    H264_NAL_SPS -> {
                        if (sps == null) {
                            sps = nalData
                            Log.d(TAG, "Extracted SPS: ${nalData.size} bytes")
                        }
                    }
                    H264_NAL_PPS -> {
                        if (pps == null) {
                            pps = nalData
                            Log.d(TAG, "Extracted PPS: ${nalData.size} bytes")
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if we have all required CSD data.
     */
    private fun hasCsd(): Boolean {
        return if (isHevc) {
            vps != null && sps != null && pps != null
        } else {
            sps != null && pps != null
        }
    }

    /**
     * Check if NAL units contain a keyframe.
     */
    private fun containsKeyFrame(nalUnits: List<Pair<Int, ByteArray>>): Boolean {
        for ((nalType, _) in nalUnits) {
            if (isHevc) {
                if (nalType == H265_NAL_IDR_W_RADL || nalType == H265_NAL_IDR_N_LP) {
                    return true
                }
            } else {
                if (nalType == H264_NAL_IDR) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Initialize MediaMuxer with CSD data.
     */
    private fun initializeMuxer() {
        val file = outputFile ?: throw IllegalStateException("Output file not set")

        try {
            muxer = MediaMuxer(
                file.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            // Create MediaFormat with CSD
            val mimeType = if (isHevc) {
                MediaFormat.MIMETYPE_VIDEO_HEVC
            } else {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }

            val format = MediaFormat.createVideoFormat(mimeType, videoWidth, videoHeight)

            if (isHevc) {
                // For H.265, csd-0 contains VPS + SPS + PPS concatenated
                val csd0 = ByteBuffer.allocate(
                    (vps?.size ?: 0) + (sps?.size ?: 0) + (pps?.size ?: 0)
                )
                vps?.let { csd0.put(it) }
                sps?.let { csd0.put(it) }
                pps?.let { csd0.put(it) }
                csd0.flip()
                format.setByteBuffer("csd-0", csd0)
                Log.d(TAG, "Set H.265 csd-0: ${csd0.remaining()} bytes")
            } else {
                // For H.264, csd-0 is SPS, csd-1 is PPS
                sps?.let {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(it))
                    Log.d(TAG, "Set H.264 csd-0 (SPS): ${it.size} bytes")
                }
                pps?.let {
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(it))
                    Log.d(TAG, "Set H.264 csd-1 (PPS): ${it.size} bytes")
                }
            }

            videoTrackIndex = muxer!!.addTrack(format)
            muxer!!.start()
            isMuxerStarted = true

            Log.i(TAG, "MediaMuxer initialized: $mimeType ${videoWidth}x${videoHeight}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaMuxer", e)
            cleanup()
            throw e
        }
    }

    /**
     * Stop recording and finalize the MP4 file.
     */
    fun stopRecording(): File? {
        // Use compareAndSet for atomic state transition - prevents double-stop
        if (!isRecordingFlag.compareAndSet(true, false)) {
            return null
        }

        Log.d(TAG, "Stopping recording...")

        // Wait for write thread to finish
        writeThread?.interrupt()
        try {
            writeThread?.join(3000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for write thread")
        }
        writeThread = null

        val file = outputFile
        val duration = getRecordingDurationMs()

        cleanup()

        Log.i(TAG, "Recording stopped: ${file?.absolutePath}, duration: ${duration}ms")
        return file
    }

    /**
     * Clean up resources.
     */
    private fun cleanup() {
        try {
            if (isMuxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }

        muxer = null
        isMuxerStarted = false
        videoTrackIndex = -1
        recordingStartTime.set(0)
        writeQueue.clear()

        sps = null
        pps = null
        vps = null
        csdExtracted = false
    }

    /**
     * Release all resources. Call when the recorder is no longer needed.
     */
    fun release() {
        if (isRecordingFlag.get()) {
            stopRecording()
        }
        cleanup()
        Log.d(TAG, "VideoRecorder released")
    }
}
