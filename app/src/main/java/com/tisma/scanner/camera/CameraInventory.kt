package com.tisma.scanner.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/** Descripción de una cámara (lógica o física) con todo lo útil para fotogrametría. */
data class CameraInfo(
    val id: String,
    val facing: Int?,
    val isLogicalMultiCamera: Boolean,
    val physicalIds: List<String>,
    val focalLengthsMm: FloatArray,
    val sensorSizeMm: Pair<Float, Float>?,
    val pixelArray: Size?,
    val activeArray: android.graphics.Rect?,
    /** [fx, fy, cx, cy, s] en píxeles del active array (puede ser null si el fabricante no lo publica). */
    val intrinsics: FloatArray?,
    /** [k1, k2, k3, p1, p2] modelo Brown-Conrady de Android. */
    val distortion: FloatArray?,
    /** Cuaternión [x, y, z, w] y traslación [m] respecto a lensPoseReference. */
    val poseRotation: FloatArray?,
    val poseTranslation: FloatArray?,
    val poseReference: Int?,
    val timestampSource: Int?,
    val jpegSizes: List<Size>,
    val hardwareLevel: Int?,
    val minFocusDiopters: Float?,
    val hyperfocalDiopters: Float?,
    val focusCalibration: Int?,
    val apertures: FloatArray,
) {
    val facingName get() = when (facing) {
        CameraMetadata.LENS_FACING_BACK -> "back"
        CameraMetadata.LENS_FACING_FRONT -> "front"
        CameraMetadata.LENS_FACING_EXTERNAL -> "external"
        else -> "unknown"
    }

    /** Campo de visión horizontal aproximado (grados), útil para mostrar "ultra gran angular", "tele", etc. */
    val hfovDeg: Double? get() {
        val f = focalLengthsMm.firstOrNull() ?: return null
        val w = sensorSizeMm?.first ?: return null
        return Math.toDegrees(2 * kotlin.math.atan(w / (2.0 * f)))
    }

    val label: String get() = buildString {
        append("#").append(id).append(" ").append(facingName)
        if (isLogicalMultiCamera) append(" lógica[").append(physicalIds.joinToString(",")).append("]")
        hfovDeg?.let { append(" ").append(it.toInt()).append("°") }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("facing", facingName)
        put("logical_multi_camera", isLogicalMultiCamera)
        put("physical_ids", JSONArray(physicalIds))
        put("focal_lengths_mm", JSONArray(focalLengthsMm.map { it.toDouble() }))
        sensorSizeMm?.let { put("sensor_size_mm", JSONArray(listOf(it.first.toDouble(), it.second.toDouble()))) }
        pixelArray?.let { put("pixel_array", JSONArray(listOf(it.width, it.height))) }
        activeArray?.let { put("active_array", JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
        intrinsics?.let { put("intrinsics_fx_fy_cx_cy_s", JSONArray(it.map { v -> v.toDouble() })) }
        distortion?.let { put("distortion_k1_k2_k3_p1_p2", JSONArray(it.map { v -> v.toDouble() })) }
        poseRotation?.let { put("pose_rotation_xyzw", JSONArray(it.map { v -> v.toDouble() })) }
        poseTranslation?.let { put("pose_translation_m", JSONArray(it.map { v -> v.toDouble() })) }
        put("pose_reference", when (poseReference) {
            CameraMetadata.LENS_POSE_REFERENCE_PRIMARY_CAMERA -> "primary_camera"
            CameraMetadata.LENS_POSE_REFERENCE_GYROSCOPE -> "gyroscope"
            CameraMetadata.LENS_POSE_REFERENCE_UNDEFINED -> "undefined"
            CameraMetadata.LENS_POSE_REFERENCE_AUTOMOTIVE -> "automotive"
            else -> "unknown"
        })
        put("timestamp_source", if (timestampSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "realtime" else "unknown")
        put("hardware_level", hardwareLevel ?: -1)
        put("min_focus_diopters", (minFocusDiopters ?: 0f).toDouble())
        put("hyperfocal_diopters", (hyperfocalDiopters ?: 0f).toDouble())
        // La precisión de "depth from focus" depende de esto: calibrated > approximate > uncalibrated.
        put("focus_distance_calibration", when (focusCalibration) {
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "calibrated"
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "approximate"
            else -> "uncalibrated"
        })
        put("apertures", JSONArray(apertures.map { it.toDouble() }))
    }
}

object CameraInventory {

    /** Lista todas las cámaras: las públicas y las físicas ocultas tras cámaras lógicas. */
    fun enumerate(manager: CameraManager): Map<String, CameraInfo> {
        val out = LinkedHashMap<String, CameraInfo>()
        for (id in manager.cameraIdList) {
            val info = describe(manager, id)
            out[id] = info
            for (pid in info.physicalIds) {
                if (pid !in out) runCatching { describe(manager, pid) }.getOrNull()?.let { out[pid] = it }
            }
        }
        return out
    }

    fun describe(manager: CameraManager, id: String): CameraInfo {
        val c = manager.getCameraCharacteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val logical = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpeg = map?.getOutputSizes(ImageFormat.JPEG)?.sortedByDescending { it.width.toLong() * it.height } ?: emptyList()
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return CameraInfo(
            id = id,
            facing = c.get(CameraCharacteristics.LENS_FACING),
            isLogicalMultiCamera = logical,
            physicalIds = if (logical) c.physicalCameraIds.toList() else emptyList(),
            focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0),
            sensorSizeMm = sensor?.let { it.width to it.height },
            pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
            activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            intrinsics = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION),
            distortion = c.get(CameraCharacteristics.LENS_DISTORTION),
            poseRotation = c.get(CameraCharacteristics.LENS_POSE_ROTATION),
            poseTranslation = c.get(CameraCharacteristics.LENS_POSE_TRANSLATION),
            poseReference = c.get(CameraCharacteristics.LENS_POSE_REFERENCE),
            timestampSource = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),
            jpegSizes = jpeg,
            hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            hyperfocalDiopters = c.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE),
            focusCalibration = c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION),
            apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: FloatArray(0),
        )
    }

    /** Conjuntos de cámaras que el dispositivo garantiza poder abrir a la vez (API 30+). */
    fun concurrentSets(manager: CameraManager): List<Set<String>> =
        runCatching { manager.concurrentCameraIds.toList() }.getOrDefault(emptyList())
}
