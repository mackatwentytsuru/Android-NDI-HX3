package com.example.ndireceiver.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

data class VideoFormatConfig(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val colorFormat: Int,
    val bitRate: Int,
    val frameRate: Int,
    val iFrameIntervalSeconds: Int
)

interface EncoderCodec {
    fun configure(config: VideoFormatConfig)
    fun start()
    fun dequeueInputBuffer(timeoutUs: Long): Int
    fun getInputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)
    fun dequeueOutputBuffer(bufferInfo: MediaCodec.BufferInfo, timeoutUs: Long): Int
    fun getOutputBuffer(index: Int): ByteBuffer?
    val outputFormat: Any
    fun releaseOutputBuffer(index: Int, render: Boolean)
    fun stop()
    fun release()
}

interface EncoderMuxer {
    fun addTrack(format: Any): Int
    fun start()
    fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo)
    fun stop()
    fun release()
}

fun interface EncoderCodecFactory {
    fun createEncoderByType(mimeType: String): EncoderCodec
}

fun interface EncoderMuxerFactory {
    fun create(path: String, format: Int): EncoderMuxer
}

private class AndroidEncoderCodec(private val codec: MediaCodec) : EncoderCodec {
    override fun configure(config: VideoFormatConfig) {
        val format = MediaFormat.createVideoFormat(config.mimeType, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, config.colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    override fun start() = codec.start()
    override fun dequeueInputBuffer(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)
    override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)
    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) =
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)

    override fun dequeueOutputBuffer(bufferInfo: MediaCodec.BufferInfo, timeoutUs: Long): Int =
        codec.dequeueOutputBuffer(bufferInfo, timeoutUs)

    override fun getOutputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)
    override val outputFormat: Any get() = codec.outputFormat
    override fun releaseOutputBuffer(index: Int, render: Boolean) = codec.releaseOutputBuffer(index, render)
    override fun stop() = codec.stop()
    override fun release() = codec.release()
}

private class AndroidEncoderMuxer(private val muxer: MediaMuxer) : EncoderMuxer {
    override fun addTrack(format: Any): Int = muxer.addTrack(format as MediaFormat)
    override fun start() = muxer.start()
    override fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) =
        muxer.writeSampleData(trackIndex, byteBuf, bufferInfo)

    override fun stop() = muxer.stop()
    override fun release() = muxer.release()
}

private val androidCodecFactory = EncoderCodecFactory { mimeType ->
    AndroidEncoderCodec(MediaCodec.createEncoderByType(mimeType))
}

private val androidMuxerFactory = EncoderMuxerFactory { path, format ->
    AndroidEncoderMuxer(MediaMuxer(path, format))
}

class UncompressedVideoEncoder(
    width: Int,
    height: Int,
    bitRate: Int,
    outputFile: File,
    private val codecFactory: EncoderCodecFactory = androidCodecFactory,
    private val muxerFactory: EncoderMuxerFactory = androidMuxerFactory
) : VideoEncoder {

    private val TAG = "UncompressedVideoEncoder"
    private val MIME_TYPE = "video/avc" // H.264
    private val FRAME_RATE = 30
    private val I_FRAME_INTERVAL = 1 // seconds
    private val TIMEOUT_USEC = 10_000L
    private val MAX_EOS_DRAIN_RETRIES = 15

    private val lock = Any()
    private val mediaCodec: EncoderCodec
    private val mediaMuxer: EncoderMuxer
    private var trackIndex = -1
    private var isMuxerStarted = false
    private var isEncoderStarted = false
    private var isReleased = false
    private var lastPresentationTimeUs: Long = 0
    private val bufferInfo: MediaCodec.BufferInfo = MediaCodec.BufferInfo()

    init {
        mediaCodec = codecFactory.createEncoderByType(MIME_TYPE)
        mediaMuxer = muxerFactory.create(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val config = VideoFormatConfig(
            mimeType = MIME_TYPE,
            width = width,
            height = height,
            colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, // NV12
            bitRate = bitRate,
            frameRate = FRAME_RATE,
            iFrameIntervalSeconds = I_FRAME_INTERVAL
        )

        mediaCodec.configure(config)
        mediaCodec.start()
        isEncoderStarted = true
    }

    override fun encodeFrame(frameData: ByteArray, presentationTimeUs: Long) {
        synchronized(lock) {
            if (isReleased) {
                Log.w(TAG, "encodeFrame() called after release; dropping frame")
                return
            }
            lastPresentationTimeUs = presentationTimeUs

            val inputBufferId = try {
                mediaCodec.dequeueInputBuffer(TIMEOUT_USEC)
            } catch (e: Exception) {
                Log.e(TAG, "dequeueInputBuffer failed", e)
                return
            }

            if (inputBufferId >= 0) {
                val inputBuffer = mediaCodec.getInputBuffer(inputBufferId)
                if (inputBuffer == null) {
                    Log.w(TAG, "getInputBuffer($inputBufferId) returned null")
                    return
                }
                inputBuffer.clear()
                if (inputBuffer.remaining() < frameData.size) {
                    Log.e(TAG, "Input buffer too small: remaining=${inputBuffer.remaining()} needed=${frameData.size}")
                    return
                }
                inputBuffer.put(frameData)
                try {
                    mediaCodec.queueInputBuffer(inputBufferId, 0, frameData.size, presentationTimeUs, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "queueInputBuffer failed", e)
                    return
                }
            }

            drainEncoder(endOfStream = false)
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        var tryAgainCount = 0
        while (true) {
            val outputBufferId = try {
                mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            } catch (e: Exception) {
                Log.w(TAG, "dequeueOutputBuffer failed", e)
                break
            }
            if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
                tryAgainCount++
                if (tryAgainCount >= MAX_EOS_DRAIN_RETRIES) {
                    Log.w(TAG, "Timed out draining encoder (EOS)")
                    break
                }
            } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isMuxerStarted) {
                    Log.w(TAG, "Output format changed after muxer started; ignoring")
                } else {
                    val newFormat = mediaCodec.outputFormat
                    trackIndex = mediaMuxer.addTrack(newFormat)
                    mediaMuxer.start()
                    isMuxerStarted = true
                }
            } else if (outputBufferId >= 0) {
                val encodedData = mediaCodec.getOutputBuffer(outputBufferId)
                if (encodedData == null) {
                    Log.w(TAG, "getOutputBuffer($outputBufferId) returned null")
                    mediaCodec.releaseOutputBuffer(outputBufferId, false)
                    continue
                }

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0) {
                    if (!isMuxerStarted) {
                        Log.w(TAG, "Muxer not started; dropping sample (size=${bufferInfo.size})")
                    } else {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        try {
                            mediaMuxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        } catch (e: Exception) {
                            Log.e(TAG, "writeSampleData failed", e)
                        }
                    }
                }

                mediaCodec.releaseOutputBuffer(outputBufferId, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    override fun release() {
        synchronized(lock) {
            if (isReleased) return
            isReleased = true

            // For ByteBuffer input mode, EOS is signaled by queueing an empty input buffer with EOS flag.
            try {
                if (isEncoderStarted) {
                    val eosInputBufferId = mediaCodec.dequeueInputBuffer(TIMEOUT_USEC)
                    if (eosInputBufferId >= 0) {
                        mediaCodec.queueInputBuffer(
                            eosInputBufferId,
                            0,
                            0,
                            lastPresentationTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    } else {
                        Log.w(TAG, "No input buffer available to queue EOS")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to queue EOS", e)
            }

            try {
                drainEncoder(endOfStream = true)
            } catch (e: Exception) {
                Log.w(TAG, "Final drain failed", e)
            }

            try {
                if (isEncoderStarted) {
                    mediaCodec.stop()
                    isEncoderStarted = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaCodec.stop() failed", e)
            }

            try {
                mediaCodec.release()
            } catch (e: Exception) {
                Log.w(TAG, "MediaCodec.release() failed", e)
            }

            try {
                if (isMuxerStarted) {
                    mediaMuxer.stop()
                    isMuxerStarted = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaMuxer.stop() failed", e)
            }

            try {
                mediaMuxer.release()
            } catch (e: Exception) {
                Log.w(TAG, "MediaMuxer.release() failed", e)
            }
        }
    }
}
