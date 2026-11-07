package com.floss.microscope.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MicroscopeDao {
    @Query("SELECT * FROM projects ORDER BY createdAtEpochMilli DESC")
    fun observeAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :projectId")
    suspend fun getProjectById(projectId: String): ProjectEntity?

    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY timestampEpochMilli DESC")
    suspend fun getSessionsForProject(projectId: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM frames WHERE sessionId = :sessionId ORDER BY timestampNs ASC")
    suspend fun getFramesForSession(sessionId: String): List<FrameEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFrames(frames: List<FrameEntity>)

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: String)
}
