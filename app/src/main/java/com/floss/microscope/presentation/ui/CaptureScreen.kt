package com.floss.microscope.presentation.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.floss.microscope.domain.model.AlignmentMethod
import com.floss.microscope.domain.model.FusionMethod
import com.floss.microscope.presentation.viewmodel.CaptureViewModel
import java.util.UUID

@Composable
fun CaptureScreen(
    viewModel: CaptureViewModel,
    projectId: UUID?,
    onNavigateBack: () -> Unit,
    onSessionCaptured: (UUID) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val telemetry by viewModel.telemetry.collectAsState()
    val capabilities by viewModel.capabilities.collectAsState()
    val captureParams by viewModel.captureParams.collectAsState()
    val processingParams by viewModel.processingParams.collectAsState()
    val zoomRatio by viewModel.zoomRatio.collectAsState()
    val burstProgress by viewModel.burstProgress.collectAsState()
    val showPeaking by viewModel.showFocusPeaking.collectAsState()
    val showGrid by viewModel.showGrid.collectAsState()

    var showManualControls by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Live CameraX Preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    viewModel.bindCamera(lifecycleOwner, this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Scientific HUD Overlay (Focus Peaking, Grid, Scale Bar, Tremor Radar, Histogram)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Rule-of-thirds + central microscopy reticle
            if (showGrid) {
                val gridColor = Color.White.copy(alpha = 0.25f)
                drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 1.5f)
                drawLine(gridColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth = 1.5f)
                drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 1.5f)
                drawLine(gridColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth = 1.5f)

                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.45f),
                    radius = 48.dp.toPx(),
                    center = Offset(w * 0.5f, h * 0.5f),
                    style = Stroke(width = 2f)
                )
            }

            // Real-time Laplacian Focus Peaking Overlay
            if (showPeaking) {
                val peakColor = Color(0xFF00FF66).copy(alpha = 0.75f)
                telemetry.peakingPointsNormalized.forEach { (nx, ny) ->
                    drawCircle(
                        color = peakColor,
                        radius = 3.2f,
                        center = Offset(nx * w, ny * h)
                    )
                }
            }

            // Live Luminance Histogram (Top-Left Mini HUD)
            val hist = telemetry.lumaHistogram
            val maxBin = (hist.maxOrNull() ?: 1).coerceAtLeast(1)
            val histLeft = 24.dp.toPx()
            val histBottom = 130.dp.toPx()
            val histHeight = 44.dp.toPx()
            val barWidth = 2.5.dp.toPx()

            for (i in hist.indices) {
                val barH = (hist[i].toFloat() / maxBin) * histHeight
                drawLine(
                    color = Color(0xFF00E5FF).copy(alpha = 0.75f),
                    start = Offset(histLeft + i * barWidth, histBottom),
                    end = Offset(histLeft + i * barWidth, histBottom - barH),
                    strokeWidth = barWidth
                )
            }
        }

        // 3. Top Control Bar + Tremor Coaching Banner
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }

                // Stability Indicator Pill
                val stabilityColor = when {
                    telemetry.stabilityScore > 0.8f -> Color(0xFF00E676)
                    telemetry.stabilityScore > 0.5f -> Color(0xFFFFEA00)
                    else -> Color(0xFFFF5252)
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(stabilityColor, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${telemetry.holdSteadyCoachingMessage} (${(telemetry.stabilityScore * 100).toInt()}%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }

                Row {
                    IconButton(onClick = { viewModel.togglePeaking() }) {
                        Icon(
                            Icons.Default.CenterFocusStrong,
                            contentDescription = "Toggle Focus Peaking",
                            tint = if (showPeaking) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                    IconButton(onClick = { viewModel.toggleGrid() }) {
                        Icon(
                            Icons.Default.GridOn,
                            contentDescription = "Toggle Reticle Grid",
                            tint = if (showGrid) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                }
            }
        }

        // 4. Bottom Scientific Controls & Burst Shutter
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Burst Progress Banner if actively capturing
            burstProgress?.let { (cur, tot) ->
                LinearProgressIndicator(
                    progress = { cur.toFloat() / tot.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Capturing High-Speed Burst Frame $cur / $tot — Hold Steady...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Expandable Manual Optics & Pipeline Drawer
            if (showManualControls) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Burst Frames: ${captureParams.targetFrameCount} (Theoretical SNR +%.1f dB)".format(
                                10.0 * kotlin.math.log10(captureParams.targetFrameCount.toDouble())
                            ),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Slider(
                            value = captureParams.targetFrameCount.toFloat(),
                            onValueChange = { count ->
                                viewModel.updateCaptureParams { it.copy(targetFrameCount = count.toInt()) }
                            },
                            valueRange = 4f..32f,
                            steps = 6
                        )

                        Text(
                            text = "Macro Focus Distance (Diopters): ${
                                captureParams.manualFocusDistance?.let { "%.1f D".format(it) } ?: "Auto-Focus"
                            }",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Slider(
                            value = captureParams.manualFocusDistance ?: 0f,
                            onValueChange = { diopters ->
                                viewModel.updateCaptureParams {
                                    it.copy(manualFocusDistance = if (diopters < 0.2f) null else diopters)
                                }
                            },
                            valueRange = 0f..capabilities.minFocusDistanceDiopters
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = processingParams.enableSfM,
                                onClick = {
                                    viewModel.updateProcessingParams { it.copy(enableSfM = !it.enableSfM) }
                                },
                                label = { Text("3D SfM") }
                            )
                            FilterChip(
                                selected = processingParams.enableSuperRes,
                                onClick = {
                                    viewModel.updateProcessingParams { it.copy(enableSuperRes = !it.enableSuperRes) }
                                },
                                label = { Text("Super-Res 1.5x") }
                            )
                            FilterChip(
                                selected = processingParams.fusionMethod == FusionMethod.FOCUS_STACKING,
                                onClick = {
                                    val next = if (processingParams.fusionMethod == FusionMethod.FOCUS_STACKING)
                                        FusionMethod.TEMPORAL_MEDIAN else FusionMethod.FOCUS_STACKING
                                    viewModel.updateProcessingParams { it.copy(fusionMethod = next) }
                                },
                                label = { Text(processingParams.fusionMethod.name) }
                            )
                        }
                    }
                }
            }

            // Magnification Zoom Slider
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "%.1fx".format(zoomRatio),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.width(44.dp)
                )
                Slider(
                    value = zoomRatio,
                    onValueChange = { viewModel.updateZoom(it) },
                    valueRange = 1.0f..capabilities.maxZoomRatio,
                    modifier = Modifier.weight(1f)
                )
            }

            // Primary Shutter Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showManualControls = !showManualControls }
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (showManualControls) "Hide Optics" else "Optics & SfM")
                }

                Button(
                    onClick = {
                        viewModel.triggerBurstCapture(projectId, onSessionCaptured)
                    },
                    enabled = burstProgress == null,
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        Icons.Default.Camera,
                        contentDescription = "Capture Burst",
                        modifier = Modifier.size(36.dp)
                    )
                }

                AssistChip(
                    onClick = {
                        val nextMethod = when (processingParams.alignmentMethod) {
                            AlignmentMethod.HYBRID -> AlignmentMethod.ECC
                            AlignmentMethod.ECC -> AlignmentMethod.FEATURE_AKAZE
                            AlignmentMethod.FEATURE_AKAZE -> AlignmentMethod.OPTICAL_FLOW
                            else -> AlignmentMethod.HYBRID
                        }
                        viewModel.updateProcessingParams { it.copy(alignmentMethod = nextMethod) }
                    },
                    label = {
                        Text(
                            text = processingParams.alignmentMethod.name,
                            color = Color.White
                        )
                    }
                )
            }
        }
    }
}
