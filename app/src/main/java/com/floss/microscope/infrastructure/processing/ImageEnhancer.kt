package com.floss.microscope.infrastructure.processing

import com.floss.microscope.domain.model.ProcessingParameters
import com.floss.microscope.infrastructure.gpu.NNAPIWrapper
import com.floss.microscope.infrastructure.gpu.VulkanAccelerator
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageEnhancer @Inject constructor(
    private val vulkanAccelerator: VulkanAccelerator,
    private val nnapiWrapper: NNAPIWrapper
) {
    fun enhance(
        fusedBgr: Mat,
        params: ProcessingParameters,
        meanResidualShakePx: Float,
        dominantShakeAngleRad: Float,
        onStep: (String) -> Unit
    ): Mat {
        var working = fusedBgr.clone()

        // 1. Spatial Non-Local Means Denoising
        if (params.enableDenoise) {
            onStep("Non-Local Means Spatial-Chroma Denoising")
            val denoised = Mat()
            Photo.fastNlMeansDenoisingColored(working, denoised, 3.8f, 3.8f, 7, 15)
            working.release()
            working = denoised
        }

        // 2. Native Hot-Pixel Filter + Gray-World WB + Optical Correction + Anisotropic TV-RL Deconvolution + 3-Scale MSRCR
        onStep("TV-Regularized Richardson-Lucy Deconvolution & 3-Scale MSRCR")
        val rgbMat = Mat()
        Imgproc.cvtColor(working, rgbMat, Imgproc.COLOR_BGR2RGB)
        val byteLen = rgbMat.cols() * rgbMat.rows() * 3
        val rgbBytes = ByteArray(byteLen)
        rgbMat.get(0, 0, rgbBytes)

        NativeCvBridge.enhanceMicroscopeNative(
            rgbBuffer = rgbBytes,
            width = rgbMat.cols(),
            height = rgbMat.rows(),
            rlIterations = if (params.enableDeconvolution) params.richardsonLucyIterations else 0,
            psfAngleRad = dominantShakeAngleRad,
            psfLengthPx = meanResidualShakePx.coerceIn(1.0f, 4.0f),
            applyRetinex = params.enableRetinex,
            correctOptics = params.enableOpticalCorrection
        )

        rgbMat.put(0, 0, rgbBytes)
        Imgproc.cvtColor(rgbMat, working, Imgproc.COLOR_RGB2BGR)
        rgbMat.release()

        // 3. CLAHE in CIE LAB Luminance Space
        onStep("Adaptive Local Contrast (CLAHE)")
        val lab = Mat()
        Imgproc.cvtColor(working, lab, Imgproc.COLOR_BGR2Lab)
        val labChannels = mutableListOf<Mat>()
        Core.split(lab, labChannels)

        val clahe = Imgproc.createCLAHE(params.claheClipLimit, Size(8.0, 8.0))
        val lEnhanced = Mat()
        clahe.apply(labChannels[0], lEnhanced)
        labChannels[0].release()
        labChannels[0] = lEnhanced

        Core.merge(labChannels, lab)
        Imgproc.cvtColor(lab, working, Imgproc.COLOR_Lab2BGR)
        lab.release()
        labChannels.forEach { it.release() }

        // 4. GPU-Accelerated (UMat OpenCL/Vulkan) Unsharp Mask & Optional Neural Microstructure Refinement
        onStep("GPU Edge-Preserving Detail Boost")
        val gpuBoosted = vulkanAccelerator.executeEdgePreservingDetailBoost(working)
        working.release()
        working = nnapiWrapper.refineMicrostructure(gpuBoosted)
        gpuBoosted.release()

        return working
    }
}
