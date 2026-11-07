package com.floss.microscope.infrastructure.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.floss.microscope.domain.model.TremorTelemetry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TremorAndPeakingAnalyzer @Inject constructor(
    @ApplicationContext context: Context
) : ImageAnalysis.Analyzer, SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _telemetry = MutableStateFlow(TremorTelemetry())
    val telemetry: StateFlow<TremorTelemetry> = _telemetry.asStateFlow()

    @Volatile
    private var currentAngularVel = 0f

    fun startListening() {
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            val wx = event.values[0]
            val wy = event.values[1]
            val wz = event.values[2]
            val magnitude = sqrt(wx * wx + wy * wy + wz * wz)
            currentAngularVel = 0.7f * currentAngularVel + 0.3f * magnitude
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun analyze(image: ImageProxy) {
        try {
            val yPlane = image.planes[0]
            val buffer = yPlane.buffer
            val width = image.width
            val height = image.height
            val rowStride = yPlane.rowStride

            val histogram = IntArray(64)
            val peakingPoints = mutableListOf<Pair<Float, Float>>()
            var sharpnessSum = 0f
            var sampleCount = 0

            val step = 8
            for (y in step until (height - step) step step) {
                for (x in step until (width - step) step step) {
                    val cIdx = y * rowStride + x
                    val c = buffer.get(cIdx).toInt() and 0xFF
                    histogram[c shr 2]++

                    val l = buffer.get(cIdx - 2).toInt() and 0xFF
                    val r = buffer.get(cIdx + 2).toInt() and 0xFF
                    val u = buffer.get((y - 2) * rowStride + x).toInt() and 0xFF
                    val d = buffer.get((y + 2) * rowStride + x).toInt() and 0xFF

                    val laplacian = abs(-4 * c + l + r + u + d)
                    sharpnessSum += laplacian
                    sampleCount++

                    if (laplacian > 85 && peakingPoints.size < 450) {
                        peakingPoints.add((x.toFloat() / width) to (y.toFloat() / height))
                    }
                }
            }

            val avgSharpness = if (sampleCount > 0) sharpnessSum / sampleCount else 0f
            val stability = exp(-currentAngularVel * 3.5f).coerceIn(0f, 1f)
            val coaching = when {
                stability > 0.82f -> "Optimal Stability — Ready for Capture"
                stability > 0.50f -> "Minor Hand Tremor — Multi-Frame Active"
                else -> "Brace Elbows / Hold Steady"
            }

            _telemetry.value = TremorTelemetry(
                angularVelocityRadPerSec = currentAngularVel,
                stabilityScore = stability,
                holdSteadyCoachingMessage = coaching,
                focusSharpnessScore = avgSharpness,
                lumaHistogram = histogram,
                peakingPointsNormalized = peakingPoints
            )
        } finally {
            image.close()
        }
    }
}
