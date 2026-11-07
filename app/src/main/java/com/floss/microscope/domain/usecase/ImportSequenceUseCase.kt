package com.floss.microscope.domain.usecase

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.floss.microscope.data.storage.FileStorageManager
import com.floss.microscope.domain.model.*
import com.floss.microscope.domain.repository.ProjectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

class ImportSequenceUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageManager: FileStorageManager,
    private val projectRepository: ProjectRepository
) {
    suspend operator fun invoke(
        projectId: UUID?,
        imageUris: List<Uri>,
        processingParameters: ProcessingParameters = ProcessingParameters()
    ): CaptureSession = withContext(Dispatchers.IO) {
        require(imageUris.isNotEmpty()) { "Must select at least 1 image to import" }

        val targetProject = if (projectId != null) {
            projectRepository.getProject(projectId)
                ?: projectRepository.createProject("Imported Specimen", "Offline sequence import")
        } else {
            projectRepository.createProject(
                name = "Imported Stack (${imageUris.size} frames)",
                notes = "Imported for offline multi-frame SfM & Fusion"
            )
        }

        val sessionId = UUID.randomUUID()
        val importedFrames = mutableListOf<Frame>()

        imageUris.forEachIndexed { idx, uri ->
            val dstFile = storageManager.createBurstFrameFile(sessionId, idx)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dstFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val exif = runCatching { ExifInterface(dstFile.absolutePath) }.getOrNull()
            val iso = exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 200) ?: 200
            val expSec = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.01) ?: 0.01

            importedFrames.add(
                Frame(
                    uri = Uri.fromFile(dstFile),
                    filePath = dstFile.absolutePath,
                    timestampNs = System.nanoTime() + idx * 16_666_666L,
                    exposureTimeNs = (expSec * 1_000_000_000L).toLong(),
                    iso = iso,
                    focusDistanceDiopters = 10.0f,
                    isRaw = false
                )
            )
        }

        val session = CaptureSession(
            id = sessionId,
            projectId = targetProject.id,
            frames = importedFrames,
            captureParameters = CaptureParameters(targetFrameCount = importedFrames.size),
            processingParameters = processingParameters.copy(numFrames = importedFrames.size),
            status = SessionStatus.CAPTURED
        )

        projectRepository.saveSession(session)
        session
    }
}
