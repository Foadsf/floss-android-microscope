package com.floss.microscope.infrastructure.processing

import android.net.Uri
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import com.floss.microscope.core.logging.FlossLogger
import com.floss.microscope.data.storage.FileStorageManager
import com.floss.microscope.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import org.opencv.imgcodecs.Imgcodecs
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.log10

@Singleton
class PipelineOrchestrator @Inject constructor(
    private val aligner: FrameAligner,
    private val fusion: MultiFrameFusion,
    private val sfmEngine: SfMEngine,
    private val enhancer: ImageEnhancer,
    private val storageManager: FileStorageManager
) {
    private companion object {
        const val TAG = "Pipeline"
    }

    fun process(
        session: CaptureSession,
        params: ProcessingParameters
    ): Flow<ProcessingStage> = channelFlow {
        val pipelineStart = System.nanoTime()
        try {
            val logs = mutableListOf<String>()
            val frames = session.frames
            FlossLogger.i(TAG, "start session=${session.id} frames=${frames.size} params=$params")
            if (frames.isEmpty()) {
                FlossLogger.w(TAG, "session ${session.id} has no frames")
                send(ProcessingStage.Failed("No frames captured in session."))
                return@channelFlow
            }

            // Stage 1: Multi-Frame Registration with live per-frame channelFlow emission
            var t = System.nanoTime()
            val alignedStack = aligner.alignFrames(
                sessionId = session.id,
                frames = frames,
                method = params.alignmentMethod
            ) { cur, tot ->
                val frac = 0.05f + 0.30f * (cur.toFloat() / tot.coerceAtLeast(1))
                trySend(
                    ProcessingStage.Aligning(
                        progressFraction = frac,
                        message = "Aligned frame $cur/$tot via ${params.alignmentMethod.name}",
                        currentFrame = cur,
                        totalFrames = tot
                    )
                )
                logs.add("Aligned frame $cur/$tot")
                FlossLogger.v(TAG, "aligned frame $cur/$tot")
            }
            currentCoroutineContext().ensureActive()
            FlossLogger.i(
                TAG,
                "stage1 align done in ${ms(t)} ms meanRegistrationErrorPx=${alignedStack.meanRegistrationErrorPx}"
            )

            val lastH = alignedStack.homographyMatricesFlat.lastOrNull()
            val dominantAngleRad = if (lastH != null && lastH.size >= 6) {
                atan2(lastH[5], lastH[2]).toFloat()
            } else 0.0f

            // Stage 2: Native Arena Streaming Multi-Frame Fusion + Sub-Pixel TV-IBP Super-Resolution
            send(
                ProcessingStage.Fusing(
                    progressFraction = 0.45f,
                    message = "Streaming Native Arena Fusion (${params.fusionMethod.name}) & Sub-Pixel SR...",
                    method = params.fusionMethod
                )
            )
            t = System.nanoTime()
            val fusionOut = fusion.fuseStackStreaming(
                rawFrames = frames,
                stack = alignedStack,
                method = params.fusionMethod,
                enableSuperRes = params.enableSuperRes
            )
            logs.add("Native Arena Fusion (${params.fusionMethod}) completed at ${fusionOut.fusedBgrMat.cols()}x${fusionOut.fusedBgrMat.rows()}")
            currentCoroutineContext().ensureActive()
            FlossLogger.i(
                TAG,
                "stage2 fusion done in ${ms(t)} ms size=${fusionOut.fusedBgrMat.cols()}x${fusionOut.fusedBgrMat.rows()}"
            )

            // Stage 3: Multi-View Photogrammetry (Levenberg-Marquardt BA + RANSAC Orthophoto + SGBM Dense Mesh)
            var pointCloud: PointCloud? = null
            if (params.enableSfM) {
                send(
                    ProcessingStage.ReconstructingSfM(
                        progressFraction = 0.68f,
                        message = "Running Levenberg-Marquardt Bundle Adjustment, Orthophoto & SGBM Mesh...",
                        trackedKeypoints = 2048
                    )
                )
                t = System.nanoTime()
                val sfmResult = sfmEngine.reconstructFullPhotogrammetry(
                    sessionId = session.id,
                    frames = frames,
                    dfdDepthBytes = fusionOut.dfdDepthMapBytes,
                    dfdWidth = fusionOut.baseWidth,
                    dfdHeight = fusionOut.baseHeight
                )
                pointCloud = sfmResult.pointCloud
                logs.add("SfM + SGBM reconstructed ${pointCloud.points.size} vertices & ${sfmResult.faces.size} faces (baseline ${pointCloud.cameraBaselineMm} mm)")
                FlossLogger.i(
                    TAG,
                    "stage3 sfm done in ${ms(t)} ms vertices=${pointCloud.points.size} faces=${sfmResult.faces.size}"
                )
            } else {
                FlossLogger.d(TAG, "stage3 sfm skipped (enableSfM=false)")
            }
            currentCoroutineContext().ensureActive()

            // Stage 4: Computational Microscopy Enhancement with live per-step emission
            t = System.nanoTime()
            val finalMat = enhancer.enhance(
                fusedBgr = fusionOut.fusedBgrMat,
                params = params,
                meanResidualShakePx = alignedStack.meanRegistrationErrorPx + 1.0f,
                dominantShakeAngleRad = dominantAngleRad
            ) { step ->
                trySend(
                    ProcessingStage.Enhancing(
                        progressFraction = 0.88f,
                        message = step,
                        currentStep = step
                    )
                )
                logs.add(step)
                FlossLogger.v(TAG, "enhance step: $step")
            }
            fusionOut.fusedBgrMat.release()
            FlossLogger.i(TAG, "stage4 enhance done in ${ms(t)} ms")

            val outFile = storageManager.getEnhancedOutputFile(session.id)
            val written = Imgcodecs.imwrite(outFile.absolutePath, finalMat)
            val width = finalMat.cols()
            val height = finalMat.rows()
            finalMat.release()
            if (!written) {
                throw IllegalStateException("OpenCV imwrite failed for ${outFile.absolutePath}")
            }
            FlossLogger.i(TAG, "wrote ${outFile.absolutePath} (${outFile.length()} bytes, ${width}x${height})")

            runCatching {
                val exif = ExifInterface(outFile.absolutePath)
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "FLOSS Android Microscope 1.0.0")
                exif.setAttribute(
                    ExifInterface.TAG_USER_COMMENT,
                    "Frames=${frames.size};Mode=${params.mode};Align=${params.alignmentMethod};Fusion=${params.fusionMethod}"
                )
                exif.saveAttributes()
            }.onFailure { FlossLogger.w(TAG, "EXIF annotation skipped", it) }

            val snrGainDb = (10.0 * log10(frames.size.toDouble()) + if (params.enableDenoise) 3.4 else 0.6).toFloat()

            val enhancedImage = EnhancedImage(
                sessionId = session.id,
                uri = Uri.fromFile(outFile),
                filePath = outFile.absolutePath,
                snrGainDb = snrGainDb,
                effectiveResolution = Size(width, height),
                processingLog = logs
            )

            val completedSession = session.copy(
                processingParameters = params,
                status = SessionStatus.COMPLETED,
                enhancedImage = enhancedImage,
                pointCloud = pointCloud
            )

            FlossLogger.i(TAG, "pipeline completed session=${session.id} total=${ms(pipelineStart)} ms")
            send(ProcessingStage.Completed(session = completedSession))
        } catch (e: CancellationException) {
            FlossLogger.w(TAG, "pipeline cancelled session=${session.id} after ${ms(pipelineStart)} ms")
            throw e
        } catch (e: Exception) {
            // Never swallow silently: the stack trace goes to logcat and the diagnostic log file.
            FlossLogger.e(TAG, "pipeline failed session=${session.id} after ${ms(pipelineStart)} ms", e)
            send(ProcessingStage.Failed("Pipeline failure: ${e.localizedMessage ?: e::class.java.simpleName}"))
        }
    }.flowOn(Dispatchers.Default)

    private fun ms(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L
}
