package com.example.ndireceiver.media

/**
 * Minimal interface used by [VideoRecorder] to allow unit testing without relying on Android's
 * real [android.media.MediaCodec]/[android.media.MediaMuxer] implementations.
 */
interface VideoEncoder {
    fun encodeFrame(frameData: ByteArray, presentationTimeUs: Long)
    fun release()
}

