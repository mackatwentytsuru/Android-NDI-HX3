package com.example.ndireceiver.media

import android.util.Log
import com.example.ndireceiver.ndi.FourCC
import java.nio.ByteBuffer
import kotlin.math.abs

class ColorSpaceConverter {
    companion object {
        private const val TAG = "ColorSpaceConverter"

        fun convert(
            data: ByteBuffer,
            fourCC: FourCC,
            width: Int,
            height: Int,
            lineStrideBytes: Int
        ): ByteArray? {
            val src = data.duplicate()
            return when (fourCC) {
                FourCC.UYVY -> uyvyToNv12(src, width, height, lineStrideBytes)
                FourCC.BGRA, FourCC.BGRX -> bgraOrBgrxToNv12(src, width, height, lineStrideBytes)
                else -> {
                    Log.w(TAG, "Unsupported FourCC for conversion: $fourCC")
                    null
                }
            }
        }

        private fun uyvyToNv12(input: ByteBuffer, width: Int, height: Int, lineStrideBytes: Int): ByteArray? {
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Invalid dimensions for UYVY conversion: ${width}x$height")
                return null
            }

            val rowBytes = width * 2
            val strideBytes = normalizeStride(lineStrideBytes, rowBytes)
            val absStride = abs(strideBytes)

            if (!validateSizes(input.remaining(), absStride, height, rowBytes)) {
                Log.w(TAG, "UYVY conversion failed due to invalid buffer/stride (stride=$lineStrideBytes)")
                return null
            }

            val nv12 = ByteArray(width * height * 3 / 2)
            val yPlane = nv12
            val uvPlane = nv12
            val yStride = width
            val uvStride = width
            val yOffset = 0
            val uvOffset = width * height

            val scratch = ByteArray(rowBytes)
            val basePos = input.position()

            // This is a basic, unoptimized implementation.
            // For production, this should be done in C++ with NEON.
            for (row in 0 until height) {
                val srcRowOffset = if (strideBytes >= 0) row * absStride else (height - 1 - row) * absStride
                input.position(basePos + srcRowOffset)
                input.get(scratch, 0, rowBytes)

                var s = 0
                var x = 0
                while (s + 3 < rowBytes && x + 1 < width) {
                    val u = scratch[s].toInt() and 0xFF
                    val y0 = scratch[s + 1].toInt() and 0xFF
                    val v = scratch[s + 2].toInt() and 0xFF
                    val y1 = scratch[s + 3].toInt() and 0xFF

                    val yIndex = row * yStride + x
                    yPlane[yOffset + yIndex] = y0.toByte()
                    yPlane[yOffset + yIndex + 1] = y1.toByte()

                    if (row % 2 == 0) {
                        val uvIndex = (row / 2) * uvStride + x
                        uvPlane[uvOffset + uvIndex] = u.toByte()
                        uvPlane[uvOffset + uvIndex + 1] = v.toByte()
                    }

                    s += 4
                    x += 2
                }
            }

            return nv12
        }

        private fun bgraOrBgrxToNv12(input: ByteBuffer, width: Int, height: Int, lineStrideBytes: Int): ByteArray? {
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Invalid dimensions for BGRA/BGRX conversion: ${width}x$height")
                return null
            }

            val rowBytes = width * 4
            val strideBytes = normalizeStride(lineStrideBytes, rowBytes)
            val absStride = abs(strideBytes)

            if (!validateSizes(input.remaining(), absStride, height, rowBytes)) {
                Log.w(TAG, "BGRA/BGRX conversion failed due to invalid buffer/stride (stride=$lineStrideBytes)")
                return null
            }

            val nv12 = ByteArray(width * height * 3 / 2)
            val yPlane = nv12
            val uvPlane = nv12
            val yStride = width
            val uvStride = width
            val yOffset = 0
            val uvOffset = width * height

            val scratch = ByteArray(rowBytes)
            val basePos = input.position()

            // This is a basic, unoptimized implementation.
            // For production, this should be done in C++ with NEON.
            for (row in 0 until height) {
                val srcRowOffset = if (strideBytes >= 0) row * absStride else (height - 1 - row) * absStride
                input.position(basePos + srcRowOffset)
                input.get(scratch, 0, rowBytes)

                for (col in 0 until width) {
                    val index = col * 4
                    val b = scratch[index].toInt() and 0xFF
                    val g = scratch[index + 1].toInt() and 0xFF
                    val r = scratch[index + 2].toInt() and 0xFF

                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                    yPlane[yOffset + row * yStride + col] = y.coerceIn(16, 235).toByte()

                    if (row % 2 == 0 && col % 2 == 0) {
                        val uvIndex = (row / 2) * uvStride + col
                        uvPlane[uvOffset + uvIndex] = u.coerceIn(16, 240).toByte()
                        uvPlane[uvOffset + uvIndex + 1] = v.coerceIn(16, 240).toByte()
                    }
                }
            }

            return nv12
        }

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

            val required = (height - 1) * absStride + rowBytes
            if (srcRemaining < required) {
                Log.w(TAG, "Source buffer too small: remaining=$srcRemaining required=$required")
                return false
            }

            return true
        }
    }
}
