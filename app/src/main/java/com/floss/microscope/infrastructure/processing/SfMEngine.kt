package com.floss.microscope.infrastructure.processing

import com.floss.microscope.data.storage.FileStorageManager
import com.floss.microscope.domain.model.*
import org.opencv.calib3d.Calib3d
import org.opencv.calib3d.StereoSGBM
import org.opencv.core.*
import org.opencv.features2d.AKAZE
import org.opencv.features2d.DescriptorMatcher
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

@Singleton
class SfMEngine @Inject constructor(
    private val storageManager: FileStorageManager
) {
    data class SfMResult(
        val pointCloud: PointCloud,
        val faces: List<TriangleFace>,
        val orthophotoPath: String,
        val denseDepthPath: String
    )

    fun reconstructFullPhotogrammetry(
        sessionId: UUID,
        frames: List<Frame>,
        dfdDepthBytes: ByteArray?,
        dfdWidth: Int,
        dfdHeight: Int
    ): SfMResult {
        val meshVertices = mutableListOf<Point3D>()
        val meshFaces = mutableListOf<TriangleFace>()
        var baselineMm = 1.25f

        val refImg = Imgcodecs.imread(frames.first().filePath, Imgcodecs.IMREAD_COLOR)
        val orthophotoFile = File(storageManager.getSessionDirectory(sessionId), "orthophoto_topdown.png")
        val depthMapFile = File(storageManager.getSessionDirectory(sessionId), "depth_map.png")
        var orthoSaved = false

        if (frames.size >= 2) {
            val targetIdx = (frames.size * 3 / 4).coerceIn(1, frames.lastIndex)
            val pairImg = Imgcodecs.imread(frames[targetIdx].filePath, Imgcodecs.IMREAD_COLOR)

            val gray1 = Mat()
            val gray2 = Mat()
            Imgproc.cvtColor(refImg, gray1, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(pairImg, gray2, Imgproc.COLOR_BGR2GRAY)

            val akaze = AKAZE.create()
            val kp1 = MatOfKeyPoint()
            val desc1 = Mat()
            val kp2 = MatOfKeyPoint()
            val desc2 = Mat()
            akaze.detectAndCompute(gray1, Mat(), kp1, desc1)
            akaze.detectAndCompute(gray2, Mat(), kp2, desc2)

            if (!desc1.empty() && !desc2.empty()) {
                val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
                val knn = mutableListOf<MatOfDMatch>()
                matcher.knnMatch(desc1, desc2, knn, 2)

                val pts1List = mutableListOf<Point>()
                val pts2List = mutableListOf<Point>()
                val kp1Arr = kp1.toArray()
                val kp2Arr = kp2.toArray()
                var parallaxAcc = 0.0

                for (m in knn) {
                    val arr = m.toArray()
                    if (arr.size >= 2 && arr[0].distance < 0.76f * arr[1].distance) {
                        val p1 = kp1Arr[arr[0].queryIdx].pt
                        val p2 = kp2Arr[arr[0].trainIdx].pt
                        pts1List.add(p1)
                        pts2List.add(p2)
                        parallaxAcc += hypot(p1.x - p2.x, p1.y - p2.y)
                    }
                    m.release()
                }

                if (pts1List.size >= 14) {
                    baselineMm = ((parallaxAcc / pts1List.size) * 0.015).toFloat().coerceIn(0.25f, 8.0f)

                    val focal = max(refImg.cols(), refImg.rows()) * 0.92
                    val cx = refImg.cols() * 0.5
                    val cy = refImg.rows() * 0.5
                    val kMat = Mat.eye(3, 3, CvType.CV_64F).apply {
                        put(0, 0, focal); put(1, 1, focal)
                        put(0, 2, cx);    put(1, 2, cy)
                    }

                    val matPts1 = MatOfPoint2f(*pts1List.toTypedArray())
                    val matPts2 = MatOfPoint2f(*pts2List.toTypedArray())
                    val inlierMask = Mat()

                    val essential = Calib3d.findEssentialMat(
                        matPts1, matPts2, kMat, Calib3d.RANSAC, 0.999, 1.2, 1000, inlierMask
                    )

                    if (!essential.empty() && essential.rows() == 3) {
                        val rMat = Mat()
                        val tMat = Mat()
                        Calib3d.recoverPose(essential, matPts1, matPts2, kMat, rMat, tMat, inlierMask)

                        val p1Proj = Mat.zeros(3, 4, CvType.CV_64F)
                        for (r in 0..2) for (c in 0..2) p1Proj.put(r, c, kMat.get(r, c)[0])

                        val rtMat = Mat(3, 4, CvType.CV_64F)
                        for (r in 0..2) {
                            for (c in 0..2) rtMat.put(r, c, rMat.get(r, c)[0])
                            rtMat.put(r, 3, tMat.get(r, 0)[0])
                        }
                        val p2Proj = Mat()
                        Core.gemm(kMat, rtMat, 1.0, Mat(), 0.0, p2Proj)

                        val pts4D = Mat()
                        Calib3d.triangulatePoints(p1Proj, p2Proj, matPts1, matPts2, pts4D)

                        // Exact 3x3 Levenberg-Marquardt Bundle Adjustment with J2 = J_proj * R
                        val sparse3D = refineTriangulatedPointsLevenbergMarquardt(
                            pts4D = pts4D,
                            obs1 = pts1List,
                            obs2 = pts2List,
                            rMat = rMat,
                            tMat = tMat,
                            focal = focal,
                            cx = cx,
                            cy = cy
                        )

                        if (sparse3D.size >= 12) {
                            val normal = fitPlaneRansacNormal(sparse3D)
                            val hOrtho = computeOrthophotoHomographyFromNormal(normal, kMat)
                            val orthoMat = Mat()
                            Imgproc.warpPerspective(
                                refImg, orthoMat, hOrtho, refImg.size(),
                                Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE
                            )
                            Imgcodecs.imwrite(orthophotoFile.absolutePath, orthoMat)
                            orthoMat.release()
                            hOrtho.release()
                            orthoSaved = true
                        }

                        // Hartley Uncalibrated Epipolar Rectification before StereoSGBM
                        val rectGray1 = Mat()
                        val rectGray2 = Mat()
                        val hRect1 = Mat()
                        val hRect2 = Mat()
                        val fMat = Calib3d.findFundamentalMat(matPts1, matPts2, Calib3d.FM_RANSAC, 1.5, 0.99)

                        val rectifiedOk = if (!fMat.empty() && fMat.rows() == 3) {
                            Calib3d.stereoRectifyUncalibrated(matPts1, matPts2, fMat, refImg.size(), hRect1, hRect2, 3.0)
                        } else false

                        if (rectifiedOk && !hRect1.empty() && !hRect2.empty()) {
                            Imgproc.warpPerspective(gray1, rectGray1, hRect1, gray1.size())
                            Imgproc.warpPerspective(gray2, rectGray2, hRect2, gray2.size())
                        } else {
                            gray1.copyTo(rectGray1)
                            gray2.copyTo(rectGray2)
                        }

                        val sgbm = StereoSGBM.create(
                            0, 64, 5, 200, 800, 1, 63, 10, 100, 32, StereoSGBM.MODE_SGBM_3WAY
                        )
                        val dispRect16S = Mat()
                        sgbm.compute(rectGray1, rectGray2, dispRect16S)

                        val disp16S = Mat()
                        if (rectifiedOk && !hRect1.empty()) {
                            Imgproc.warpPerspective(
                                dispRect16S, disp16S, hRect1, gray1.size(),
                                Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE
                            )
                        } else {
                            dispRect16S.copyTo(disp16S)
                        }

                        val disp8U = Mat()
                        Core.normalize(disp16S, disp8U, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)

                        val colorDepth = Mat()
                        Imgproc.applyColorMap(disp8U, colorDepth, Imgproc.COLORMAP_TURBO)
                        Imgcodecs.imwrite(depthMapFile.absolutePath, colorDepth)

                        buildConnected3DMesh(
                            bgr = refImg,
                            sgbmDisp8U = disp8U,
                            dfdDepthBytes = dfdDepthBytes,
                            dfdW = dfdWidth,
                            dfdH = dfdHeight,
                            outPoints = meshVertices,
                            outFaces = meshFaces
                        )

                        rectGray1.release(); rectGray2.release()
                        hRect1.release(); hRect2.release(); fMat.release()
                        dispRect16S.release(); disp16S.release()
                        disp8U.release(); colorDepth.release()
                        p1Proj.release(); rtMat.release(); p2Proj.release(); pts4D.release()
                    }
                    kMat.release(); matPts1.release(); matPts2.release(); inlierMask.release(); essential.release()
                }
            }
            kp1.release(); desc1.release(); kp2.release(); desc2.release()
            pairImg.release(); gray1.release(); gray2.release()
        }

        if (!orthoSaved) {
            Imgcodecs.imwrite(orthophotoFile.absolutePath, refImg)
        }

        if (meshVertices.isEmpty()) {
            val dispFallback = Mat(refImg.rows(), refImg.cols(), CvType.CV_8UC1, Scalar(128.0))
            if (dfdDepthBytes != null && dfdWidth == refImg.cols() && dfdHeight == refImg.rows()) {
                dispFallback.put(0, 0, dfdDepthBytes)
            }
            val colorDepth = Mat()
            Imgproc.applyColorMap(dispFallback, colorDepth, Imgproc.COLORMAP_TURBO)
            Imgcodecs.imwrite(depthMapFile.absolutePath, colorDepth)

            buildConnected3DMesh(
                bgr = refImg,
                sgbmDisp8U = dispFallback,
                dfdDepthBytes = dfdDepthBytes,
                dfdW = dfdWidth,
                dfdH = dfdHeight,
                outPoints = meshVertices,
                outFaces = meshFaces
            )
            dispFallback.release()
            colorDepth.release()
        }

        refImg.release()

        val plyFile = storageManager.getPlyOutputFile(sessionId)
        val objFile = storageManager.getObjOutputFile(sessionId)
        writeMeshPlyFile(plyFile, meshVertices, meshFaces)
        writeMeshObjFile(objFile, meshVertices, meshFaces)

        return SfMResult(
            pointCloud = PointCloud(
                sessionId = sessionId,
                points = meshVertices,
                plyFilePath = plyFile.absolutePath,
                objFilePath = objFile.absolutePath,
                cameraBaselineMm = baselineMm
            ),
            faces = meshFaces,
            orthophotoPath = orthophotoFile.absolutePath,
            denseDepthPath = depthMapFile.absolutePath
        )
    }

    /**
     * Exact 3x3 Levenberg-Marquardt Bundle Adjustment solving:
     * (J1^T J1 + J2^T J2 + lambda I) dX = J1^T r1 + J2^T r2, where J2 = J_proj(X2) * R.
     */
    private fun refineTriangulatedPointsLevenbergMarquardt(
        pts4D: Mat,
        obs1: List<Point>,
        obs2: List<Point>,
        rMat: Mat,
        tMat: Mat,
        focal: Double,
        cx: Double,
        cy: Double
    ): List<DoubleArray> {
        val R = DoubleArray(9)
        rMat.get(0, 0, R)
        val tx = tMat.get(0, 0)[0]
        val ty = tMat.get(1, 0)[0]
        val tz = tMat.get(2, 0)[0]

        val validPoints = mutableListOf<DoubleArray>()

        fun reprojectionSqError(X: Double, Y: Double, Z: Double, u1: Double, v1: Double, u2: Double, v2: Double): Double {
            if (Z <= 0.05) return Double.MAX_VALUE
            val X2 = R[0] * X + R[1] * Y + R[2] * Z + tx
            val Y2 = R[3] * X + R[4] * Y + R[5] * Z + ty
            val Z2 = R[6] * X + R[7] * Y + R[8] * Z + tz
            if (Z2 <= 0.05) return Double.MAX_VALUE
            val e1x = u1 - (focal * (X / Z) + cx)
            val e1y = v1 - (focal * (Y / Z) + cy)
            val e2x = u2 - (focal * (X2 / Z2) + cx)
            val e2y = v2 - (focal * (Y2 / Z2) + cy)
            return e1x * e1x + e1y * e1y + e2x * e2x + e2y * e2y
        }

        for (col in 0 until pts4D.cols()) {
            val w = pts4D.get(3, col)[0]
            if (abs(w) < 1e-6) continue

            var X = pts4D.get(0, col)[0] / w
            var Y = pts4D.get(1, col)[0] / w
            var Z = pts4D.get(2, col)[0] / w
            if (!X.isFinite() || !Y.isFinite() || !Z.isFinite() || Z <= 0.05 || Z > 100.0) continue

            val u1 = obs1[col].x; val v1 = obs1[col].y
            val u2 = obs2[col].x; val v2 = obs2[col].y

            for (iter in 0 until 5) {
                val curErr = reprojectionSqError(X, Y, Z, u1, v1, u2, v2)
                if (curErr == Double.MAX_VALUE || curErr < 1e-6) break

                val invZ1 = 1.0 / Z
                val j1_00 = focal * invZ1; val j1_02 = -focal * X * invZ1 * invZ1
                val j1_11 = focal * invZ1; val j1_12 = -focal * Y * invZ1 * invZ1
                val r1x = u1 - (focal * X * invZ1 + cx)
                val r1y = v1 - (focal * Y * invZ1 + cy)

                val X2 = R[0] * X + R[1] * Y + R[2] * Z + tx
                val Y2 = R[3] * X + R[4] * Y + R[5] * Z + ty
                val Z2 = R[6] * X + R[7] * Y + R[8] * Z + tz
                val invZ2 = 1.0 / Z2

                val jp_00 = focal * invZ2; val jp_02 = -focal * X2 * invZ2 * invZ2
                val jp_11 = focal * invZ2; val jp_12 = -focal * Y2 * invZ2 * invZ2

                // J2 = J_proj(X2) * R (2x3 matrix)
                val j2_00 = jp_00 * R[0] + jp_02 * R[6]
                val j2_01 = jp_00 * R[1] + jp_02 * R[7]
                val j2_02 = jp_00 * R[2] + jp_02 * R[8]
                val j2_10 = jp_11 * R[3] + jp_12 * R[6]
                val j2_11 = jp_11 * R[4] + jp_12 * R[7]
                val j2_12 = jp_11 * R[5] + jp_12 * R[8]

                val r2x = u2 - (focal * X2 * invZ2 + cx)
                val r2y = v2 - (focal * Y2 * invZ2 + cy)

                // Normal matrix A = J1^T J1 + J2^T J2 + lambda I (3x3 symmetric)
                val lam = 1e-3
                val a00 = j1_00 * j1_00 + j2_00 * j2_00 + j2_10 * j2_10 + lam
                val a01 = j2_00 * j2_01 + j2_10 * j2_11
                val a02 = j1_00 * j1_02 + j2_00 * j2_02 + j2_10 * j2_12
                val a11 = j1_11 * j1_11 + j2_01 * j2_01 + j2_11 * j2_11 + lam
                val a12 = j1_11 * j1_12 + j2_01 * j2_02 + j2_11 * j2_12
                val a22 = j1_02 * j1_02 + j1_12 * j1_12 + j2_02 * j2_02 + j2_12 * j2_12 + lam

                val b0 = j1_00 * r1x + j2_00 * r2x + j2_10 * r2y
                val b1 = j1_11 * r1y + j2_01 * r2x + j2_11 * r2y
                val b2 = j1_02 * r1x + j1_12 * r1y + j2_02 * r2x + j2_12 * r2y

                val detA = a00 * (a11 * a22 - a12 * a12) -
                           a01 * (a01 * a22 - a12 * a02) +
                           a02 * (a01 * a12 - a11 * a02)
                if (abs(detA) < 1e-12) break
                val invDet = 1.0 / detA

                val dX = (b0 * (a11 * a22 - a12 * a12) + b1 * (a02 * a12 - a01 * a22) + b2 * (a01 * a12 - a02 * a11)) * invDet
                val dY = (b0 * (a12 * a02 - a01 * a22) + b1 * (a00 * a22 - a02 * a02) + b2 * (a01 * a02 - a00 * a12)) * invDet
                val dZ = (b0 * (a01 * a12 - a11 * a02) + b1 * (a01 * a02 - a00 * a12) + b2 * (a00 * a11 - a01 * a01)) * invDet

                if (reprojectionSqError(X + dX, Y + dY, Z + dZ, u1, v1, u2, v2) < curErr) {
                    X += dX; Y += dY; Z += dZ
                }
            }

            if (reprojectionSqError(X, Y, Z, u1, v1, u2, v2) < 16.0) {
                validPoints.add(doubleArrayOf(X, Y, Z))
            }
        }
        return validPoints
    }

    private fun fitPlaneRansacNormal(pts: List<DoubleArray>): DoubleArray {
        val rng = Random(42)
        var bestInliers = -1
        var bestNormal = doubleArrayOf(0.0, 0.0, 1.0)

        for (iter in 0 until 45) {
            val p1 = pts[rng.nextInt(pts.size)]
            val p2 = pts[rng.nextInt(pts.size)]
            val p3 = pts[rng.nextInt(pts.size)]

            val ux = p2[0] - p1[0]; val uy = p2[1] - p1[1]; val uz = p2[2] - p1[2]
            val vx = p3[0] - p1[0]; val vy = p3[1] - p1[1]; val vz = p3[2] - p1[2]

            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val norm = sqrt(nx * nx + ny * ny + nz * nz)
            if (norm < 1e-6) continue
            nx /= norm; ny /= norm; nz /= norm
            if (nz < 0) { nx = -nx; ny = -ny; nz = -nz }

            val d = -(nx * p1[0] + ny * p1[1] + nz * p1[2])
            var inliers = 0
            for (pt in pts) {
                if (abs(nx * pt[0] + ny * pt[1] + nz * pt[2] + d) < 0.08) inliers++
            }
            if (inliers > bestInliers) {
                bestInliers = inliers
                bestNormal = doubleArrayOf(nx, ny, nz)
            }
        }
        return bestNormal
    }

    private fun computeOrthophotoHomographyFromNormal(normal: DoubleArray, kMat: Mat): Mat {
        val nx = normal[0].coerceIn(-0.22, 0.22)
        val ny = normal[1].coerceIn(-0.22, 0.22)
        val rVec = Mat(3, 1, CvType.CV_64F).apply {
            put(0, 0, -ny); put(1, 0, nx); put(2, 0, 0.0)
        }
        val rOrtho = Mat()
        Calib3d.Rodrigues(rVec, rOrtho)

        val kInv = kMat.inv()
        val temp = Mat()
        val hOrtho = Mat()
        Core.gemm(kMat, rOrtho, 1.0, Mat(), 0.0, temp)
        Core.gemm(temp, kInv, 1.0, Mat(), 0.0, hOrtho)

        rVec.release(); rOrtho.release(); kInv.release(); temp.release()
        return hOrtho
    }

    private fun buildConnected3DMesh(
        bgr: Mat,
        sgbmDisp8U: Mat,
        dfdDepthBytes: ByteArray?,
        dfdW: Int,
        dfdH: Int,
        outPoints: MutableList<Point3D>,
        outFaces: MutableList<TriangleFace>
    ) {
        val gridCols = 64
        val gridRows = 64
        val stepX = max(1, bgr.cols() / gridCols)
        val stepY = max(1, bgr.rows() / gridRows)

        val actualCols = bgr.cols() / stepX
        val actualRows = bgr.rows() / stepY
        val cx = bgr.cols() * 0.5f
        val cy = bgr.rows() * 0.5f
        val xyScale = 2.0f / max(bgr.cols(), bgr.rows())

        for (gy in 0 until actualRows) {
            for (gx in 0 until actualCols) {
                val px = (gx * stepX).coerceIn(0, bgr.cols() - 1)
                val py = (gy * stepY).coerceIn(0, bgr.rows() - 1)

                val color = bgr.get(py, px)
                val b = color[0].toInt()
                val g = color[1].toInt()
                val r = color[2].toInt()

                val sgbmZ = (sgbmDisp8U.get(py, px)[0].toFloat() / 255.0f) - 0.5f
                val dfdZ = if (dfdDepthBytes != null && py < dfdH && px < dfdW) {
                    ((dfdDepthBytes[py * dfdW + px].toInt() and 0xFF) / 255.0f) - 0.5f
                } else sgbmZ
                val fusedZ = (0.55f * sgbmZ + 0.45f * dfdZ) * 0.55f

                outPoints.add(
                    Point3D(
                        x = (px - cx) * xyScale,
                        y = -(py - cy) * xyScale,
                        z = fusedZ,
                        r = r,
                        g = g,
                        b = b
                    )
                )
            }
        }

        for (gy in 0 until actualRows - 1) {
            for (gx in 0 until actualCols - 1) {
                val i00 = gy * actualCols + gx
                val i10 = gy * actualCols + (gx + 1)
                val i01 = (gy + 1) * actualCols + gx
                val i11 = (gy + 1) * actualCols + (gx + 1)
                outFaces.add(TriangleFace(i00, i01, i10))
                outFaces.add(TriangleFace(i10, i01, i11))
            }
        }
    }

    private fun writeMeshPlyFile(file: File, points: List<Point3D>, faces: List<TriangleFace>) {
        file.bufferedWriter().use { out ->
            out.write("ply\nformat ascii 1.0\n")
            out.write("element vertex ${points.size}\n")
            out.write("property float x\nproperty float y\nproperty float z\n")
            out.write("property uchar red\nproperty uchar green\nproperty uchar blue\n")
            out.write("element face ${faces.size}\n")
            out.write("property list uchar int vertex_indices\nend_header\n")
            for (pt in points) out.write("${pt.x} ${pt.y} ${pt.z} ${pt.r} ${pt.g} ${pt.b}\n")
            for (f in faces) out.write("3 ${f.v1} ${f.v2} ${f.v3}\n")
        }
    }

    private fun writeMeshObjFile(file: File, points: List<Point3D>, faces: List<TriangleFace>) {
        file.bufferedWriter().use { out ->
            out.write("# FLOSS Android Microscope Triangulated 3D Specimen Mesh\n")
            for (pt in points) {
                out.write("v ${pt.x} ${pt.y} ${pt.z} ${pt.r / 255f} ${pt.g / 255f} ${pt.b / 255f}\n")
            }
            for (f in faces) {
                out.write("f ${f.v1 + 1} ${f.v2 + 1} ${f.v3 + 1}\n")
            }
        }
    }
}
