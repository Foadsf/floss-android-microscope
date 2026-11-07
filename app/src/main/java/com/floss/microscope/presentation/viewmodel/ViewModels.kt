package com.floss.microscope.presentation.viewmodel

import android.content.Context
import android.content.Intent
import androidx.camera.view.PreviewView
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.floss.microscope.domain.model.*
import com.floss.microscope.domain.repository.CameraRepository
import com.floss.microscope.domain.repository.ProjectRepository
import com.floss.microscope.domain.usecase.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val importSequenceUseCase: ImportSequenceUseCase
) : ViewModel() {

    val projects: StateFlow<List<Project>> = projectRepository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createNewProject(name: String, notes: String) {
        viewModelScope.launch {
            projectRepository.createProject(name, notes)
        }
    }

    fun deleteProject(projectId: UUID) {
        viewModelScope.launch {
            projectRepository.deleteProject(projectId)
        }
    }

    fun importExternalImageSequence(
        uris: List<android.net.Uri>,
        onSessionReady: (UUID) -> Unit
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val session = importSequenceUseCase(projectId = null, imageUris = uris)
            onSessionReady(session.id)
        }
    }
}

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val cameraRepository: CameraRepository,
    private val startCaptureSessionUseCase: StartCaptureSessionUseCase
) : ViewModel() {

    val telemetry: StateFlow<TremorTelemetry> = cameraRepository.tremorTelemetry
    val capabilities: StateFlow<DeviceCapabilities> = cameraRepository.capabilities

    private val _captureParams = MutableStateFlow(CaptureParameters())
    val captureParams: StateFlow<CaptureParameters> = _captureParams.asStateFlow()

    private val _processingParams = MutableStateFlow(ProcessingParameters())
    val processingParams: StateFlow<ProcessingParameters> = _processingParams.asStateFlow()

    private val _zoomRatio = MutableStateFlow(2.0f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    private val _burstProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val burstProgress: StateFlow<Pair<Int, Int>?> = _burstProgress.asStateFlow()

    private val _showFocusPeaking = MutableStateFlow(true)
    val showFocusPeaking: StateFlow<Boolean> = _showFocusPeaking.asStateFlow()

    private val _showGrid = MutableStateFlow(true)
    val showGrid: StateFlow<Boolean> = _showGrid.asStateFlow()

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraRepository.bindCamera(lifecycleOwner, previewView)
        cameraRepository.applyCaptureParameters(_captureParams.value, _zoomRatio.value)
    }

    fun updateZoom(zoom: Float) {
        _zoomRatio.value = zoom
        cameraRepository.applyCaptureParameters(_captureParams.value, zoom)
    }

    fun updateCaptureParams(updater: (CaptureParameters) -> CaptureParameters) {
        val updated = updater(_captureParams.value)
        _captureParams.value = updated
        cameraRepository.applyCaptureParameters(updated, _zoomRatio.value)
    }

    fun updateProcessingParams(updater: (ProcessingParameters) -> ProcessingParameters) {
        _processingParams.value = updater(_processingParams.value)
    }

    fun togglePeaking() {
        _showFocusPeaking.value = !_showFocusPeaking.value
    }

    fun toggleGrid() {
        _showGrid.value = !_showGrid.value
    }

    fun triggerBurstCapture(projectId: UUID?, onSessionCreated: (UUID) -> Unit) {
        if (_burstProgress.value != null) return
        viewModelScope.launch {
            try {
                _burstProgress.value = 0 to _captureParams.value.targetFrameCount
                val session = startCaptureSessionUseCase(
                    projectId = projectId,
                    captureParams = _captureParams.value,
                    processingParams = _processingParams.value
                ) { current, total ->
                    _burstProgress.value = current to total
                }
                _burstProgress.value = null
                onSessionCreated(session.id)
            } catch (e: Exception) {
                _burstProgress.value = null
            }
        }
    }
}

@HiltViewModel
class ProcessingViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val executePipelineUseCase: ExecutePipelineUseCase,
    private val reprocessProjectUseCase: ReprocessProjectUseCase,
    private val exportResultUseCase: ExportResultUseCase
) : ViewModel() {

    private val _stage = MutableStateFlow<ProcessingStage?>(null)
    val stage: StateFlow<ProcessingStage?> = _stage.asStateFlow()

    private val _activeSession = MutableStateFlow<CaptureSession?>(null)
    val activeSession: StateFlow<CaptureSession?> = _activeSession.asStateFlow()

    private var pipelineJob: Job? = null

    fun startProcessingSession(sessionId: UUID) {
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            val session = projectRepository.getSession(sessionId) ?: return@launch
            _activeSession.value = session
            executePipelineUseCase(session, session.processingParameters).collect { st ->
                _stage.value = st
                if (st is ProcessingStage.Completed) {
                    _activeSession.value = st.session
                }
            }
        }
    }

    fun loadCompletedSession(sessionId: UUID) {
        viewModelScope.launch {
            _activeSession.value = projectRepository.getSession(sessionId)
        }
    }

    fun reprocessSession(sessionId: UUID, newParams: ProcessingParameters, onStarted: () -> Unit) {
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            onStarted()
            reprocessProjectUseCase(sessionId, newParams).collect { st ->
                _stage.value = st
                if (st is ProcessingStage.Completed) {
                    _activeSession.value = st.session
                }
            }
        }
    }

    fun cancelProcessing() {
        pipelineJob?.cancel()
        _stage.value = ProcessingStage.Failed("Cancelled by user")
    }

    fun shareExportBundle(
        context: Context,
        includeRaw: Boolean,
        pointCloudFormat: PointCloudFormat
    ) {
        val session = _activeSession.value ?: return
        viewModelScope.launch {
            val zipFile = exportResultUseCase(session, includeRaw, pointCloudFormat)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Export Microscopy Bundle"))
        }
    }
}
