package com.floss.microscope.infrastructure.processing

import com.floss.microscope.core.logging.FlossLogger

object NativeCvBridge {
    private const val TAG = "NativeCv"

    init {
        try {
            System.loadLibrary("microscope_cv")
            FlossLogger.i(TAG, "libmicroscope_cv loaded")
        } catch (e: UnsatisfiedLinkError) {
            FlossLogger.e(TAG, "libmicroscope_cv failed to load (check ABI and NDK build)", e)
            throw e
        }
    }

    external fun createArenaNative(width: Int, height: Int, expectedFrames: Int): Long

    external fun pushFrameToArenaNative(
        arenaHandle: Long,
        frameRgbBytes: ByteArray,
        homography3x3Flat: DoubleArray
    )

    external fun executeArenaFusionAndSRNative(
        arenaHandle: Long,
        mode: Int,
        enableSuperRes: Boolean,
        outWidth: Int,
        outHeight: Int,
        outputRgbBuffer: ByteArray,
        outputDepthMapBuffer: ByteArray
    )

    external fun destroyArenaNative(arenaHandle: Long)

    external fun demosaicRaw16ToRgbNative(
        raw16BayerBuffer: ShortArray,
        width: Int,
        height: Int,
        bayerPattern: Int,
        blackLevel: Int,
        whiteLevel: Int,
        outputRgb8Buffer: ByteArray
    )

    external fun enhanceMicroscopeNative(
        rgbBuffer: ByteArray,
        width: Int,
        height: Int,
        rlIterations: Int,
        psfAngleRad: Float,
        psfLengthPx: Float,
        applyRetinex: Boolean,
        correctOptics: Boolean
    )
}
