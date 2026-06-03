package com.example.ndireceiver.ndi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [NdiCompressedPacket], the NDIlib_compressed_packet_t parser.
 *
 * These verify the byte-level parsing against the documented Advanced SDK wire
 * format (44-byte little-endian header, `[header][data][extra_data]`, Annex-B
 * payloads). This is the one piece of HX3 logic that can be tested without the
 * Advanced SDK or live hardware.
 */
class NdiCompressedPacketTest {

    private val FOURCC_H264 = 0x34363248 // 'H264'
    private val FOURCC_HEVC = 0x43564548 // 'HEVC'

    /**
     * Build a packet buffer: [44-byte header][data][extraData].
     */
    private fun packet(
        fourCC: Int,
        flags: Int,
        data: ByteArray,
        extraData: ByteArray,
        version: Int = NdiCompressedPacket.HEADER_SIZE,
        // allow forcing an inconsistent declared size for negative tests
        declaredDataSize: Int = data.size,
        declaredExtraSize: Int = extraData.size
    ): ByteBuffer {
        val buf = ByteBuffer
            .allocate(NdiCompressedPacket.HEADER_SIZE + data.size + extraData.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(version)            // 0  version
        buf.putInt(fourCC)             // 4  fourCC
        buf.putLong(0L)                // 8  pts
        buf.putLong(0L)                // 16 dts
        buf.putLong(0L)                // 24 reserved
        buf.putInt(flags)              // 32 flags
        buf.putInt(declaredDataSize)   // 36 data_size
        buf.putInt(declaredExtraSize)  // 40 extra_data_size
        buf.put(data)                  // 44 data
        buf.put(extraData)             //    extra_data
        buf.rewind()
        return buf
    }

    @Test
    fun `keyframe prepends extra data before frame data`() {
        val data = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22) // IDR slice (Annex-B)
        val extra = byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68) // SPS + PPS

        val frame = NdiCompressedPacket.parse(packet(FOURCC_H264, 1, data, extra))!!

        assertEquals(FourCC.H264, frame.codec)
        assertTrue(frame.isKeyframe)
        // MediaCodec needs config NALs first: extra_data + data.
        assertArrayEquals(extra + data, frame.annexB)
    }

    @Test
    fun `delta frame contains only frame data`() {
        val data = byteArrayOf(0, 0, 0, 1, 0x41, 0x33, 0x44) // non-IDR slice

        val frame = NdiCompressedPacket.parse(packet(FOURCC_H264, 0, data, ByteArray(0)))!!

        assertEquals(FourCC.H264, frame.codec)
        assertFalse(frame.isKeyframe)
        assertArrayEquals(data, frame.annexB)
    }

    @Test
    fun `recognizes HEVC fourCC`() {
        val data = byteArrayOf(0, 0, 0, 1, 0x26)
        val extra = byteArrayOf(0, 0, 0, 1, 0x40) // VPS

        val frame = NdiCompressedPacket.parse(packet(FOURCC_HEVC, 1, data, extra))!!

        assertEquals(FourCC.HEVC, frame.codec)
        assertArrayEquals(extra + data, frame.annexB)
    }

    @Test
    fun `keyframe flag is read from bit 0 only`() {
        val data = byteArrayOf(1, 2, 3)
        // flags with bit0 set among other bits.
        val frame = NdiCompressedPacket.parse(packet(FOURCC_H264, 0x7, data, byteArrayOf(9)))!!
        assertTrue(frame.isKeyframe)

        val delta = NdiCompressedPacket.parse(packet(FOURCC_H264, 0x6, data, ByteArray(0)))!!
        assertFalse(delta.isKeyframe)
    }

    @Test
    fun `returns null when buffer smaller than header`() {
        val tooSmall = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        assertNull(NdiCompressedPacket.parse(tooSmall))
    }

    @Test
    fun `returns null when declared sizes exceed buffer`() {
        val data = byteArrayOf(1, 2, 3)
        // Claim more data than is actually present.
        val bad = packet(FOURCC_H264, 1, data, ByteArray(0), declaredDataSize = 9999)
        assertNull(NdiCompressedPacket.parse(bad))
    }

    @Test
    fun `returns null for unknown codec`() {
        val data = byteArrayOf(1, 2, 3)
        val aac = 0x00ff
        assertNull(NdiCompressedPacket.parse(packet(aac, 1, data, ByteArray(0))))
    }

    @Test
    fun `does not consume the source buffer position`() {
        val data = byteArrayOf(0, 0, 0, 1, 0x41)
        val buf = packet(FOURCC_H264, 0, data, ByteArray(0))
        val posBefore = buf.position()
        NdiCompressedPacket.parse(buf)
        assertEquals(posBefore, buf.position())
    }
}
