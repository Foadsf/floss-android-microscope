package com.floss.microscope.data.storage

import android.content.Context
import com.floss.microscope.domain.model.Point3D
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileStorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val projectsRoot = File(context.filesDir, "projects").apply { mkdirs() }
    private val exportsRoot = File(context.cacheDir, "exports").apply { mkdirs() }

    fun getSessionDirectory(sessionId: UUID): File {
        return File(projectsRoot, sessionId.toString()).apply { mkdirs() }
    }

    fun createBurstFrameFile(sessionId: UUID, frameIndex: Int): File {
        val dir = File(getSessionDirectory(sessionId), "burst").apply { mkdirs() }
        return File(dir, "frame_%03d.jpg".format(frameIndex))
    }

    fun createAlignedFrameFile(sessionId: UUID, frameIndex: Int): File {
        val dir = File(getSessionDirectory(sessionId), "aligned").apply { mkdirs() }
        return File(dir, "aligned_%03d.png".format(frameIndex))
    }

    fun getEnhancedOutputFile(sessionId: UUID): File {
        return File(getSessionDirectory(sessionId), "enhanced_microscope.png")
    }

    fun getPlyOutputFile(sessionId: UUID): File {
        return File(getSessionDirectory(sessionId), "specimen_cloud.ply")
    }

    fun getObjOutputFile(sessionId: UUID): File {
        return File(getSessionDirectory(sessionId), "specimen_cloud.obj")
    }

    fun getMetadataJsonFile(sessionId: UUID): File {
        return File(getSessionDirectory(sessionId), "metadata.json")
    }

    fun getExportFile(name: String): File {
        return File(exportsRoot, name)
    }

    fun parsePlyPoints(plyFile: File): List<Point3D> {
        if (!plyFile.exists()) return emptyList()
        val points = mutableListOf<Point3D>()
        var headerEnded = false
        plyFile.forEachLine { line ->
            if (headerEnded) {
                val parts = line.trim().split(" ")
                if (parts.size >= 6) {
                    points.add(
                        Point3D(
                            x = parts[0].toFloatOrNull() ?: 0f,
                            y = parts[1].toFloatOrNull() ?: 0f,
                            z = parts[2].toFloatOrNull() ?: 0f,
                            r = parts[3].toIntOrNull() ?: 200,
                            g = parts[4].toIntOrNull() ?: 200,
                            b = parts[5].toIntOrNull() ?: 200
                        )
                    )
                }
            } else if (line.trim() == "end_header") {
                headerEnded = true
            }
        }
        return points
    }
}
