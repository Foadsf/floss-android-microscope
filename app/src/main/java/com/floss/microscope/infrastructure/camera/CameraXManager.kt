package com.floss.microscope.infrastructure.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.floss.microscope.data.storage.FileStorageManager
import com.floss.microscope.domain.model.*
import com.floss.microscope.domain.repository.CameraRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class CameraXManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilityDetector: CapabilityDetector,
    private val tremorAnalyzer: TremorAndPeakingAnalyzer,
    private val storageManager: FileStorageManager
) : CameraRepository {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private val _capabilities = MutableStateFlow(capabilityDetector.detectBackCameraCapabilities())
    override val capabilities: StateFlow<DeviceCapabilities> = _capabilities.asStateFlow()

    override val tremorTelemetry: StateFlow<TremorTelemetry> = tremorAnalyzer.telemetry

    override fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.setAnalyzer(cameraExecutor, tremorAnalyzer) }

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                imageAnalysis
            )
            tremorAnalyzer.startListening()
        }, ContextCompat.getMainExecutor(context))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    override fun applyCaptureParameters(params: CaptureParameters, zoomRatio: Float) {
        val cam = camera ?: return
        cam.cameraControl.setZoomRatio(zoomRatio.coerceIn(1.0f, _capabilities.value.maxZoomRatio))
        cam.cameraControl.setExposureCompensationIndex(params.exposureCompensationIndex)

        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val optionsBuilder = CaptureRequestOptions.Builder()

        params.manualFocusDistance?.let { focusDist ->
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                focusDist
            )
        } ?: run {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }

        params.isoOverride?.let { iso ->
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                iso
            )
        }

        camera2Control.setCaptureRequestOptions(optionsBuilder.build())
    }

    @OptIn(ExperimentalCamera2Interop::class)
    override suspend fun captureBurst(
        sessionId: UUID,
        params: CaptureParameters,
        onFrameCaptured: (Int, Int) -> Unit
    ): List<Frame> = withContext(Dispatchers.IO) {
        val captureUseCase = imageCapture ?: throw IllegalStateException("Camera not bound")
        val cam = camera ?: throw IllegalStateException("Camera instance unavailable")
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)

        val capturedFrames = mutableListOf<Frame>()
        val count = params.targetFrameCount.coerceIn(2, 32)
        val baseFocus = params.manualFocusDistance ?: (_capabilities.value.minFocusDistanceDiopters * 0.85f)

        // Lock AE and AWB across the burst so multi-frame registration and SNR fusion are radiometrically consistent
        val lockOptions = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, params.lockExposure)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            .build()
        camera2Control.setCaptureRequestOptions(lockOptions)

        try {
            for (i in 0 until count) {
                // Micro-step focus distance across +/- 0.8 Diopters if focus lock is disabled (Focus-Stacking Sweep)
                val stepFraction = if (count > 1) (i.toFloat() / (count - 1)) - 0.5f else 0f
                val frameFocusDiopters = if (!params.lockFocus) {
                    (baseFocus + stepFraction * 1.6f).coerceIn(0.5f, _capabilities.value.minFocusDistanceDiopters)
                } else {
                    baseFocus
                }

                val stepOptions = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, frameFocusDiopters)
                    .build()
                camera2Control.setCaptureRequestOptions(stepOptions)

                val outputFile = storageManager.createBurstFrameFile(sessionId, i)
                takeSingleFilePicture(captureUseCase, outputFile)

                val exif = ExifInterface(outputFile.absolutePath)
                val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, params.isoOverride ?: 200)
                val expSec = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.01)

                capturedFrames.add(
                    Frame(
                        uri = Uri.fromFile(outputFile),
                        filePath = outputFile.absolutePath,
                        timestampNs = System.nanoTime(),
                        exposureTimeNs = (expSec * 1_000_000_000L).toLong(),
                        iso = iso,
                        focusDistanceDiopters = frameFocusDiopters,
                        isRaw = false
                    )
                )
                onFrameCaptured(i + 1, count)
            }
        } finally {
            // Release AE/AWB locks after burst completion
            val unlockOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                .build()
            camera2Control.setCaptureRequestOptions(unlockOptions)
        }
        capturedFrames
    }

    private suspend fun takeSingleFilePicture(
        captureUseCase: ImageCapture,
        outputFile: File
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        captureUseCase.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            }
        )
    }
}
