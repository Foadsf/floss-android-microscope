package com.floss.microscope.presentation.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.floss.microscope.domain.model.*
import com.floss.microscope.presentation.viewmodel.ProcessingViewModel
import java.io.File
import java.util.UUID
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private enum class InspectorTab(val title: String) {
    SPLIT_2D("2D Split"),
    ORTHO_DEPTH("Ortho / Depth"),
    MESH_3D("3D Mesh"),
    CALIPERS("μm Calipers")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultViewerScreen(
    sessionId: UUID,
    viewModel: ProcessingViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToProcessing: () -> Unit
) {
    val context = LocalContext.current
    val session by viewModel.activeSession.collectAsState()

    LaunchedEffect(sessionId) {
        if (session?.id != sessionId) {
            viewModel.loadCompletedSession(sessionId)
        }
    }

    var selectedTab by remember { mutableStateOf(InspectorTab.SPLIT_2D) }
    var splitSliderFraction by remember { mutableStateOf(0.5f) }
    var showDepthMapInOrthoTab by remember { mutableStateOf(false) }
    var renderSolidMesh by remember { mutableStateOf(true) }

    var micrometersPerPixel by remember { mutableStateOf(2.4f) }
    val annotations = remember { mutableStateListOf<MicrometerAnnotation>() }
    var showCalibrationDialog by remember { mutableStateOf(false) }
    var showReprocessDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val snr = session?.enhancedImage?.snrGainDb ?: 0f
                    Text("Result (+%.1f dB SNR)".format(snr))
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCalibrationDialog = true }) {
                        Icon(Icons.Default.Straighten, contentDescription = "Calibrate Scale (μm/px)")
                    }
                    IconButton(onClick = { showReprocessDialog = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Reprocess Session")
                    }
                    IconButton(
                        onClick = {
                            viewModel.shareExportBundle(
                                context = context,
                                includeRaw = false,
                                pointCloudFormat = PointCloudFormat.PLY
                            )
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export & Share Bundle")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                InspectorTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.title, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            val activeSession = session
            if (activeSession == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val sessionDir = activeSession.enhancedImage?.filePath?.let { File(it).parentFile }
                val orthoPath = sessionDir?.let { File(it, "orthophoto_topdown.png").absolutePath }
                val depthPath = sessionDir?.let { File(it, "depth_map.png").absolutePath }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (selectedTab) {
                        InspectorTab.SPLIT_2D -> {
                            BeforeAfterSplitComparator(
                                rawFramePath = activeSession.frames.firstOrNull()?.filePath,
                                enhancedImagePath = activeSession.enhancedImage?.filePath,
                                splitFraction = splitSliderFraction,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        InspectorTab.ORTHO_DEPTH -> {
                            val displayPath = if (showDepthMapInOrthoTab) depthPath else orthoPath
                            BeforeAfterSplitComparator(
                                rawFramePath = null,
                                enhancedImagePath = displayPath ?: activeSession.enhancedImage?.filePath,
                                splitFraction = 0f,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        InspectorTab.MESH_3D -> {
                            activeSession.pointCloud?.let { pc ->
                                Interactive3DSurfaceMeshViewer(
                                    pointCloud = pc,
                                    renderSolidTriangles = renderSolidMesh,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        InspectorTab.CALIPERS -> {
                            BeforeAfterSplitComparator(
                                rawFramePath = null,
                                enhancedImagePath = activeSession.enhancedImage?.filePath,
                                splitFraction = 0f,
                                modifier = Modifier.fillMaxSize()
                            )
                            MicrometerCaliperOverlay(
                                micrometersPerPixel = micrometersPerPixel,
                                imageWidthPx = activeSession.enhancedImage?.effectiveResolution?.width ?: 1000,
                                imageHeightPx = activeSession.enhancedImage?.effectiveResolution?.height ?: 1000,
                                annotations = annotations,
                                onAddAnnotation = { annotations.add(it) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        when (selectedTab) {
                            InspectorTab.SPLIT_2D -> {
                                Text(
                                    text = "Before (Raw Frame 1) vs. After (Sub-Pixel SR + TV-RL Enhanced)",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Slider(
                                    value = splitSliderFraction,
                                    onValueChange = { splitSliderFraction = it },
                                    valueRange = 0.02f..0.98f
                                )
                            }
                            InspectorTab.ORTHO_DEPTH -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (showDepthMapInOrthoTab) "Dense SGBM + DfD 3D Depth Map"
                                        else "RANSAC Plane-Rectified Top-Down Orthophoto",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Switch(
                                        checked = showDepthMapInOrthoTab,
                                        onCheckedChange = { showDepthMapInOrthoTab = it }
                                    )
                                }
                            }
                            InspectorTab.MESH_3D -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Blinn-Phong Z-Sorted 3D Surface (${activeSession.pointCloud?.points?.size ?: 0} vertices)",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    FilterChip(
                                        selected = renderSolidMesh,
                                        onClick = { renderSolidMesh = !renderSolidMesh },
                                        label = { Text(if (renderSolidMesh) "Solid Mesh" else "Point Cloud") }
                                    )
                                }
                            }
                            InspectorTab.CALIPERS -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val lastMeasurement = annotations.lastOrNull()?.label ?: "Drag across specimen to measure"
                                    Text(
                                        text = "Scale: %.2f μm/px • Last: $lastMeasurement".format(micrometersPerPixel),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    TextButton(onClick = { annotations.clear() }) {
                                        Text("Clear (${annotations.size})")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCalibrationDialog) {
        var scaleInput by remember { mutableStateOf(micrometersPerPixel.toString()) }
        AlertDialog(
            onDismissRequest = { showCalibrationDialog = false },
            title = { Text("Calibrate Micrometer Scale") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter physical scale in micrometers per pixel (μm/px), or calibrate using a 1000 μm (1 mm) stage micrometer tick.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = scaleInput,
                        onValueChange = { scaleInput = it },
                        label = { Text("Micrometers per Pixel (μm/px)") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scaleInput.toFloatOrNull()?.let { micrometersPerPixel = it.coerceAtLeast(0.05f) }
                        showCalibrationDialog = false
                    }
                ) {
                    Text("Apply Calibration")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCalibrationDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showReprocessDialog && session != null) {
        val currentParams = session!!.processingParameters
        var selectedFusion by remember { mutableStateOf(currentParams.fusionMethod) }
        var enableDeconv by remember { mutableStateOf(currentParams.enableDeconvolution) }
        var enableRetinex by remember { mutableStateOf(currentParams.enableRetinex) }

        AlertDialog(
            onDismissRequest = { showReprocessDialog = false },
            title = { Text("Reprocess Specimen Stack") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fusion Algorithm:", style = MaterialTheme.typography.labelSmall)
                    FusionMethod.entries.forEach { method ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedFusion == method,
                                onClick = { selectedFusion = method }
                            )
                            Text(method.name)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = enableDeconv, onCheckedChange = { enableDeconv = it })
                        Text("TV-Regularized Richardson-Lucy Deconvolution")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = enableRetinex, onCheckedChange = { enableRetinex = it })
                        Text("3-Scale MSRCR Local Contrast")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showReprocessDialog = false
                        viewModel.reprocessSession(
                            sessionId = sessionId,
                            newParams = currentParams.copy(
                                fusionMethod = selectedFusion,
                                enableDeconvolution = enableDeconv,
                                enableRetinex = enableRetinex
                            ),
                            onStarted = onNavigateToProcessing
                        )
                    }
                ) {
                    Text("Reprocess")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReprocessDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun BeforeAfterSplitComparator(
    rawFramePath: String?,
    enhancedImagePath: String?,
    splitFraction: Float,
    modifier: Modifier = Modifier
) {
    val rawBitmap = remember(rawFramePath) {
        rawFramePath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }
    val enhancedBitmap = remember(enhancedImagePath) {
        enhancedImagePath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }

    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTransformGestures { _, panChange, zoomChange, _ ->
                scale = (scale * zoomChange).coerceIn(1f, 16f)
                pan += panChange
            }
        }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = pan.x,
                    translationY = pan.y
                )
        ) {
            val canvasW = size.width.toInt()
            val canvasH = size.height.toInt()
            val splitX = size.width * splitFraction

            enhancedBitmap?.let { bmp ->
                drawImage(
                    image = bmp,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(canvasW, canvasH)
                )
            }

            if (rawBitmap != null && splitFraction > 0.01f) {
                clipRect(left = 0f, top = 0f, right = splitX, bottom = size.height) {
                    drawImage(
                        image = rawBitmap,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(canvasW, canvasH)
                    )
                }
                drawLine(
                    color = Color(0xFF00E5FF),
                    start = Offset(splitX, 0f),
                    end = Offset(splitX, size.height),
                    strokeWidth = 4f
                )
            }
        }
    }
}

private data class RenderTriangle(
    val x0: Float, val y0: Float,
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val meanZ: Float,
    val color: Color
)

/**
 * Watertight 2-Triangle-per-Quad 3D Surface Mesh Renderer with Painter's Algorithm Back-to-Front
 * Depth Sorting and 3D Cross-Product Normal Blinn-Phong Illumination.
 */
private class PreallocatedMeshBuffers(numVertices: Int, val gridDim: Int) {
    val rxArr = FloatArray(numVertices)
    val ryArr = FloatArray(numVertices)
    val rzArr = FloatArray(numVertices)
    val projX = FloatArray(numVertices)
    val projY = FloatArray(numVertices)

    val numTris = if (gridDim * gridDim == numVertices && gridDim >= 4) (gridDim - 1) * (gridDim - 1) * 2 else 0
    val triV0 = IntArray(numTris)
    val triV1 = IntArray(numTris)
    val triV2 = IntArray(numTris)
    val triMeanZ = FloatArray(numTris)
    val triColorArgb = IntArray(numTris)
    val triOrder = IntArray(numTris) { it }
    val pointOrder = IntArray(numVertices) { it }
    val reusablePath = Path()

    init {
        if (numTris > 0) {
            var t = 0
            for (gy in 0 until gridDim - 1) {
                for (gx in 0 until gridDim - 1) {
                    val i00 = gy * gridDim + gx
                    val i10 = i00 + 1
                    val i01 = (gy + 1) * gridDim + gx
                    val i11 = i01 + 1

                    triV0[t] = i00; triV1[t] = i01; triV2[t] = i10; t++
                    triV0[t] = i10; triV1[t] = i01; triV2[t] = i11; t++
                }
            }
        }
    }

    fun sortTrianglesBackToFront() {
        // In-place CombSort on primitive IntArray triOrder by descending triMeanZ (zero heap allocations)
        var gap = numTris
        var swapped = true
        while (gap > 1 || swapped) {
            gap = (gap * 10) / 13
            if (gap < 1) gap = 1
            swapped = false
            for (i in 0 until numTris - gap) {
                val idxA = triOrder[i]
                val idxB = triOrder[i + gap]
                if (triMeanZ[idxA] < triMeanZ[idxB]) {
                    triOrder[i] = idxB
                    triOrder[i + gap] = idxA
                    swapped = true
                }
            }
        }
    }

    fun sortPointsBackToFront(count: Int) {
        var gap = count
        var swapped = true
        while (gap > 1 || swapped) {
            gap = (gap * 10) / 13
            if (gap < 1) gap = 1
            swapped = false
            for (i in 0 until count - gap) {
                val idxA = pointOrder[i]
                val idxB = pointOrder[i + gap]
                if (rzArr[idxA] < rzArr[idxB]) {
                    pointOrder[i] = idxB
                    pointOrder[i + gap] = idxA
                    swapped = true
                }
            }
        }
    }
}

@Composable
private fun Interactive3DSurfaceMeshViewer(
    pointCloud: PointCloud,
    renderSolidTriangles: Boolean,
    modifier: Modifier = Modifier
) {
    val pts = pointCloud.points
    val buffers = remember(pts) {
        val gDim = sqrt(pts.size.toDouble()).toInt()
        PreallocatedMeshBuffers(pts.size, gDim)
    }

    var yaw by remember { mutableStateOf(0.45f) }
    var pitch by remember { mutableStateOf(-0.35f) }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                yaw += dragAmount.x * 0.008f
                pitch = (pitch + dragAmount.y * 0.008f).coerceIn(-1.45f, 1.45f)
            }
        }
    ) {
        if (pts.isEmpty()) return@Canvas

        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val radiusScale = size.minDimension * 0.45f

        val cosY = cos(yaw); val sinY = sin(yaw)
        val cosP = cos(pitch); val sinP = sin(pitch)

        val rxArr = buffers.rxArr
        val ryArr = buffers.ryArr
        val rzArr = buffers.rzArr
        val projX = buffers.projX
        val projY = buffers.projY

        for (i in pts.indices) {
            val pt = pts[i]
            val x1 = pt.x * cosY + pt.z * sinY
            val z1 = -pt.x * sinY + pt.z * cosY
            val y2 = pt.y * cosP - z1 * sinP
            val z2 = pt.y * sinP + z1 * cosP

            rxArr[i] = x1
            ryArr[i] = y2
            rzArr[i] = z2

            val depth = (z2 + 2.4f).coerceAtLeast(0.35f)
            projX[i] = cx + (x1 / depth) * radiusScale
            projY[i] = cy + (y2 / depth) * radiusScale
        }

        if (renderSolidTriangles && buffers.numTris > 0) {
            val lx = 0.35f; val ly = -0.45f; val lz = -0.82f
            val numTris = buffers.numTris

            for (t in 0 until numTris) {
                val a = buffers.triV0[t]
                val b = buffers.triV1[t]
                val c = buffers.triV2[t]

                val ux = rxArr[b] - rxArr[a]; val uy = ryArr[b] - ryArr[a]; val uz = rzArr[b] - rzArr[a]
                val vx = rxArr[c] - rxArr[a]; val vy = ryArr[c] - ryArr[a]; val vz = rzArr[c] - rzArr[a]
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val nLen = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                nx /= nLen; ny /= nLen; nz /= nLen

                val lambert = kotlin.math.abs(nx * lx + ny * ly + nz * lz)
                val illum = (0.35f + 0.78f * lambert).coerceIn(0.35f, 1.25f)

                val pA = pts[a]; val pB = pts[b]; val pC = pts[c]
                val rAvg = ((pA.r + pB.r + pC.r) / 3f * illum).toInt().coerceIn(0, 255)
                val gAvg = ((pA.g + pB.g + pC.g) / 3f * illum).toInt().coerceIn(0, 255)
                val bAvg = ((pA.b + pB.b + pC.b) / 3f * illum).toInt().coerceIn(0, 255)

                buffers.triMeanZ[t] = (rzArr[a] + rzArr[b] + rzArr[c]) * 0.3333333f
                buffers.triColorArgb[t] = (0xFF shl 24) or (rAvg shl 16) or (gAvg shl 8) or bAvg
            }

            buffers.sortTrianglesBackToFront()
            val path = buffers.reusablePath

            for (k in 0 until numTris) {
                val t = buffers.triOrder[k]
                val a = buffers.triV0[t]
                val b = buffers.triV1[t]
                val c = buffers.triV2[t]

                path.reset()
                path.moveTo(projX[a], projY[a])
                path.lineTo(projX[b], projY[b])
                path.lineTo(projX[c], projY[c])
                path.close()
                drawPath(path, Color(buffers.triColorArgb[t]))
            }
        } else {
            buffers.sortPointsBackToFront(pts.size)
            for (k in pts.indices) {
                val i = buffers.pointOrder[k]
                val pt = pts[i]
                drawCircle(
                    color = Color(pt.r, pt.g, pt.b),
                    radius = 4.2f,
                    center = Offset(projX[i], projY[i])
                )
            }
        }
    }
}

@Composable
private fun MicrometerCaliperOverlay(
    micrometersPerPixel: Float,
    imageWidthPx: Int,
    imageHeightPx: Int,
    annotations: List<MicrometerAnnotation>,
    onAddAnnotation: (MicrometerAnnotation) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }

    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.YELLOW
            textSize = 34f
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
            setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
        }
    }

    Canvas(
        modifier = modifier.pointerInput(micrometersPerPixel, imageWidthPx, imageHeightPx) {
            detectDragGestures(
                onDragStart = { offset ->
                    dragStart = offset
                    dragCurrent = offset
                },
                onDrag = { change, _ ->
                    change.consume()
                    dragCurrent = change.position
                },
                onDragEnd = {
                    val s = dragStart
                    val e = dragCurrent
                    if (s != null && e != null && size.width > 0 && size.height > 0) {
                        val dxNorm = (e.x - s.x) / size.width
                        val dyNorm = (e.y - s.y) / size.height
                        // Uses exact horizontal (imageWidthPx) AND vertical (imageHeightPx) dimensions
                        val distPx = hypot(dxNorm * imageWidthPx, dyNorm * imageHeightPx)
                        val distUm = distPx * micrometersPerPixel
                        val label = if (distUm >= 1000f) "%.2f mm".format(distUm / 1000f) else "%.1f μm".format(distUm)
                        onAddAnnotation(
                            MicrometerAnnotation(
                                startNormalized = (s.x / size.width) to (s.y / size.height),
                                endNormalized = (e.x / size.width) to (e.y / size.height),
                                distanceMicrometers = distUm,
                                label = label
                            )
                        )
                    }
                    dragStart = null
                    dragCurrent = null
                }
            )
        }
    ) {
        val w = size.width
        val h = size.height
        val caliperColor = Color(0xFFFFEA00)

        annotations.forEach { ann ->
            val p1 = Offset(ann.startNormalized.first * w, ann.startNormalized.second * h)
            val p2 = Offset(ann.endNormalized.first * w, ann.endNormalized.second * h)
            drawLine(caliperColor, p1, p2, strokeWidth = 3.5f)
            drawCircle(caliperColor, radius = 6f, center = p1)
            drawCircle(caliperColor, radius = 6f, center = p2)

            val midX = (p1.x + p2.x) * 0.5f
            val midY = (p1.y + p2.y) * 0.5f - 12f
            drawContext.canvas.nativeCanvas.drawText(ann.label, midX, midY, textPaint)
        }

        if (dragStart != null && dragCurrent != null) {
            val s = dragStart!!
            val e = dragCurrent!!
            drawLine(Color(0xFF00E5FF), s, e, strokeWidth = 3.5f)
            drawCircle(Color(0xFF00E5FF), radius = 7f, center = s)
            drawCircle(Color(0xFF00E5FF), radius = 7f, center = e)

            if (w > 0 && h > 0) {
                val dxNorm = (e.x - s.x) / w
                val dyNorm = (e.y - s.y) / h
                val liveUm = hypot(dxNorm * imageWidthPx, dyNorm * imageHeightPx) * micrometersPerPixel
                val liveLabel = if (liveUm >= 1000f) "%.2f mm".format(liveUm / 1000f) else "%.1f μm".format(liveUm)
                drawContext.canvas.nativeCanvas.drawText(
                    liveLabel,
                    (s.x + e.x) * 0.5f,
                    (s.y + e.y) * 0.5f - 12f,
                    textPaint
                )
            }
        }
    }
}
