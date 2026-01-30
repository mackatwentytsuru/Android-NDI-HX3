package com.example.ndireceiver.media

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.Surface
import com.example.ndireceiver.ndi.NdiNative
import com.example.ndireceiver.ndi.VideoFrameData
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Renders uncompressed NDI video frames (BGRA/BGRX/RGBA/RGBX/UYVY) onto an Android Surface.
 *
 * Notes:
 * - NDI SDK v6 can deliver already-decoded (uncompressed) frames; these must NOT be sent to MediaCodec.
 * - The incoming frame ByteBuffer is backed by native memory and is only valid until the caller frees it.
 *   This renderer copies/converts the frame synchronously during [render].
 * - Bitmap.Config.ARGB_8888 with copyPixelsFromBuffer() expects RGBA byte order.
 */
class UncompressedVideoRenderer {
    companion object {
        private const val TAG = "UncompressedVideoRenderer"
    }

    private val renderLock = Any()

    @Volatile
    private var surface: Surface? = null

    private var bitmap: Bitmap? = null
    private var bitmapWidth = 0
    private var bitmapHeight = 0

    // RGBA format for Bitmap.Config.ARGB_8888
    private var rgbaBytes: ByteArray? = null
    private var rgbaBuffer: ByteBuffer? = null
    private var rowScratch: ByteArray? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect = Rect()

    fun setSurface(surface: Surface?) {
        synchronized(renderLock) {
            this.surface = surface
        }
    }

    fun release() {
        synchronized(renderLock) {
            surface = null
            bitmap?.recycle()
            bitmap = null
            bitmapWidth = 0
            bitmapHeight = 0
            rgbaBytes = null
            rgbaBuffer = null
            rowScratch = null
        }
    }

    fun render(frame: VideoFrameData) {
        synchronized(renderLock) {
            val currentSurface = surface ?: return
            if (frame.width <= 0 || frame.height <= 0) return

            ensureBuffers(frame.width, frame.height)

            val pixels = rgbaBytes ?: return
            val bufferView = rgbaBuffer ?: return

            // All copy/convert functions output RGBA for Bitmap.Config.ARGB_8888
            val ok = when (frame.fourCC) {
                NdiNative.FourCC.BGRA -> copyBgraToRgba(frame, pixels, forceOpaque = false)
                NdiNative.FourCC.BGRX -> copyBgraToRgba(frame, pixels, forceOpaque = true)
                NdiNative.FourCC.RGBA -> copyRgba(frame, pixels, forceOpaque = false)
                NdiNative.FourCC.RGBX -> copyRgba(frame, pixels, forceOpaque = true)
                NdiNative.FourCC.UYVY -> convertUyvyToRgba(frame, pixels)
                else -> {
                    Log.w(TAG, "Unsupported FourCC for uncompressed render: ${String.format("0x%08X", frame.fourCC)}")
                    false
                }
            }

            if (!ok) return

            val bmp = bitmap ?: return
            try {
                bufferView.rewind()
                bmp.copyPixelsFromBuffer(bufferView)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy pixels into Bitmap", e)
                return
            }

            val canvas = try {
                currentSurface.lockCanvas(null)
            } catch (e: Exception) {
                Log.e(TAG, "Surface.lockCanvas failed", e)
                return
            }

            try {
                dstRect.set(0, 0, canvas.width, canvas.height)
                canvas.drawBitmap(bmp, null, dstRect, paint)
            } catch (e: Exception) {
                Log.e(TAG, "Canvas draw failed", e)
            } finally {
                try {
                    currentSurface.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.w(TAG, "Surface.unlockCanvasAndPost failed", e)
                }
            }
        }
    }

    private fun ensureBuffers(width: Int, height: Int) {
        val existing = bitmap
        if (existing != null && !existing.isRecycled && bitmapWidth == width && bitmapHeight == height) {
            return
        }

        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
        }
        bitmapWidth = width
        bitmapHeight = height

        val bytes = ByteArray(width * height * 4)
        rgbaBytes = bytes
        rgbaBuffer = ByteBuffer.wrap(bytes)
        rowScratch = ByteArray(max(width * 4, width * 2))
    }

    /**
     * Copies BGRA source to RGBA destination, swapping R and B channels.
     */
    private fun copyBgraToRgba(frame: VideoFrameData, dstRgba: ByteArray, forceOpaque: Boolean): Boolean {
        val width = frame.width
        val height = frame.height
        val rowBytes = width * 4
        val strideBytes = normalizeStride(frame.lineStrideBytes, rowBytes)
        val absStride = abs(strideBytes)

        val scratch = rowScratch ?: return false
        if (scratch.size < rowBytes) return false

        val src = frame.data.duplicate()
        val basePos = src.position()

        if (!validateSizes(src.remaining(), absStride, height, rowBytes)) return false

        var dstOffset = 0
        for (row in 0 until height) {
            val srcRowOffset = if (strideBytes >= 0) row * absStride else (height - 1 - row) * absStride
            src.position(basePos + srcRowOffset)
            src.get(scratch, 0, rowBytes)

            var s = 0
            var d = dstOffset
            while (s < rowBytes) {
                // Source is BGRA
                val b = scratch[s]
                val g = scratch[s + 1]
                val r = scratch[s + 2]
                val a = if (forceOpaque) 0xFF.toByte() else scratch[s + 3]

                // Destination is RGBA for Bitmap
                dstRgba[d] = r
                dstRgba[d + 1] = g
                dstRgba[d + 2] = b
                dstRgba[d + 3] = a

                s += 4
                d += 4
            }

            dstOffset += rowBytes
        }

        return true
    }

    /**
     * Copies RGBA source to RGBA destination (direct copy with stride handling).
     */
    private fun copyRgba(frame: VideoFrameData, dstRgba: ByteArray, forceOpaque: Boolean): Boolean {
        val width = frame.width
        val height = frame.height
        val rowBytes = width * 4
        val strideBytes = normalizeStride(frame.lineStrideBytes, rowBytes)
        val absStride = abs(strideBytes)

        val src = frame.data.duplicate()
        val basePos = src.position()

        if (!validateSizes(src.remaining(), absStride, height, rowBytes)) return false

        var dstOffset = 0
        for (row in 0 until height) {
            val srcRowOffset = if (strideBytes >= 0) row * absStride else (height - 1 - row) * absStride
            src.position(basePos + srcRowOffset)
            src.get(dstRgba, dstOffset, rowBytes)

            if (forceOpaque) {
                var i = dstOffset + 3
                val end = dstOffset + rowBytes
                while (i < end) {
                    dstRgba[i] = 0xFF.toByte()
                    i += 4
                }
            }

            dstOffset += rowBytes
        }

        return true
    }

    /**
     * Converts UYVY source to RGBA destination.
     */
    private fun convertUyvyToRgba(frame: VideoFrameData, dstRgba: ByteArray): Boolean {
        val width = frame.width
        val height = frame.height
        val rowBytesSrc = width * 2
        val rowBytesDst = width * 4
        val strideBytes = normalizeStride(frame.lineStrideBytes, rowBytesSrc)
        val absStride = abs(strideBytes)

        val scratch = rowScratch ?: return false
        if (scratch.size < rowBytesSrc) return false

        val src = frame.data.duplicate()
        val basePos = src.position()

        if (!validateSizes(src.remaining(), absStride, height, rowBytesSrc)) return false

        var dstOffset = 0
        for (row in 0 until height) {
            val srcRowOffset = if (strideBytes >= 0) row * absStride else (height - 1 - row) * absStride
            src.position(basePos + srcRowOffset)
            src.get(scratch, 0, rowBytesSrc)

            var s = 0
            var d = dstOffset
            // Process 2 pixels (4 bytes of UYVY -> 8 bytes of RGBA) at a time
            while (s + 3 < rowBytesSrc) {
                val u = scratch[s].toInt() and 0xFF
                val y0 = scratch[s + 1].toInt() and 0xFF
                val v = scratch[s + 2].toInt() and 0xFF
                val y1 = scratch[s + 3].toInt() and 0xFF

                writeYuvToRgba(dstRgba, d, y0, u, v)
                writeYuvToRgba(dstRgba, d + 4, y1, u, v)

                s += 4
                d += 8
            }

            dstOffset += rowBytesDst
        }

        return true
    }

    /**
     * Converts a single YUV pixel to RGBA and writes it to the destination array.
     * Uses BT.601 limited-range conversion.
     */
    private fun writeYuvToRgba(dst: ByteArray, dstOffset: Int, y: Int, u: Int, v: Int) {
        val c = y - 16
        val d = u - 128
        val e = v - 128

        val r = clamp8((298 * c + 409 * e + 128) shr 8)
        val g = clamp8((298 * c - 100 * d - 208 * e + 128) shr 8)
        val b = clamp8((298 * c + 516 * d + 128) shr 8)

        // Write in RGBA order for Bitmap.Config.ARGB_8888
        dst[dstOffset] = r.toByte()
        dst[dstOffset + 1] = g.toByte()
        dst[dstOffset + 2] = b.toByte()
        dst[dstOffset + 3] = 0xFF.toByte()
    }

    private fun clamp8(value: Int): Int = min(255, max(0, value))

    private fun normalizeStride(strideBytes: Int, minRowBytes: Int): Int {
        if (strideBytes == 0) return minRowBytes
        val absStride = abs(strideBytes)
        return if (absStride < minRowBytes) {
            Log.w(TAG, "Invalid stride ($strideBytes) < row bytes ($minRowBytes); using tight stride")
            minRowBytes
        } else {
            strideBytes
        }
    }

    private fun validateSizes(srcRemaining: Int, absStride: Int, height: Int, rowBytes: Int): Boolean {
        if (height <= 0) return false
        if (absStride <= 0) return false
        if (rowBytes <= 0) return false
        val needed = absStride.toLong() * (height - 1) + rowBytes
        return if (srcRemaining.toLong() < needed) {
            Log.w(TAG, "Source buffer too small: remaining=$srcRemaining needed=$needed (stride=$absStride height=$height)")
            false
        } else true
    }
}
