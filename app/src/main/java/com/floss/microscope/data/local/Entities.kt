package com.floss.microscope.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.floss.microscope.domain.model.*

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMilli: Long,
    val notes: String
)

@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val timestampEpochMilli: Long,
    val status: String,
    val mode: String,
    val alignmentMethod: String,
    val fusionMethod: String,
    val enableSfM: Boolean,
    val enableSuperRes: Boolean,
    val enableDenoise: Boolean,
    val enhancedImagePath: String?,
    val snrGainDb: Float?,
    val resWidth: Int?,
    val resHeight: Int?,
    val processingLogJoined: String?,
    val plyFilePath: String?,
    val objFilePath: String?,
    val cameraBaselineMm: Float?
)

@Entity(
    tableName = "frames",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class FrameEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val filePath: String,
    val timestampNs: Long,
    val exposureTimeNs: Long,
    val iso: Int,
    val focusDistanceDiopters: Float,
    val isRaw: Boolean
)
