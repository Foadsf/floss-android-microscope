package com.floss.microscope.infrastructure.processing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.floss.microscope.core.logging.FlossLogger
import com.floss.microscope.domain.model.ProcessingStage
import com.floss.microscope.domain.repository.ProjectRepository
import com.floss.microscope.domain.usecase.ExecutePipelineUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.onEach
import java.util.UUID

@HiltWorker
class MicroscopeProcessingWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val projectRepository: ProjectRepository,
    private val executePipelineUseCase: ExecutePipelineUseCase
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val attempt = runAttemptCount
        val sessionIdStr = inputData.getString(KEY_SESSION_ID)
        FlossLogger.i(TAG, "doWork start sessionId=$sessionIdStr attempt=$attempt")
        val sessionId = runCatching { UUID.fromString(sessionIdStr) }.getOrNull()
        if (sessionId == null) {
            FlossLogger.e(TAG, "invalid session id in work input: $sessionIdStr")
            return Result.failure()
        }

        val session = projectRepository.getSession(sessionId)
        if (session == null) {
            FlossLogger.e(TAG, "session $sessionId not found in database; nothing to process")
            return Result.failure()
        }
        ensureNotificationChannel()

        val finalStage = executePipelineUseCase(session, session.processingParameters)
            .onEach { stage ->
                FlossLogger.d(TAG, "stage ${stage::class.java.simpleName} ${(stage.progressFraction * 100).toInt()}% ${stage.message}")
                setProgress(
                    workDataOf(
                        KEY_PROGRESS to stage.progressFraction,
                        KEY_MESSAGE to stage.message
                    )
                )
                setForeground(createForegroundInfo(stage.message, (stage.progressFraction * 100).toInt()))
            }
            .lastOrNull()

        return when (finalStage) {
            is ProcessingStage.Completed -> {
                FlossLogger.i(TAG, "doWork success sessionId=$sessionId")
                Result.success()
            }
            is ProcessingStage.Failed -> {
                FlossLogger.e(TAG, "doWork failed sessionId=$sessionId reason=${finalStage.message}")
                Result.failure()
            }
            else -> {
                FlossLogger.e(TAG, "doWork ended without a terminal stage (last=${finalStage?.let { it::class.java.simpleName }})")
                Result.failure()
            }
        }
    }

    private fun ensureNotificationChannel() {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Microscopy Computational Pipeline",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun createForegroundInfo(statusText: String, progressPercent: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("FLOSS Digital Microscope")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setProgress(100, progressPercent.coerceIn(0, 100), false)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "Worker"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_MESSAGE = "message"
        private const val CHANNEL_ID = "floss_microscope_worker"
        private const val NOTIFICATION_ID = 4102

        fun enqueue(context: Context, sessionId: UUID) {
            val request = OneTimeWorkRequestBuilder<MicroscopeProcessingWorker>()
                .setInputData(workDataOf(KEY_SESSION_ID to sessionId.toString()))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "process_$sessionId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
