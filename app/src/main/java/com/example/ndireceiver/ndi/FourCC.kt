package com.example.ndireceiver.ndi

enum class FourCC {
    UYVY,
    BGRA,
    BGRX,
    RGBA,
    RGBX,
    NV12,
    I420,
    H264,
    HEVC,
    UNKNOWN;

    companion object {
        fun fromInt(value: Int): FourCC {
            return when (value) {
                NdiNative.FourCC.UYVY -> UYVY
                NdiNative.FourCC.BGRA -> BGRA
                NdiNative.FourCC.BGRX -> BGRX
                NdiNative.FourCC.RGBA -> RGBA
                NdiNative.FourCC.RGBX -> RGBX
                NdiNative.FourCC.NV12 -> NV12
                NdiNative.FourCC.I420 -> I420
                NdiNative.FourCC.H264 -> H264
                NdiNative.FourCC.HEVC -> HEVC
                else -> UNKNOWN
            }
        }
    }
}
