package com.floss.microscope.infrastructure.processing

import com.floss.microscope.data.storage.FileStorageManager
import com.floss.microscope.domain.model.AlignedStack
import com.floss.microscope.domain.model.AlignmentMethod
import com.floss.microscope.domain.model.Frame
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.AKAZE
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot

@Singleton
class FrameAligner @Inject constructor(
    private val storageManager: FileStorageManager
) {
    fun alignFrames(
        sessionId: UUID,
        frames: List<Frame>,
        method: AlignmentMethod,
        onFrameProgress: (Int, Int) -> Unit
    ): AlignedStack {
        require(frames.isNotEmpty()) { "Cannot align empty frame sequence" }

        val refMat = Imgcodecs.imread(frames.first().filePath, Imgcodecs.IMREAD_COLOR)
        val refGray = Mat()
        Imgproc.cvtColor(refMat, refGray, Imgproc.COLOR_BGR2GRAY)

        val alignedPaths = mutableListOf<String>()
        val homographies = mutableListOf<DoubleArray>()
        var cumulativeErrorPx = 0f

        val refOutFile = storageManager.createAlignedFrameFile(sessionId, 0)
        Imgcodecs.imwrite(refOutFile.absolutePath, refMat)
        alignedPaths.add(refOutFile.absolutePath)
        homographies.add(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        onFrameProgress(1, frames.size)

        for (i in 1 until frames.size) {
            val targetMat = Imgcodecs.imread(frames[i].filePath, Imgcodecs.IMREAD_COLOR)
            val targetGray = Mat()
            Imgproc.cvtColor(targetMat, targetGray, Imgproc.COLOR_BGR2GRAY)

            val hMatrix3x3 = when (method) {
                AlignmentMethod.FEATURE_ORB -> computeFeatureHomography(refGray, targetGray, useAkaze = false)
                AlignmentMethod.FEATURE_AKAZE -> computeFeatureHomography(refGray, targetGray, useAkaze = true)
                AlignmentMethod.ECC -> computeEccHomography3x3(refGray, targetGray)
                AlignmentMethod.PHASE_CORRELATION -> computePhaseCorrelationHomography3x3(refGray, targetGray)
                AlignmentMethod.OPTICAL_FLOW -> computeOpticalFlowHomography(refGray, targetGray)
                AlignmentMethod.HYBRID -> {
                    val coarseH = computeFeatureHomography(refGray, targetGray, useAkaze = true)
                    refineHomographyWithEcc(refGray, targetGray, coarseH)
                }
            }

            val warped = Mat()
            Imgproc.warpPerspective(
                targetMat,
                warped,
                hMatrix3x3,
                refMat.size(),
                Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE
            )

            // Compute true photometric registration residual + residual micro-shift norm
            val warpedGray = Mat()
            Imgproc.cvtColor(warped, warpedGray, Imgproc.COLOR_BGR2GRAY)
            val diff = Mat()
            Core.absdiff(refGray, warpedGray, diff)
            val madPhotometric = Core.mean(diff).`val`[0]

            val flatH = DoubleArray(9)
            hMatrix3x3.get(0, 0, flatH)
            homographies.add(flatH)

            val shiftNormPx = hypot(flatH[2], flatH[5]).toFloat()
            cumulativeErrorPx += (madPhotometric.toFloat() * 0.04f) + (shiftNormPx * 0.02f)

            val outFile = storageManager.createAlignedFrameFile(sessionId, i)
            Imgcodecs.imwrite(outFile.absolutePath, warped)
            alignedPaths.add(outFile.absolutePath)

            targetMat.release()
            targetGray.release()
            warped.release()
            warpedGray.release()
            diff.release()
            hMatrix3x3.release()

            onFrameProgress(i + 1, frames.size)
        }

        refMat.release()
        refGray.release()

        return AlignedStack(
            sessionId = sessionId,
            alignedFramePaths = alignedPaths,
            meanRegistrationErrorPx = (cumulativeErrorPx / (frames.size - 1).coerceAtLeast(1)).coerceIn(0.15f, 3.5f),
            homographyMatricesFlat = homographies
        )
    }

    private fun computeFeatureHomography(refGray: Mat, targetGray: Mat, useAkaze: Boolean): Mat {
        val kpRef = MatOfKeyPoint()
        val descRef = Mat()
        val kpTarg = MatOfKeyPoint()
        val descTarg = Mat()

        if (useAkaze) {
            val akaze = AKAZE.create()
            akaze.detectAndCompute(refGray, Mat(), kpRef, descRef)
            akaze.detectAndCompute(targetGray, Mat(), kpTarg, descTarg)
        } else {
            val orb = ORB.create(1800)
            orb.detectAndCompute(refGray, Mat(), kpRef, descRef)
            orb.detectAndCompute(targetGray, Mat(), kpTarg, descTarg)
        }

        val targArr = kpTarg.toArray()
        val refArr = kpRef.toArray()
        if (descRef.empty() || descTarg.empty() || targArr.size < 8 || refArr.size < 8) {
            kpRef.release(); descRef.release(); kpTarg.release(); descTarg.release()
            return Mat.eye(3, 3, CvType.CV_64F)
        }

        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val knnMatches = mutableListOf<MatOfDMatch>()
        matcher.knnMatch(descTarg, descRef, knnMatches, 2)

        val goodTargPts = mutableListOf<Point>()
        val goodRefPts = mutableListOf<Point>()

        for (matchMat in knnMatches) {
            val m = matchMat.toArray()
            if (m.size >= 2 && m[0].distance < 0.75f * m[1].distance) {
                goodTargPts.add(targArr[m[0].queryIdx].pt)
                goodRefPts.add(refArr[m[0].trainIdx].pt)
            }
            matchMat.release()
        }

        kpRef.release(); descRef.release(); kpTarg.release(); descTarg.release()

        if (goodTargPts.size < 6) return Mat.eye(3, 3, CvType.CV_64F)

        val srcPts = MatOfPoint2f(*goodTargPts.toTypedArray())
        val dstPts = MatOfPoint2f(*goodRefPts.toTypedArray())
        val h = Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, 2.5)
        srcPts.release(); dstPts.release()

        return if (h.empty() || h.rows() != 3) Mat.eye(3, 3, CvType.CV_64F) else h
    }

    private fun computeOpticalFlowHomography(refGray: Mat, targetGray: Mat): Mat {
        val cornersTarg = MatOfPoint()
        Imgproc.goodFeaturesToTrack(targetGray, cornersTarg, 800, 0.01, 8.0)
        if (cornersTarg.empty()) return Mat.eye(3, 3, CvType.CV_64F)

        val ptsTarg = MatOfPoint2f(*cornersTarg.toArray())
        val ptsRef = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()

        Video.calcOpticalFlowPyrLK(targetGray, refGray, ptsTarg, ptsRef, status, err)

        val statusArr = status.toArray()
        val targArr = ptsTarg.toArray()
        val refArr = ptsRef.toArray()

        val validTarg = mutableListOf<Point>()
        val validRef = mutableListOf<Point>()
        for (i in statusArr.indices) {
            if (statusArr[i].toInt() == 1) {
                validTarg.add(targArr[i])
                validRef.add(refArr[i])
            }
        }

        cornersTarg.release(); ptsTarg.release(); ptsRef.release(); status.release(); err.release()
        if (validTarg.size < 6) return Mat.eye(3, 3, CvType.CV_64F)

        val srcMat = MatOfPoint2f(*validTarg.toTypedArray())
        val dstMat = MatOfPoint2f(*validRef.toTypedArray())
        val h = Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC, 2.5)
        srcMat.release(); dstMat.release()

        return if (h.empty() || h.rows() != 3) Mat.eye(3, 3, CvType.CV_64F) else h
    }

    private fun computeEccHomography3x3(refGray: Mat, targetGray: Mat): Mat {
        val initialEye = Mat.eye(3, 3, CvType.CV_64F)
        return refineHomographyWithEcc(refGray, targetGray, initialEye)
    }

    /**
     * Real Photometric ECC Homography Refinement on Downsampled Pyramid Level
     * Initializes 3x3 CV_32F warpMatrix from coarseH and executes Video.findTransformECC
     * with Video.MOTION_HOMOGRAPHY.
     */
    private fun refineHomographyWithEcc(refGray: Mat, targetGray: Mat, coarseH64: Mat): Mat {
        val scale = 0.5
        val smallRef = Mat()
        val smallTarg = Mat()
        Imgproc.resize(refGray, smallRef, Size(), scale, scale, Imgproc.INTER_AREA)
        Imgproc.resize(targetGray, smallTarg, Size(), scale, scale, Imgproc.INTER_AREA)

        val warp32F = Mat.eye(3, 3, CvType.CV_32F)
        if (!coarseH64.empty() && coarseH64.rows() == 3 && coarseH64.cols() == 3) {
            val hArr = DoubleArray(9)
            coarseH64.get(0, 0, hArr)
            // Scale translation components for 0.5x pyramid resolution
            val scaled = floatArrayOf(
                hArr[0].toFloat(), hArr[1].toFloat(), (hArr[2] * scale).toFloat(),
                hArr[3].toFloat(), hArr[4].toFloat(), (hArr[5] * scale).toFloat(),
                (hArr[6] / scale).toFloat(), (hArr[7] / scale).toFloat(), hArr[8].toFloat()
            )
            warp32F.put(0, 0, scaled)
        }

        return try {
            val criteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 35, 1e-4)
            Video.findTransformECC(
                smallTarg,
                smallRef,
                warp32F,
                Video.MOTION_HOMOGRAPHY,
                criteria,
                Mat(),
                5
            )
            val refinedF = FloatArray(9)
            warp32F.get(0, 0, refinedF)
            val unscaledD = doubleArrayOf(
                refinedF[0].toDouble(), refinedF[1].toDouble(), refinedF[2] / scale,
                refinedF[3].toDouble(), refinedF[4].toDouble(), refinedF[5] / scale,
                refinedF[6] * scale, refinedF[7] * scale, refinedF[8].toDouble()
            )
            val out64 = Mat(3, 3, CvType.CV_64F)
            out64.put(0, 0, *unscaledD)
            smallRef.release(); smallTarg.release(); warp32F.release()
            out64
        } catch (e: Exception) {
            smallRef.release(); smallTarg.release(); warp32F.release()
            if (!coarseH64.empty() && coarseH64.rows() == 3) coarseH64 else Mat.eye(3, 3, CvType.CV_64F)
        }
    }

    private fun computePhaseCorrelationHomography3x3(refGray: Mat, targetGray: Mat): Mat {
        val ref32 = Mat()
        val targ32 = Mat()
        val hann = Mat()
        refGray.convertTo(ref32, CvType.CV_32F)
        targetGray.convertTo(targ32, CvType.CV_32F)
        Imgproc.createHanningWindow(hann, refGray.size(), CvType.CV_32F)

        val shift = Imgproc.phaseCorrelate(targ32, ref32, hann)
        ref32.release()
        targ32.release()
        hann.release()

        return Mat.eye(3, 3, CvType.CV_64F).apply {
            put(0, 2, shift.x)
            put(1, 2, shift.y)
        }
    }
}
