package com.floss.microscope.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.floss.microscope.core.logging.FlossLogger
import com.floss.microscope.domain.model.ProcessingMode
import com.floss.microscope.presentation.viewmodel.CaptureViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    captureViewModel: CaptureViewModel,
    darkTheme: Boolean,
    onToggleDarkTheme: (Boolean) -> Unit,
    highContrast: Boolean,
    onToggleHighContrast: (Boolean) -> Unit,
    onNavigateBack: () -> Unit
) {
    val processingParams by captureViewModel.processingParams.collectAsState()
    val capabilities by captureViewModel.capabilities.collectAsState()
    val context = LocalContext.current
    var verboseLogging by remember { mutableStateOf(FlossLogger.isVerbose) }
    var diagnosticsMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Hardware Telemetry") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Processing Mode Preset", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProcessingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = processingParams.mode == mode,
                        onClick = {
                            captureViewModel.updateProcessingParams {
                                when (mode) {
                                    ProcessingMode.QUICK -> it.copy(
                                        mode = mode,
                                        enableSfM = false,
                                        enableSuperRes = false,
                                        richardsonLucyIterations = 2
                                    )
                                    ProcessingMode.QUALITY -> it.copy(
                                        mode = mode,
                                        enableSfM = true,
                                        enableSuperRes = true,
                                        richardsonLucyIterations = 5
                                    )
                                    ProcessingMode.SCIENTIFIC -> it.copy(
                                        mode = mode,
                                        enableSfM = true,
                                        enableSuperRes = true,
                                        richardsonLucyIterations = 10
                                    )
                                }
                            }
                        },
                        label = { Text(mode.name) }
                    )
                }
            }

            HorizontalDivider()
            Text("Computational Imaging Modules", style = MaterialTheme.typography.titleMedium)

            SwitchRow(
                title = "Optical Vignetting & Chromatic Aberration Correction",
                checked = processingParams.enableOpticalCorrection,
                onCheckedChange = { enabled ->
                    captureViewModel.updateProcessingParams { it.copy(enableOpticalCorrection = enabled) }
                }
            )
            SwitchRow(
                title = "Richardson-Lucy Blind/Non-Blind Deconvolution",
                checked = processingParams.enableDeconvolution,
                onCheckedChange = { enabled ->
                    captureViewModel.updateProcessingParams { it.copy(enableDeconvolution = enabled) }
                }
            )
            SwitchRow(
                title = "Multi-Scale Retinex Local Contrast (MSRCR)",
                checked = processingParams.enableRetinex,
                onCheckedChange = { enabled ->
                    captureViewModel.updateProcessingParams { it.copy(enableRetinex = enabled) }
                }
            )
            SwitchRow(
                title = "Spatial Non-Local Means Chroma Denoising",
                checked = processingParams.enableDenoise,
                onCheckedChange = { enabled ->
                    captureViewModel.updateProcessingParams { it.copy(enableDenoise = enabled) }
                }
            )

            HorizontalDivider()
            Text("Accessibility & Appearance", style = MaterialTheme.typography.titleMedium)

            SwitchRow(
                title = "Scientific Dark Theme",
                checked = darkTheme,
                onCheckedChange = onToggleDarkTheme
            )
            SwitchRow(
                title = "High-Contrast Accessibility Mode",
                checked = highContrast,
                onCheckedChange = onToggleHighContrast
            )

            HorizontalDivider()
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                title = "Verbose debug logging (records to a log file)",
                checked = verboseLogging,
                onCheckedChange = { enabled ->
                    FlossLogger.setVerbose(context, enabled)
                    verboseLogging = enabled
                    diagnosticsMessage = null
                }
            )
            Text(
                text = "Log file: " + (FlossLogger.currentLogFile?.absolutePath ?: "not recording (enable verbose logging)"),
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = {
                diagnosticsMessage = shareDebugBundle(context)
            }) {
                Text("Export debug log")
            }
            diagnosticsMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            HorizontalDivider()
            Text("Detected Hardware Capabilities (Vendor-Agnostic)", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Optical Image Stabilization (OIS): ${capabilities.hasOis}")
                    Text("RAW Sensor Stream Support: ${capabilities.supportsRaw}")
                    Text("Manual Sensor Controls: ${capabilities.supportsManualSensor}")
                    Text("Minimum Macro Focus: ${capabilities.minFocusDistanceDiopters} Diopters")
                    Text("ISO Sensitivity Range: ${capabilities.minIso} – ${capabilities.maxIso}")
                    Text("Max Digital Zoom Ratio: ${capabilities.maxZoomRatio}x")
                }
            }
        }
    }
}

/**
 * Bundles every retained diagnostic log and opens the system share sheet.
 * Returns a user-facing message when nothing could be shared, otherwise null.
 */
private fun shareDebugBundle(context: Context): String? {
    val bundle = FlossLogger.buildExportBundle(context)
        ?: return "No log yet. Turn on verbose logging, reproduce the problem, then export."
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", bundle)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "FLOSS Microscope debug log")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share debug log"))
    FlossLogger.i("Diagnostics", "debug bundle exported: ${bundle.absolutePath} (${bundle.length()} bytes)")
    return null
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onDismiss: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("How FLOSS Microscope Works") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "Turn Hand Tremor into Super-Resolution & 3D Geometry",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "1. Brace & Focus: Rest your wrists on a surface near the specimen. Enable Focus Peaking (green highlights) to lock at minimum macro focus distance."
            )
            Text(
                text = "2. Natural Micro-Baseline: When you capture a 16-to-32 frame burst, natural sub-millimeter hand tremor provides multiple slightly shifted viewpoints."
            )
            Text(
                text = "3. 6-DoF Registration & Fusion: The pipeline aligns every frame via Hybrid AKAZE/ECC registration, boosting Signal-to-Noise Ratio (SNR) by +12 dB and synthesizing a 3D point cloud."
            )
            Text(
                text = "4. 100% On-Device & FLOSS: Zero cloud uploads, zero proprietary lock-in. Export standard PNGs, JSON logs, and PLY/OBJ 3D meshes."
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start Exploring")
            }
        }
    }
}
