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
    val preCorrectionArray: android.graphics.Rect?,
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
    /** No está en cameraIdList: se encontró probando su número. */
    val probed: Boolean = false,
) {
    /** Resolución real del sensor (el JPEG puede venir agrupado: 108 MP -> 12 MP). */
    val sensorPixels: Long get() = pixelArray?.let { it.width.toLong() * it.height }
        ?: jpegSizes.firstOrNull()?.let { it.width.toLong() * it.height } ?: 0L

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
        // Desde API 28 los intrínsecos se refieren a este array (sin corrección de distorsión).
        preCorrectionArray?.let { put("pre_correction_active_array", JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
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
        val listed = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        fun add(id: String, probed: Boolean) {
            if (id in out) return
            val info = runCatching { describe(manager, id) }.getOrNull() ?: return
            out[id] = info.copy(probed = probed)
            for (pid in info.physicalIds) add(pid, probed = false)
        }
        for (id in listed) add(id, probed = false)
        // Muchos fabricantes (Honor/Huawei, Xiaomi…) sólo listan "0" y "1" pero dejan abrir
        // el resto de objetivos (ultra gran angular, macro) si se piden por su número.
        for (n in 0..PROBE_MAX_ID) add(n.toString(), probed = true)
        return out
    }

    /** Hasta qué número de cámara se prueba si existe aunque no esté en la lista. */
    const val PROBE_MAX_ID = 15

    /** Texto con todo lo que el móvil dice de sus cámaras, para depurar a distancia. */
    fun diagnostics(manager: CameraManager, cams: Map<String, CameraInfo>): String = buildString {
        appendLine("Tisma Scanner · diagnóstico de cámaras")
        appendLine("Dispositivo: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (SDK ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Lista oficial: " + runCatching { manager.cameraIdList.joinToString() }.getOrDefault("?"))
        appendLine("Concurrentes: " + concurrentSets(manager).joinToString { it.joinToString("+", "{", "}") })
        for (c in cams.values) {
            appendLine("#${c.id} ${c.facingName}${if (c.probed) " (oculta, encontrada por número)" else ""}" +
                (if (c.isLogicalMultiCamera) " lógica[${c.physicalIds.joinToString()}]" else "") +
                " sensor ${c.pixelArray?.let { "${it.width}x${it.height}" } ?: "?"}" +
                " jpeg ${c.jpegSizes.firstOrNull()?.let { "${it.width}x${it.height}" } ?: "-"}" +
                " f=${c.focalLengthsMm.joinToString()}mm hfov=${c.hfovDeg?.toInt() ?: "?"}°" +
                " minFoco=${c.minFocusDiopters ?: "?"}D nivel=${c.hardwareLevel}")
        }
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
            preCorrectionArray = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE),
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
