package com.floss.microscope.data.repository

import android.net.Uri
import android.util.Size
import com.floss.microscope.data.local.*
import com.floss.microscope.data.storage.FileStorageManager
import com.floss.microscope.domain.model.*
import com.floss.microscope.domain.repository.ProcessingRepository
import com.floss.microscope.domain.repository.ProjectRepository
import com.floss.microscope.infrastructure.export.ArtifactExporter
import com.floss.microscope.infrastructure.processing.PipelineOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepositoryImpl @Inject constructor(
    private val dao: MicroscopeDao,
    private val storageManager: FileStorageManager
) : ProjectRepository {

    override fun observeProjects(): Flow<List<Project>> {
        return dao.observeAllProjects().map { projectEntities ->
            projectEntities.map { entity -> mapProjectEntity(entity) }
        }
    }

    override suspend fun getProject(projectId: UUID): Project? = withContext(Dispatchers.IO) {
        val entity = dao.getProjectById(projectId.toString()) ?: return@withContext null
        mapProjectEntity(entity)
    }

    override suspend fun getSession(sessionId: UUID): CaptureSession? = withContext(Dispatchers.IO) {
        val entity = dao.getSessionById(sessionId.toString()) ?: return@withContext null
        mapSessionEntity(entity)
    }

    override suspend fun createProject(name: String, notes: String): Project = withContext(Dispatchers.IO) {
        val project = Project(name = name, notes = notes)
        dao.insertProject(
            ProjectEntity(
                id = project.id.toString(),
                name = project.name,
                createdAtEpochMilli = project.createdAt.toEpochMilli(),
                notes = project.notes
            )
        )
        project
    }

    override suspend fun saveSession(session: CaptureSession) = withContext(Dispatchers.IO) {
        dao.insertSession(
            SessionEntity(
                id = session.id.toString(),
                projectId = session.projectId.toString(),
                timestampEpochMilli = session.timestamp.toEpochMilli(),
                status = session.status.name,
                mode = session.processingParameters.mode.name,
                alignmentMethod = session.processingParameters.alignmentMethod.name,
                fusionMethod = session.processingParameters.fusionMethod.name,
                enableSfM = session.processingParameters.enableSfM,
                enableSuperRes = session.processingParameters.enableSuperRes,
                enableDenoise = session.processingParameters.enableDenoise,
                enhancedImagePath = session.enhancedImage?.filePath,
                snrGainDb = session.enhancedImage?.snrGainDb,
                resWidth = session.enhancedImage?.effectiveResolution?.width,
                resHeight = session.enhancedImage?.effectiveResolution?.height,
                processingLogJoined = session.enhancedImage?.processingLog?.joinToString("\n"),
                plyFilePath = session.pointCloud?.plyFilePath,
                objFilePath = session.pointCloud?.objFilePath,
                cameraBaselineMm = session.pointCloud?.cameraBaselineMm
            )
        )
        val frameEntities = session.frames.map { frame ->
            FrameEntity(
                id = frame.id.toString(),
                sessionId = session.id.toString(),
                filePath = frame.filePath,
                timestampNs = frame.timestampNs,
                exposureTimeNs = frame.exposureTimeNs,
                iso = frame.iso,
                focusDistanceDiopters = frame.focusDistanceDiopters,
                isRaw = frame.isRaw
            )
        }
        dao.insertFrames(frameEntities)
    }

    override suspend fun deleteProject(projectId: UUID) = withContext(Dispatchers.IO) {
        dao.deleteProject(projectId.toString())
    }

    private suspend fun mapProjectEntity(entity: ProjectEntity): Project {
        val sessionEntities = dao.getSessionsForProject(entity.id)
        val sessions = sessionEntities.map { mapSessionEntity(it) }
        return Project(
            id = UUID.fromString(entity.id),
            name = entity.name,
            createdAt = Instant.ofEpochMilli(entity.createdAtEpochMilli),
            notes = entity.notes,
            sessions = sessions
        )
    }

    private suspend fun mapSessionEntity(entity: SessionEntity): CaptureSession {
        val sessionId = UUID.fromString(entity.id)
        val frames = dao.getFramesForSession(entity.id).map { f ->
            Frame(
                id = UUID.fromString(f.id),
                uri = Uri.fromFile(File(f.filePath)),
                filePath = f.filePath,
                timestampNs = f.timestampNs,
                exposureTimeNs = f.exposureTimeNs,
                iso = f.iso,
                focusDistanceDiopters = f.focusDistanceDiopters,
                isRaw = f.isRaw
            )
        }

        val enhancedImage = entity.enhancedImagePath?.let { path ->
            EnhancedImage(
                sessionId = sessionId,
                uri = Uri.fromFile(File(path)),
                filePath = path,
                snrGainDb = entity.snrGainDb ?: 0f,
                effectiveResolution = Size(entity.resWidth ?: 0, entity.resHeight ?: 0),
                processingLog = entity.processingLogJoined?.split("\n") ?: emptyList()
            )
        }

        val pointCloud = entity.plyFilePath?.let { plyPath ->
            val points = storageManager.parsePlyPoints(File(plyPath))
            PointCloud(
                sessionId = sessionId,
                points = points,
                plyFilePath = plyPath,
                objFilePath = entity.objFilePath ?: "",
                cameraBaselineMm = entity.cameraBaselineMm ?: 1.0f
            )
        }

        return CaptureSession(
            id = sessionId,
            projectId = UUID.fromString(entity.projectId),
            timestamp = Instant.ofEpochMilli(entity.timestampEpochMilli),
            frames = frames,
            processingParameters = ProcessingParameters(
                mode = ProcessingMode.valueOf(entity.mode),
                alignmentMethod = AlignmentMethod.valueOf(entity.alignmentMethod),
                fusionMethod = FusionMethod.valueOf(entity.fusionMethod),
                enableSfM = entity.enableSfM,
                enableSuperRes = entity.enableSuperRes,
                enableDenoise = entity.enableDenoise
            ),
            status = SessionStatus.valueOf(entity.status),
            enhancedImage = enhancedImage,
            pointCloud = pointCloud
        )
    }
}

@Singleton
class ProcessingRepositoryImpl @Inject constructor(
    private val orchestrator: PipelineOrchestrator,
    private val exporter: ArtifactExporter
) : ProcessingRepository {

    override fun executePipeline(
        session: CaptureSession,
        params: ProcessingParameters
    ): Flow<ProcessingStage> {
        return orchestrator.process(session, params)
    }

    override suspend fun exportSessionArtifacts(
        session: CaptureSession,
        includeRawFrames: Boolean,
        pointCloudFormat: PointCloudFormat
    ): File {
        return exporter.exportSessionBundle(session, includeRawFrames, pointCloudFormat)
    }
}
