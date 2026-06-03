package com.example.ndireceiver.ndi

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for `NDIlib_compressed_packet_t` — the container the NDI **Advanced SDK**
 * uses to deliver compressed H.264 / HEVC (NDI|HX, HX3) video to the application.
 *
 * When the receiver is created with [NdiNative.ColorFormat.COMPRESSED_V5], each
 * captured video frame's `p_data` points at this packet instead of raw pixels.
 * This parser turns it into a MediaCodec-ready Annex-B byte stream.
 *
 * Wire format (little-endian, `#pragma pack(1)`, 44-byte header, no padding):
 * ```
 * offset  type      field
 *  0      int32     version            (== header size, currently 44)
 *  4      uint32    fourCC             ('H264' or 'HEVC')
 *  8      int64     pts                (100ns units)
 * 16      int64     dts                (100ns units)
 * 24      uint64    reserved
 * 32      uint32    flags              (bit 0 = keyframe / I-frame)
 * 36      uint32    data_size          (bytes of compressed payload)
 * 40      uint32    extra_data_size    (bytes of codec config; I-frames only)
 * 44      ...       [ data ][ extra_data ]   (in that order)
 * ```
 * Both `data` and `extra_data` are **Annex-B** (NAL units delimited by
 * `00 00 00 01` start codes). `extra_data` holds the codec configuration as
 * concatenated Annex-B parameter sets — H.264: SPS, PPS; HEVC: VPS, SPS, PPS —
 * and is present only on keyframes.
 *
 * Source: Processing.NDI.Advanced.h (NDIlib_compressed_packet_t, version_0 = 44)
 * and docs.ndi.video "Using H.264, H.265, and AAC Codecs".
 */
object NdiCompressedPacket {

    /** Size of the fixed packet header in bytes (NDIlib_compressed_packet_version_0). */
    const val HEADER_SIZE = 44

    private const val FLAG_KEYFRAME = 0x1

    // Header field offsets.
    private const val OFF_VERSION = 0
    private const val OFF_FOURCC = 4
    private const val OFF_FLAGS = 32
    private const val OFF_DATA_SIZE = 36
    private const val OFF_EXTRA_SIZE = 40

    /**
     * Result of parsing one compressed packet.
     *
     * @property codec [FourCC.H264] or [FourCC.HEVC].
     * @property isKeyframe true when this is an I-frame (carries codec config).
     * @property annexB MediaCodec-ready Annex-B stream. On a keyframe this is
     *   `extra_data + data` (config NALs first, as MediaCodec requires); on a
     *   delta frame it is `data` only. Always a freshly allocated copy, so it
     *   stays valid after the native NDI frame is freed.
     */
    data class CompressedFrame(
        val codec: FourCC,
        val isKeyframe: Boolean,
        val annexB: ByteArray
    ) {
        // ByteArray needs explicit equals/hashCode for a value-like data class.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CompressedFrame) return false
            return codec == other.codec &&
                isKeyframe == other.isKeyframe &&
                annexB.contentEquals(other.annexB)
        }

        override fun hashCode(): Int {
            var result = codec.hashCode()
            result = 31 * result + isKeyframe.hashCode()
            result = 31 * result + annexB.contentHashCode()
            return result
        }
    }

    /**
     * Parse a compressed NDI video packet.
     *
     * @param buffer the frame buffer from native (position/limit are not mutated).
     * @return the parsed frame, or null if the buffer is too small, the sizes are
     *   inconsistent, or the codec is not H.264/HEVC.
     */
    fun parse(buffer: ByteBuffer): CompressedFrame? {
        val buf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = buf.position()
        val total = buf.remaining()
        if (total < HEADER_SIZE) return null

        val version = buf.getInt(base + OFF_VERSION)
        val fourCC = buf.getInt(base + OFF_FOURCC)
        val flags = buf.getInt(base + OFF_FLAGS)
        val dataSize = buf.getInt(base + OFF_DATA_SIZE)
        val extraSize = buf.getInt(base + OFF_EXTRA_SIZE)

        // The payload starts at `version` bytes in (forward-compatible if the
        // header ever grows); never less than the known header size.
        val headerLen = if (version >= HEADER_SIZE) version else HEADER_SIZE
        if (dataSize < 0 || extraSize < 0) return null

        val dataStart = base + headerLen
        val extraStart = dataStart + dataSize
        // Everything must fit inside the buffer.
        if (headerLen > total) return null
        if (extraStart + extraSize > base + total) return null

        val codec = when (fourCC) {
            NdiNative.FourCC.H264, NdiNative.FourCC.H264_LOW -> FourCC.H264
            NdiNative.FourCC.HEVC, NdiNative.FourCC.HEVC_LOW -> FourCC.HEVC
            else -> return null
        }

        val isKeyframe = (flags and FLAG_KEYFRAME) != 0
        val includeExtra = isKeyframe && extraSize > 0

        val out = ByteArray(if (includeExtra) extraSize + dataSize else dataSize)
        var offset = 0
        // MediaCodec needs config NALs (SPS/PPS/VPS) BEFORE the frame data.
        if (includeExtra) {
            buf.position(extraStart)
            buf.get(out, offset, extraSize)
            offset += extraSize
        }
        buf.position(dataStart)
        buf.get(out, offset, dataSize)

        return CompressedFrame(codec, isKeyframe, out)
    }
}
