package com.example.ndireceiver.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.example.ndireceiver.ndi.FourCC
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
 * VideoRecorder: A versatile video recorder for Android that handles both
 * compressed (H.264/H.265) and uncompressed (UYVY, etc.) NDI video frames.
 *
 * For compressed frames, it acts as a passthrough muxer, writing the data
 * directly to an MP4 container with minimal overhead.
 *
 * For uncompressed frames, it implements a full encoding pipeline:
 * 1. Color space conversion (e.g., UYVY to NV12) using `ColorSpaceConverter`.
 * 2. Real-time H.264 encoding via `UncompressedVideoEncoder`.
 * 3. Muxing the encoded frames into an MP4 file.
 *
 * All I/O, conversion, and encoding operations are performed on a dedicated
 * background thread to prevent blocking the main NDI receiver thread.
 */
class VideoRecorder(
    private val outputDir: File,
    private val encoderFactory: (width: Int, height: Int, bitRate: Int, outputFile: File) -> VideoEncoder =
        { width, height, bitRate, outputFile -> UncompressedVideoEncoder(width, height, bitRate, outputFile) }
) {

    companion object {
        private const val TAG = "VideoRecorder"
        private const val WRITE_QUEUE_SIZE = 30
        private const val BITRATE_1080P = 8 * 1024 * 1024 // 8 Mbps

        // H.264 NAL unit types
        private const val H264_NAL_TYPE_MASK = 0x1F
        private const val H264_NAL_IDR = 5
        private const val H264_NAL_SPS = 7
        private const val H264_NAL_PPS = 8

        // H.265 NAL unit types
        private const val H265_NAL_TYPE_MASK = 0x3F
        private const val H265_NAL_IDR_W_RADL = 19
        private const val H265_NAL_IDR_N_LP = 20
        private const val H265_NAL_VPS = 32
        private const val H265_NAL_SPS = 33
        private const val H265_NAL_PPS = 34
    }

    sealed class RecordingState {
        object Idle : RecordingState()
        data class Recording(val file: File, val durationMs: Long) : RecordingState()
        data class Error(val message: String) : RecordingState()
    }

    // Passthrough muxer for compressed streams
    private var passthroughMuxer: MediaMuxer? = null
    private var videoTrackIndex: Int = -1
    private var isMuxerStarted = false

    // Encoder for uncompressed streams
    private var uncompressedEncoder: VideoEncoder? = null
    private var isEncoding = false

    private val isRecordingFlag = AtomicBoolean(false)
    private var outputFile: File? = null
    private var startTimeUs: Long = -1

    // Compressed stream properties
    private var isHevc = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null
    private var csdExtracted = false

    // Uncompressed stream properties
    private var frameFourCC: FourCC = FourCC.UNKNOWN

    // Background processing
    private var writeThread: Thread? = null
    private val writeQueue = LinkedBlockingQueue<VideoFrameData>(WRITE_QUEUE_SIZE)
    private val recordingStartTime = AtomicLong(0)


    fun isRecording(): Boolean = isRecordingFlag.get()

    fun getRecordingDurationMs(): Long {
        val startTime = recordingStartTime.get()
        return if (startTime > 0) System.currentTimeMillis() - startTime else 0
    }

    fun getOutputFile(): File? = outputFile

    /**
     * Start recording for compressed video streams (H.264/H.265).
     */
    fun startRecording(width: Int, height: Int, isHevc: Boolean): File {
        this.isEncoding = false
        this.frameFourCC = if (isHevc) FourCC.HEVC else FourCC.H264
        return commonStart(width, height)
    }

    /**
     * Start recording for uncompressed video streams.
     */
    fun startRecording(width: Int, height: Int, fourCC: FourCC): File {
        this.isEncoding = true
        this.frameFourCC = fourCC
        return commonStart(width, height)
    }

    private fun commonStart(width: Int, height: Int): File {
        if (!isRecordingFlag.compareAndSet(false, true)) {
            throw IllegalStateException("Already recording")
        }

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val codec = if (isEncoding) "H264_from_${frameFourCC.name}" else if (isHevc) "H265" else "H264"
        outputFile = File(outputDir, "NDI_${timestamp}_${width}x${height}_${codec}.mp4")

        this.videoWidth = width
        this.videoHeight = height
        this.startTimeUs = -1

        if (isEncoding) {
            // Setup for encoding uncompressed frames
            uncompressedEncoder = encoderFactory(width, height, BITRATE_1080P, outputFile!!)
        } else {
            // Setup for passthrough of compressed frames
            this.videoTrackIndex = -1
            this.isMuxerStarted = false
            this.csdExtracted = false
            this.sps = null
            this.pps = null
            this.vps = null
        }

        recordingStartTime.set(System.currentTimeMillis())
        writeThread = Thread({ writeLoop() }, "VideoRecorder-Write").apply { start() }

        Log.i(TAG, "Recording started: ${outputFile?.absolutePath}")
        return outputFile!!
    }

    fun writeFrame(frame: VideoFrameData) {
        if (!isRecordingFlag.get()) return

        // For uncompressed streams, the data will be processed on the write thread.
        // For compressed streams, we copy the data to avoid issues with the buffer being reused.
        val dataCopy = if (!isEncoding) {
            val originalData = frame.data
            val dataDuplicate = originalData.duplicate()
            dataDuplicate.rewind()
            val bytes = ByteArray(dataDuplicate.remaining())
            dataDuplicate.get(bytes)
            ByteBuffer.wrap(bytes)
        } else {
            frame.data // Pass original buffer, will be converted on write thread
        }

        val frameToWrite = frame.copy(data = dataCopy)

        if (!writeQueue.offer(frameToWrite, 200, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Write queue full, dropping frame")
        }
    }

    private fun writeLoop() {
        Log.d(TAG, "Write loop started. Mode: ${if (isEncoding) "Encoding" else "Passthrough"}")

        while (isRecordingFlag.get() || writeQueue.isNotEmpty()) {
            try {
                val frame = writeQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (startTimeUs < 0) {
                    startTimeUs = frame.timestamp
                }
                val presentationTimeUs = frame.timestamp - startTimeUs

                if (isEncoding) {
                    processFrameForEncoding(frame, presentationTimeUs)
                } else {
                    processFrameForPassthrough(frame, presentationTimeUs)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in write loop", e)
            }
        }
        Log.d(TAG, "Write loop finished.")
    }

    private fun processFrameForEncoding(frame: VideoFrameData, presentationTimeUs: Long) {
        val nv12Bytes = ColorSpaceConverter.convert(
            frame.data,
            frameFourCC,
            videoWidth,
            videoHeight,
            frame.lineStrideBytes
        )

        if (nv12Bytes != null) {
            try {
                uncompressedEncoder?.encodeFrame(nv12Bytes, presentationTimeUs)
            } catch (e: Exception) {
                Log.e(TAG, "Encoding failed for frame at $presentationTimeUs", e)
            }
        } else {
            Log.w(TAG, "Color conversion failed for frame. FourCC: $frameFourCC")
        }
    }

    private fun processFrameForPassthrough(frame: VideoFrameData, presentationTimeUs: Long) {
        val data = ByteArray(frame.data.remaining())
        frame.data.get(data)

        val nalUnits = parseNalUnits(data)
        if (nalUnits.isEmpty()) {
            Log.w(TAG, "No NAL units found in passthrough frame")
            return
        }

        if (!csdExtracted) {
            extractCsd(nalUnits)
            if (hasCsd()) {
                initializePassthroughMuxer()
                csdExtracted = true
            } else {
                Log.d(TAG, "Waiting for CSD data for passthrough...")
                return
            }
        }

        if (!isMuxerStarted || passthroughMuxer == null) return

        val isKeyFrame = containsKeyFrame(nalUnits)
        val bufferInfo = MediaCodec.BufferInfo().apply {
            offset = 0
            size = data.size
            this.presentationTimeUs = presentationTimeUs
            flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        }

        try {
            passthroughMuxer?.writeSampleData(videoTrackIndex, ByteBuffer.wrap(data), bufferInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing passthrough sample data", e)
        }
    }

    private fun parseNalUnits(data: ByteArray): List<Pair<Int, ByteArray>> {
        val nalUnits = mutableListOf<Pair<Int, ByteArray>>()
        var pos = 0
        while (pos < data.size - 3) {
            val startCodePos = findStartCode(data, pos)
            if (startCodePos < 0) break

            val is4ByteStartCode = startCodePos > 0 && (data[startCodePos - 1].toInt() and 0xFF) == 0
            val nalStart = if (is4ByteStartCode) startCodePos - 1 else startCodePos
            val nalDataStart = startCodePos + 3

            if (nalDataStart >= data.size) break

            var nalEnd = data.size
            for (i in nalDataStart until data.size - 2) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && (data[i + 2].toInt() == 1 || (i + 3 < data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1))) {
                    nalEnd = i
                    break
                }
            }

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

    private fun findStartCode(data: ByteArray, startPos: Int): Int {
        for (i in startPos until data.size - 2) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                return i
            }
        }
        return -1
    }

    private fun extractCsd(nalUnits: List<Pair<Int, ByteArray>>) {
        for ((nalType, nalData) in nalUnits) {
            if (isHevc) {
                when (nalType) {
                    H265_NAL_VPS -> if (vps == null) vps = nalData
                    H265_NAL_SPS -> if (sps == null) sps = nalData
                    H265_NAL_PPS -> if (pps == null) pps = nalData
                }
            } else {
                when (nalType) {
                    H264_NAL_SPS -> if (sps == null) sps = nalData
                    H264_NAL_PPS -> if (pps == null) pps = nalData
                }
            }
        }
    }

    private fun hasCsd(): Boolean = if (isHevc) vps != null && sps != null && pps != null else sps != null && pps != null

    private fun containsKeyFrame(nalUnits: List<Pair<Int, ByteArray>>): Boolean {
        return nalUnits.any { (nalType, _) ->
            if (isHevc) {
                nalType == H265_NAL_IDR_W_RADL || nalType == H265_NAL_IDR_N_LP
            } else {
                nalType == H264_NAL_IDR
            }
        }
    }

    private fun initializePassthroughMuxer() {
        val file = outputFile ?: throw IllegalStateException("Output file not set for passthrough")
        try {
            passthroughMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val mime = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight)

            if (isHevc) {
                val csd = ByteBuffer.allocate((vps?.size ?: 0) + (sps?.size ?: 0) + (pps?.size ?: 0))
                vps?.let { csd.put(it) }
                sps?.let { csd.put(it) }
                pps?.let { csd.put(it) }
                csd.flip()
                format.setByteBuffer("csd-0", csd)
            } else {
                sps?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                pps?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            }

            videoTrackIndex = passthroughMuxer!!.addTrack(format)
            passthroughMuxer!!.start()
            isMuxerStarted = true
            Log.i(TAG, "Passthrough MediaMuxer initialized: $mime ${videoWidth}x${videoHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize passthrough MediaMuxer", e)
            cleanup()
            throw e
        }
    }

    fun stopRecording(): File? {
        if (!isRecordingFlag.compareAndSet(true, false)) return null
        Log.d(TAG, "Stopping recording...")

        val thread = writeThread
        if (thread != null) {
            try {
                // Prefer a graceful drain of the queue; fallback to interrupt if the thread hangs.
                thread.join(3000)
                if (thread.isAlive) {
                    Log.w(TAG, "Write thread did not finish in time; interrupting")
                    thread.interrupt()
                    thread.join(1000)
                }
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for write thread")
                Thread.currentThread().interrupt()
            }
        }
        writeThread = null

        val file = outputFile
        cleanup()
        Log.i(TAG, "Recording stopped: ${file?.absolutePath}")
        return file
    }

    private fun cleanup() {
        if (isEncoding) {
            uncompressedEncoder?.release()
            uncompressedEncoder = null
        } else {
            try {
                if (isMuxerStarted) passthroughMuxer?.stop()
                passthroughMuxer?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error during passthrough muxer cleanup", e)
            }
            passthroughMuxer = null
            isMuxerStarted = false
        }

        videoTrackIndex = -1
        recordingStartTime.set(0)
        writeQueue.clear()
        sps = null
        pps = null
        vps = null
        csdExtracted = false
    }

    fun release() {
        if (isRecordingFlag.get()) {
            stopRecording()
        }
        cleanup()
        Log.d(TAG, "VideoRecorder released")
    }
}
