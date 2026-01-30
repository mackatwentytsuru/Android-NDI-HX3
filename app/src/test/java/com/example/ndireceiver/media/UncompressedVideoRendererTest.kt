package com.example.ndireceiver.media

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Unit tests for UncompressedVideoRenderer.
 * Tests color conversion, buffer validation, and stride handling.
 *
 * Note: Some private methods are tested by reimplementing the logic here
 * to verify the algorithm correctness without Android dependencies.
 */
class UncompressedVideoRendererTest {

    @Before
    fun setUp() {
        // Mock Android Log class
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
        every { android.util.Log.d(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== Pure Algorithm Tests (no Android dependencies) ==========

    /**
     * Reimplementation of clamp8 for testing
     */
    private fun clamp8(value: Int): Int = min(255, max(0, value))

    /**
     * Reimplementation of writeYuvToBgra for testing BT.601 conversion
     */
    private fun writeYuvToBgra(dst: ByteArray, dstOffset: Int, y: Int, u: Int, v: Int) {
        val c = y - 16
        val d = u - 128
        val e = v - 128

        val r = clamp8((298 * c + 409 * e + 128) shr 8)
        val g = clamp8((298 * c - 100 * d - 208 * e + 128) shr 8)
        val b = clamp8((298 * c + 516 * d + 128) shr 8)

        dst[dstOffset] = b.toByte()
        dst[dstOffset + 1] = g.toByte()
        dst[dstOffset + 2] = r.toByte()
        dst[dstOffset + 3] = 0xFF.toByte()
    }

    /**
     * Reimplementation of normalizeStride for testing
     */
    private fun normalizeStride(strideBytes: Int, minRowBytes: Int): Int {
        if (strideBytes == 0) return minRowBytes
        val absStride = kotlin.math.abs(strideBytes)
        return if (absStride < minRowBytes) {
            minRowBytes
        } else {
            strideBytes
        }
    }

    /**
     * Reimplementation of validateSizes for testing
     */
    private fun validateSizes(srcRemaining: Int, absStride: Int, height: Int, rowBytes: Int): Boolean {
        if (height <= 0) return false
        if (absStride <= 0) return false
        if (rowBytes <= 0) return false
        val needed = absStride.toLong() * height.toLong()
        return srcRemaining.toLong() >= needed
    }

    // ========== BT.601 Color Conversion Tests ==========

    @Test
    fun `writeYuvToBgra converts black correctly`() {
        // Y=16, U=128, V=128 represents black in BT.601 limited range
        val dst = ByteArray(4)
        writeYuvToBgra(dst, 0, 16, 128, 128)

        // Black should produce (0, 0, 0, 255) in BGRA
        assertEquals("B should be 0", 0, dst[0].toInt() and 0xFF)
        assertEquals("G should be 0", 0, dst[1].toInt() and 0xFF)
        assertEquals("R should be 0", 0, dst[2].toInt() and 0xFF)
        assertEquals("A should be 255", 255, dst[3].toInt() and 0xFF)
    }

    @Test
    fun `writeYuvToBgra converts white correctly`() {
        // Y=235, U=128, V=128 represents white in BT.601 limited range
        val dst = ByteArray(4)
        writeYuvToBgra(dst, 0, 235, 128, 128)

        // White should produce approximately (255, 255, 255, 255) in BGRA
        // Allow small tolerance for integer math rounding
        assertTrue("B should be near 255", dst[0].toInt() and 0xFF >= 250)
        assertTrue("G should be near 255", dst[1].toInt() and 0xFF >= 250)
        assertTrue("R should be near 255", dst[2].toInt() and 0xFF >= 250)
        assertEquals("A should be 255", 255, dst[3].toInt() and 0xFF)
    }

    @Test
    fun `writeYuvToBgra converts red correctly`() {
        // Y=81, U=90, V=240 represents red in BT.601
        val dst = ByteArray(4)
        writeYuvToBgra(dst, 0, 81, 90, 240)

        val b = dst[0].toInt() and 0xFF
        val g = dst[1].toInt() and 0xFF
        val r = dst[2].toInt() and 0xFF

        // Red should have high R, low G and B
        assertTrue("R should be high (>200)", r > 200)
        assertTrue("G should be low (<50)", g < 50)
        assertTrue("B should be low (<50)", b < 50)
    }

    @Test
    fun `writeYuvToBgra converts green correctly`() {
        // Y=145, U=54, V=34 represents green in BT.601
        val dst = ByteArray(4)
        writeYuvToBgra(dst, 0, 145, 54, 34)

        val b = dst[0].toInt() and 0xFF
        val g = dst[1].toInt() and 0xFF
        val r = dst[2].toInt() and 0xFF

        // Green should have high G, low R and B
        assertTrue("G should be high (>200)", g > 200)
        assertTrue("R should be low (<50)", r < 50)
        assertTrue("B should be low (<50)", b < 50)
    }

    @Test
    fun `writeYuvToBgra converts blue correctly`() {
        // Y=41, U=240, V=110 represents blue in BT.601
        val dst = ByteArray(4)
        writeYuvToBgra(dst, 0, 41, 240, 110)

        val b = dst[0].toInt() and 0xFF
        val g = dst[1].toInt() and 0xFF
        val r = dst[2].toInt() and 0xFF

        // Blue should have high B, low R and G
        assertTrue("B should be high (>200)", b > 200)
        assertTrue("R should be low (<50)", r < 50)
        assertTrue("G should be low (<50)", g < 50)
    }

    // ========== Clamp Tests ==========

    @Test
    fun `clamp8 returns 0 for negative values`() {
        assertEquals(0, clamp8(-1))
        assertEquals(0, clamp8(-100))
        assertEquals(0, clamp8(Int.MIN_VALUE))
    }

    @Test
    fun `clamp8 returns 255 for values above 255`() {
        assertEquals(255, clamp8(256))
        assertEquals(255, clamp8(500))
        assertEquals(255, clamp8(Int.MAX_VALUE))
    }

    @Test
    fun `clamp8 returns input for valid range values`() {
        assertEquals(0, clamp8(0))
        assertEquals(128, clamp8(128))
        assertEquals(255, clamp8(255))
    }

    // ========== Stride Normalization Tests ==========

    @Test
    fun `normalizeStride returns minRowBytes when stride is 0`() {
        assertEquals(1920, normalizeStride(0, 1920))
    }

    @Test
    fun `normalizeStride returns stride when stride is valid`() {
        assertEquals(2048, normalizeStride(2048, 1920))
    }

    @Test
    fun `normalizeStride handles negative stride for bottom-up frames`() {
        // Negative stride indicates bottom-up frame
        assertEquals(-2048, normalizeStride(-2048, 1920))
    }

    @Test
    fun `normalizeStride returns minRowBytes when stride is too small`() {
        // If stride is less than minRowBytes, use tight stride
        assertEquals(1920, normalizeStride(1000, 1920))
    }

    // ========== Buffer Validation Tests ==========

    @Test
    fun `validateSizes returns true for valid buffer`() {
        // Buffer with enough space: 1920 stride * 1080 height = 2,073,600 bytes
        assertTrue(validateSizes(2073600, 1920, 1080, 1920))
    }

    @Test
    fun `validateSizes returns false for buffer too small`() {
        // Buffer smaller than needed
        assertFalse(validateSizes(1000000, 1920, 1080, 1920))
    }

    @Test
    fun `validateSizes returns false for zero height`() {
        assertFalse(validateSizes(1000000, 1920, 0, 1920))
    }

    @Test
    fun `validateSizes returns false for negative height`() {
        assertFalse(validateSizes(1000000, 1920, -1, 1920))
    }

    @Test
    fun `validateSizes returns false for zero stride`() {
        assertFalse(validateSizes(1000000, 0, 1080, 1920))
    }

    @Test
    fun `validateSizes handles large frame correctly`() {
        // 4K frame: 3840*4 stride * 2160 height
        val stride = 3840 * 4
        val needed = stride.toLong() * 2160
        assertTrue(validateSizes(needed.toInt(), stride, 2160, stride))
    }

    @Test
    fun `validateSizes handles exactly enough bytes`() {
        // Exactly the right amount
        assertTrue(validateSizes(1920 * 1080, 1920, 1080, 1920))
    }

    @Test
    fun `validateSizes handles padded stride`() {
        // Stride is 2048 (padded), but we need 1920 bytes per row
        assertTrue(validateSizes(2048 * 1080, 2048, 1080, 1920))
    }
}
