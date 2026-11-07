package com.floss.microscope.domain.repository

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.floss.microscope.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

interface ProjectRepository {
    fun observeProjects(): Flow<List<Project>>
    suspend fun getProject(projectId: UUID): Project?
    suspend fun getSession(sessionId: UUID): CaptureSession?
    suspend fun createProject(name: String, notes: String): Project
    suspend fun saveSession(session: CaptureSession)
    suspend fun deleteProject(projectId: UUID)
}

interface CameraRepository {
    val tremorTelemetry: StateFlow<TremorTelemetry>
    val capabilities: StateFlow<DeviceCapabilities>
    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView)
    fun applyCaptureParameters(params: CaptureParameters, zoomRatio: Float)
    suspend fun captureBurst(
        sessionId: UUID,
        params: CaptureParameters,
        onFrameCaptured: (Int, Int) -> Unit
    ): List<Frame>
}

interface ProcessingRepository {
    fun executePipeline(
        session: CaptureSession,
        params: ProcessingParameters
    ): Flow<ProcessingStage>

    suspend fun exportSessionArtifacts(
        session: CaptureSession,
        includeRawFrames: Boolean,
        pointCloudFormat: PointCloudFormat
    ): File
}
