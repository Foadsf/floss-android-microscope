package com.floss.microscope.infrastructure.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import com.floss.microscope.domain.model.DeviceCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CapabilityDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun detectBackCameraCapabilities(): DeviceCapabilities {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backCameraId = manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()

        if (backCameraId == null) {
            return DeviceCapabilities(
                hasOis = false,
                supportsRaw = false,
                supportsManualSensor = false,
                minFocusDistanceDiopters = 10f,
                maxIso = 3200,
                minIso = 100,
                maxZoomRatio = 4f
            )
        }

        val chars = manager.getCameraCharacteristics(backCameraId)
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10.0f
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4.0f

        return DeviceCapabilities(
            hasOis = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON),
            supportsRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            supportsManualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            minFocusDistanceDiopters = minFocus.coerceAtLeast(1.0f),
            maxIso = isoRange?.upper ?: 3200,
            minIso = isoRange?.lower ?: 100,
            maxZoomRatio = maxZoom
        )
    }
}
