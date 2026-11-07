package com.floss.microscope.domain.usecase

import com.floss.microscope.domain.model.*
import com.floss.microscope.domain.repository.CameraRepository
import com.floss.microscope.domain.repository.ProcessingRepository
import com.floss.microscope.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

class StartCaptureSessionUseCase @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val cameraRepository: CameraRepository
) {
    suspend operator fun invoke(
        projectId: UUID?,
        captureParams: CaptureParameters,
        processingParams: ProcessingParameters,
        onBurstProgress: (Int, Int) -> Unit
    ): CaptureSession {
        val targetProject = if (projectId != null) {
            projectRepository.getProject(projectId)
                ?: projectRepository.createProject("Specimen Study", "Auto-created project")
        } else {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            projectRepository.createProject("Microscopy $timestamp", "Captured via FLOSS Microscope")
        }

        val sessionId = UUID.randomUUID()
        val frames = cameraRepository.captureBurst(sessionId, captureParams, onBurstProgress)

        val session = CaptureSession(
            id = sessionId,
            projectId = targetProject.id,
            frames = frames,
            captureParameters = captureParams,
            processingParameters = processingParams,
            status = SessionStatus.CAPTURED
        )
        projectRepository.saveSession(session)
        return session
    }
}

class ExecutePipelineUseCase @Inject constructor(
    private val processingRepository: ProcessingRepository,
    private val projectRepository: ProjectRepository
) {
    operator fun invoke(
        session: CaptureSession,
        params: ProcessingParameters
    ): Flow<ProcessingStage> {
        return processingRepository.executePipeline(session, params).onEach { stage ->
            if (stage is ProcessingStage.Completed) {
                projectRepository.saveSession(stage.session)
            }
        }
    }
}

class ReprocessProjectUseCase @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val executePipelineUseCase: ExecutePipelineUseCase
) {
    suspend operator fun invoke(
        sessionId: UUID,
        newParams: ProcessingParameters
    ): Flow<ProcessingStage> {
        val session = projectRepository.getSession(sessionId)
            ?: throw IllegalArgumentException("Session $sessionId not found")
        return executePipelineUseCase(session, newParams)
    }
}

class ExportResultUseCase @Inject constructor(
    private val processingRepository: ProcessingRepository
) {
    suspend operator fun invoke(
        session: CaptureSession,
        includeRawFrames: Boolean = false,
        format: PointCloudFormat = PointCloudFormat.PLY
    ): File {
        return processingRepository.exportSessionArtifacts(session, includeRawFrames, format)
    }
}
