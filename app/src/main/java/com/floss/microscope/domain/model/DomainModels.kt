package com.floss.microscope.domain.model

import android.net.Uri
import android.util.Size
import java.time.Instant
import java.util.UUID

enum class ProcessingMode {
    QUICK,
    QUALITY,
    SCIENTIFIC
}

enum class AlignmentMethod {
    OPTICAL_FLOW,
    FEATURE_ORB,
    FEATURE_AKAZE,
    ECC,
    PHASE_CORRELATION,
    HYBRID
}

enum class FusionMethod {
    TEMPORAL_AVERAGE,
    TEMPORAL_MEDIAN,
    EXPOSURE_HDR,
    FOCUS_STACKING
}

enum class SessionStatus {
    CAPTURING,
    CAPTURED,
    PROCESSING,
    COMPLETED,
    FAILED
}

enum class PointCloudFormat {
    PLY,
    OBJ
}

data class CaptureParameters(
    val targetFrameCount: Int = 16,
    val lockExposure: Boolean = true,
    val lockFocus: Boolean = true,
    val isoOverride: Int? = null,
    val exposureCompensationIndex: Int = 0,
    val manualFocusDistance: Float? = null, // Diopters (0.0 = infinity, max = macro)
    val referenceScaleMmPerPixel: Float? = null
)

data class ProcessingParameters(
    val mode: ProcessingMode = ProcessingMode.QUALITY,
    val numFrames: Int = 16,
    val enableSfM: Boolean = true,
    val enableSuperRes: Boolean = true,
    val enableDenoise: Boolean = true,
    val enableDeconvolution: Boolean = true,
    val enableRetinex: Boolean = true,
    val enableOpticalCorrection: Boolean = true,
    val alignmentMethod: AlignmentMethod = AlignmentMethod.HYBRID,
    val fusionMethod: FusionMethod = FusionMethod.FOCUS_STACKING,
    val richardsonLucyIterations: Int = 5,
    val claheClipLimit: Double = 2.2
)

data class Frame(
    val id: UUID = UUID.randomUUID(),
    val uri: Uri,
    val filePath: String,
    val timestampNs: Long,
    val exposureTimeNs: Long,
    val iso: Int,
    val focusDistanceDiopters: Float,
    val isRaw: Boolean = false
)

data class AlignedStack(
    val sessionId: UUID,
    val alignedFramePaths: List<String>,
    val meanRegistrationErrorPx: Float,
    val homographyMatricesFlat: List<DoubleArray>
)

data class EnhancedImage(
    val id: UUID = UUID.randomUUID(),
    val sessionId: UUID,
    val uri: Uri,
    val filePath: String,
    val snrGainDb: Float,
    val effectiveResolution: Size,
    val processingLog: List<String>
)

data class Point3D(
    val x: Float,
    val y: Float,
    val z: Float,
    val r: Int,
    val g: Int,
    val b: Int
)

data class PointCloud(
    val id: UUID = UUID.randomUUID(),
    val sessionId: UUID,
    val points: List<Point3D>,
    val plyFilePath: String,
    val objFilePath: String,
    val cameraBaselineMm: Float
)

data class CaptureSession(
    val id: UUID = UUID.randomUUID(),
    val projectId: UUID,
    val timestamp: Instant = Instant.now(),
    val frames: List<Frame> = emptyList(),
    val captureParameters: CaptureParameters = CaptureParameters(),
    val processingParameters: ProcessingParameters = ProcessingParameters(),
    val status: SessionStatus = SessionStatus.CAPTURING,
    val enhancedImage: EnhancedImage? = null,
    val pointCloud: PointCloud? = null
)

data class Project(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val createdAt: Instant = Instant.now(),
    val notes: String = "",
    val sessions: List<CaptureSession> = emptyList()
)

data class DeviceCapabilities(
    val hasOis: Boolean,
    val supportsRaw: Boolean,
    val supportsManualSensor: Boolean,
    val minFocusDistanceDiopters: Float,
    val maxIso: Int,
    val minIso: Int,
    val maxZoomRatio: Float
)

data class TremorTelemetry(
    val angularVelocityRadPerSec: Float = 0f,
    val stabilityScore: Float = 1f, // 0.0 (extreme shake) .. 1.0 (rock steady)
    val holdSteadyCoachingMessage: String = "Hold Steady",
    val focusSharpnessScore: Float = 0f,
    val lumaHistogram: IntArray = IntArray(64),
    val peakingPointsNormalized: List<Pair<Float, Float>> = emptyList()
)

sealed interface ProcessingStage {
    val progressFraction: Float
    val message: String

    data class Aligning(
        override val progressFraction: Float,
        override val message: String,
        val currentFrame: Int,
        val totalFrames: Int
    ) : ProcessingStage

    data class ReconstructingSfM(
        override val progressFraction: Float,
        override val message: String,
        val trackedKeypoints: Int
    ) : ProcessingStage

    data class Fusing(
        override val progressFraction: Float,
        override val message: String,
        val method: FusionMethod
    ) : ProcessingStage

    data class Enhancing(
        override val progressFraction: Float,
        override val message: String,
        val currentStep: String
    ) : ProcessingStage

    data class Completed(
        val session: CaptureSession,
        override val progressFraction: Float = 1.0f,
        override val message: String = "Completed"
    ) : ProcessingStage

    data class Failed(
        val reason: String,
        override val progressFraction: Float = 0f,
        override val message: String = reason
    ) : ProcessingStage
}

enum class BracketingMode {
    NONE_LOCKED_BURST,     // Best for MFSR & SNR (locks focus + exposure)
    FOCUS_STACK_SWEEP,     // Steps lens focus distance across specimen Z-depth
    HDR_EXPOSURE_BRACKET   // Steps sensor exposure time [-1 EV, 0 EV, +1 EV]
}

data class TriangleFace(
    val v1: Int,
    val v2: Int,
    val v3: Int
)

data class MicrometerAnnotation(
    val id: String = java.util.UUID.randomUUID().toString(),
    val startNormalized: Pair<Float, Float>,
    val endNormalized: Pair<Float, Float>,
    val distanceMicrometers: Float,
    val label: String
)

data class PhotogrammetryOutput(
    val pointCloud: PointCloud,
    val faces: List<TriangleFace>,
    val depthMapFilePath: String,
    val orthophotoFilePath: String,
    val estimatedMicrometersPerPixel: Float
)
