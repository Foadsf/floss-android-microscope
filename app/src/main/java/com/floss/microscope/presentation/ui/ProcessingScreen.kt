package com.floss.microscope.presentation.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.floss.microscope.domain.model.ProcessingStage
import com.floss.microscope.presentation.viewmodel.ProcessingViewModel

@Composable
fun ProcessingScreen(
    viewModel: ProcessingViewModel,
    onProcessingComplete: () -> Unit,
    onCancel: () -> Unit
) {
    val stage by viewModel.stage.collectAsState()

    LaunchedEffect(stage) {
        if (stage is ProcessingStage.Completed) {
            onProcessingComplete()
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = stage?.progressFraction ?: 0.05f,
        label = "pipelineProgress"
    )

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Computational Microscopy Pipeline",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stage?.message ?: "Initializing multi-frame stack...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
            )
            Spacer(Modifier.height(24.dp))

            // Pipeline Stage Stepper Cards
            PipelineStageRow(
                title = "1. 6-DoF Software Tremor Stabilization",
                active = stage is ProcessingStage.Aligning,
                completed = (stage?.progressFraction ?: 0f) > 0.30f
            )
            PipelineStageRow(
                title = "2. Epipolar SfM & Monocular Photogrammetry",
                active = stage is ProcessingStage.ReconstructingSfM,
                completed = (stage?.progressFraction ?: 0f) > 0.55f
            )
            PipelineStageRow(
                title = "3. Multi-Frame Super-Resolution & Focus Fusion",
                active = stage is ProcessingStage.Fusing,
                completed = (stage?.progressFraction ?: 0f) > 0.75f
            )
            PipelineStageRow(
                title = "4. Richardson-Lucy Deconvolution & Retinex",
                active = stage is ProcessingStage.Enhancing,
                completed = stage is ProcessingStage.Completed
            )

            Spacer(Modifier.height(32.dp))

            if (stage is ProcessingStage.Failed) {
                Text(
                    text = (stage as ProcessingStage.Failed).reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
            }

            OutlinedButton(onClick = onCancel) {
                Icon(Icons.Default.Cancel, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Cancel Processing")
            }
        }
    }
}

@Composable
private fun PipelineStageRow(
    title: String,
    active: Boolean,
    completed: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                completed -> Icons.Default.CheckCircle
                active -> Icons.Default.Sync
                else -> Icons.Default.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = when {
                completed -> MaterialTheme.colorScheme.secondary
                active -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            }
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active || completed) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}
