package com.floss.microscope.infrastructure.processing

import com.floss.microscope.domain.model.AlignedStack
import com.floss.microscope.domain.model.Frame
import com.floss.microscope.domain.model.FusionMethod
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MultiFrameFusion @Inject constructor() {

    data class FusionOutput(
        val fusedBgrMat: Mat,
        val dfdDepthMapBytes: ByteArray,
        val baseWidth: Int,
        val baseHeight: Int
    )

    /**
     * Streams UNWARPED raw frames + 3x3 Homographies (Target_f -> Reference_0) into the C++
     * NativeFrameArena one-by-one. Inside C++:
     *  - 1x aligned frames are generated via H_inv for Pyramid Focus Stacking / Median / DfD.
     *  - Sub-Pixel Super-Resolution pulls directly from the unwarped raw frames via H_inv
     *    using exact pixel-center projection rx = (hx + 0.5)/scale - 0.5, eliminating double-warping!
     */
    fun fuseStackStreaming(
        rawFrames: List<Frame>,
        stack: AlignedStack,
        method: FusionMethod,
        enableSuperRes: Boolean
    ): FusionOutput {
        require(rawFrames.isNotEmpty()) { "rawFrames cannot be empty" }

        val firstBgr = Imgcodecs.imread(rawFrames.first().filePath, Imgcodecs.IMREAD_COLOR)
        val baseW = firstBgr.cols()
        val baseH = firstBgr.rows()
        firstBgr.release()

        val arenaHandle = NativeCvBridge.createArenaNative(baseW, baseH, rawFrames.size)
        val singleFrameBytes = ByteArray(baseW * baseH * 3)

        try {
            for (i in rawFrames.indices) {
                val rawBgr = Imgcodecs.imread(rawFrames[i].filePath, Imgcodecs.IMREAD_COLOR)
                val rawRgb = Mat()
                Imgproc.cvtColor(rawBgr, rawRgb, Imgproc.COLOR_BGR2RGB)
                rawBgr.release()

                rawRgb.get(0, 0, singleFrameBytes)
                rawRgb.release()

                val hFlat = stack.homographyMatricesFlat.getOrElse(i) {
                    doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
                }
                NativeCvBridge.pushFrameToArenaNative(arenaHandle, singleFrameBytes, hFlat)
            }

            val outW = if (enableSuperRes) (baseW * 1.5).toInt() else baseW
            val outH = if (enableSuperRes) (baseH * 1.5).toInt() else baseH

            val outRgbBytes = ByteArray(outW * outH * 3)
            val dfdDepthBytes = ByteArray(baseW * baseH)

            val nativeMode = when (method) {
                FusionMethod.TEMPORAL_AVERAGE -> 0
                FusionMethod.TEMPORAL_MEDIAN -> 1
                FusionMethod.FOCUS_STACKING, FusionMethod.EXPOSURE_HDR -> 2
            }

            NativeCvBridge.executeArenaFusionAndSRNative(
                arenaHandle = arenaHandle,
                mode = nativeMode,
                enableSuperRes = enableSuperRes,
                outWidth = outW,
                outHeight = outH,
                outputRgbBuffer = outRgbBytes,
                outputDepthMapBuffer = dfdDepthBytes
            )

            if (method == FusionMethod.EXPOSURE_HDR && !enableSuperRes && stack.alignedFramePaths.size >= 2) {
                val alignedMats = stack.alignedFramePaths.map { Imgcodecs.imread(it, Imgcodecs.IMREAD_COLOR) }
                val mertens = Photo.createMergeMertens()
                val hdrFloat = Mat()
                mertens.process(alignedMats, hdrFloat)
                val hdr8U = Mat()
                hdrFloat.convertTo(hdr8U, CvType.CV_8UC3, 255.0)
                hdrFloat.release()
                alignedMats.forEach { it.release() }
                return FusionOutput(hdr8U, dfdDepthBytes, baseW, baseH)
            }

            val outRgbMat = Mat(outH, outW, CvType.CV_8UC3)
            outRgbMat.put(0, 0, outRgbBytes)
            val outBgrMat = Mat()
            Imgproc.cvtColor(outRgbMat, outBgrMat, Imgproc.COLOR_RGB2BGR)
            outRgbMat.release()

            return FusionOutput(outBgrMat, dfdDepthBytes, baseW, baseH)
        } finally {
            NativeCvBridge.destroyArenaNative(arenaHandle)
        }
    }
}
