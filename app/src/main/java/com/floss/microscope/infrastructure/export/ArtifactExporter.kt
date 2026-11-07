package com.floss.microscope.infrastructure.export

import com.floss.microscope.data.storage.FileStorageManager
import com.floss.microscope.domain.model.CaptureSession
import com.floss.microscope.domain.model.PointCloudFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtifactExporter @Inject constructor(
    private val storageManager: FileStorageManager
) {
    suspend fun exportSessionBundle(
        session: CaptureSession,
        includeRawFrames: Boolean,
        pointCloudFormat: PointCloudFormat
    ): File = withContext(Dispatchers.IO) {
        // Write comprehensive metadata JSON
        val metaFile = storageManager.getMetadataJsonFile(session.id)
        val json = JSONObject().apply {
            put("sessionId", session.id.toString())
            put("timestamp", session.timestamp.toString())
            put("frameCount", session.frames.size)
            put("processingMode", session.processingParameters.mode.name)
            put("alignmentMethod", session.processingParameters.alignmentMethod.name)
            put("fusionMethod", session.processingParameters.fusionMethod.name)
            put("snrGainDb", session.enhancedImage?.snrGainDb ?: 0f)
            put("resolutionWidth", session.enhancedImage?.effectiveResolution?.width ?: 0)
            put("resolutionHeight", session.enhancedImage?.effectiveResolution?.height ?: 0)
            put("processingLog", JSONArray(session.enhancedImage?.processingLog ?: emptyList<String>()))
        }
        metaFile.writeText(json.toString(2))

        val zipFile = storageManager.getExportFile("microscope_session_${session.id.toString().take(8)}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            session.enhancedImage?.filePath?.let { path ->
                addFileToZip(zos, File(path), "enhanced_microscope.png")
            }
            addFileToZip(zos, metaFile, "metadata.json")

            session.pointCloud?.let { pc ->
                val pcFile = if (pointCloudFormat == PointCloudFormat.PLY) {
                    File(pc.plyFilePath)
                } else {
                    File(pc.objFilePath)
                }
                if (pcFile.exists()) {
                    addFileToZip(zos, pcFile, pcFile.name)
                }
            }

            if (includeRawFrames) {
                session.frames.forEachIndexed { idx, frame ->
                    val f = File(frame.filePath)
                    if (f.exists()) {
                        addFileToZip(zos, f, "burst/frame_%03d.jpg".format(idx))
                    }
                }
            }
        }
        zipFile
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
        if (!file.exists()) return
        FileInputStream(file).use { fis ->
            val entry = ZipEntry(entryName)
            zos.putNextEntry(entry)
            fis.copyTo(zos)
            zos.closeEntry()
        }
    }
}
