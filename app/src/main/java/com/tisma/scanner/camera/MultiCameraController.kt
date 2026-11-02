package com.tisma.scanner.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Abre varias cámaras a la vez (cámaras físicas de una lógica + cámaras concurrentes) y
 * dispara capturas JPEG sincronizadas en todas ellas.
 */
class MultiCameraController(context: Context, private val listener: Listener) {

    interface Listener {
        /** JPEG listo. `cameraId` es la cámara física (o la lógica si no hay físicas). */
        fun onImage(cameraId: String, timestampNs: Long, width: Int, height: Int, jpeg: ByteArray)
        /** Metadatos de captura por cámara (exposición, foco, intrínsecos dinámicos...). */
        fun onCaptureMetadata(cameraId: String, timestampNs: Long, meta: JSONObject)
        fun onLog(msg: String)
    }

    /** Lo que finalmente se ha conseguido abrir. */
    data class OpenedStream(val cameraId: String, val size: Size, val viaGroup: String)

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val camThread = HandlerThread("camera").apply { start() }
    private val ioThread = HandlerThread("camera-io").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private val ioHandler = Handler(ioThread.looper)
    private val executor = Executor { camHandler.post(it) }

    private inner class Active(
        val group: CameraGroup,
        val device: CameraDevice,
        val session: CameraCaptureSession,
        val streamSurface: Surface,           // previsualización o flujo "sumidero"
        val readers: Map<String, ImageReader>, // cameraId -> lector JPEG
        val sink: ImageReader?,
        val manualSensor: Boolean,
        val exposureRange: android.util.Range<Long>?,
        val isoRange: android.util.Range<Int>?,
    ) {
        /** Última exposición elegida por el AE automático (para el horquillado HDR). */
        @Volatile var lastExposureNs: Long = 0
        @Volatile var lastIso: Int = 0
        @Volatile var lastFocus: Float = 0f
        /** Fotogramas de previsualización recibidos desde que se abrió la cámara. */
        @Volatile var frames = 0
        /** AE/AF han tenido tiempo de converger: sólo entonces se puede bloquear. */
        val settled get() = frames >= SETTLE_FRAMES
        lateinit var characteristics: CameraCharacteristics
    }

    /**
     * Ajustes de calidad pensados para piezas planas / opacas con poca textura:
     *  - torch: luz continua uniforme (mejor que flash: igual en todas las fotos).
     *  - lock3A: congela foco, exposición y balance de blancos durante el escaneo, para que
     *    los intrínsecos (que cambian con el foco) y el color sean idénticos en todas las fotos.
     *  - Reducción de ruido y realce de bordes al mínimo: ese procesado "alisa" justo la
     *    microtextura que necesita el emparejamiento de puntos en superficies lisas.
     */
    @Volatile var torch = false
    /**
     * Calidad JPEG. 92 es el punto dulce para fotogrametría: ~35-45 % menos que 98 sin pérdida
     * medible de puntos SIFT. Por debajo de ~85 empieza a borrar microtextura.
     * (Medirlo con fotos propias: tools/compression_test.py.)
     */
    @Volatile var jpegQuality = 92
    @Volatile var lock3A = false

    /** Aplica a una petición los ajustes comunes de calidad. */
    private fun CaptureRequest.Builder.applyQuality(a: Active) {
        val ch = a.characteristics
        val nr = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
        when {
            CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL in nr -> set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
            CaptureRequest.NOISE_REDUCTION_MODE_OFF in nr -> set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        }
        val edge = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)
        if (CaptureRequest.EDGE_MODE_OFF in edge) set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        if (torch && ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        }
        // Bloquear antes de converger congelaría una exposición/enfoque arbitrarios
        // (pasaba al abrir cada cámara en el modo rotativo).
        if (lock3A && a.settled) {
            set(CaptureRequest.CONTROL_AE_LOCK, true)
            set(CaptureRequest.CONTROL_AWB_LOCK, true)
            val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (minFocus > 0f && a.lastFocus > 0f) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, a.lastFocus)
            }
        }
    }

    /** Etiqueta de cada foto de una ráfaga (HDR o barrido de foco). */
    data class ShotTag(val ev: Int = 0, val focusDiopters: Float? = null)

    /**
     * Plan de enfoque para una cámara: recibe (enfoque mínimo, enfoque actual) en dioptrías y
     * devuelve las distancias de enfoque a fotografiar. Lista vacía = foto normal.
     */
    fun interface FocusPlan { fun diopters(cameraId: String, minFocus: Float, current: Float): List<Float> }

    /**
     * Barrido de enfoque completo (depth from focus) de cerca a lejos. Para cada píxel, la foto
     * en la que está más nítido da su distancia, así se sabe qué está delante y qué detrás.
     * Con una sola foto no se puede: el desenfoque es igual a ambos lados del plano de enfoque.
     */
    fun captureFocusSweep(steps: Int = 12, farDiopters: Float = 1.0f): Int =
        capture(focusPlan = { _, minFocus, _ ->
            val far = farDiopters.coerceIn(0f, minFocus)
            (0 until steps).map { i -> minFocus + (far - minFocus) * i / (steps - 1).coerceAtLeast(1) }
        })

    /** Tiempo de exposición actual de la cámara (o de la lógica que la contiene). */
    fun exposureNsFor(cameraId: String): Long =
        active.firstOrNull { cameraId in it.readers.keys || it.group.deviceId == cameraId }?.lastExposureNs ?: 0L

    /** Distancia de enfoque actual de la cámara principal (dioptrías; 0 = infinito/desconocido). */
    fun primaryFocusDiopters(): Float = active.firstOrNull()?.lastFocus ?: 0f

    /** Re-aplica la petición repetitiva (tras cambiar torch / lock3A). */
    fun refreshRepeating() {
        for (a in active) runCatching { startRepeating(a) }
    }

    private fun startRepeating(act: Active) {
        val repeating = act.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(act.streamSurface)
            setAf(act.characteristics, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyQuality(act)
        }.build()
        act.session.setRepeatingRequest(repeating, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { act.lastExposureNs = it }
                res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { act.lastIso = it }
                val locked = lock3A && act.settled
                if (!locked) res.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { act.lastFocus = it }
                // Al converger, re-emitir la petición para que el bloqueo surta efecto.
                if (++act.frames == SETTLE_FRAMES && lock3A) camHandler.post { runCatching { startRepeating(act) } }
            }
        }, camHandler)
    }

    // Se leen desde el hilo de E/S de cámara (onImage) y se modifican desde el principal.
    private val active = java.util.concurrent.CopyOnWriteArrayList<Active>()
    /** Cámara que de verdad se ve en la vista previa tras la última apertura. */
    @Volatile var previewCameraId: String? = null
        private set
    val openedStreams = java.util.concurrent.CopyOnWriteArrayList<OpenedStream>()

    /** Pone el modo de AF sólo si el objetivo lo admite (los de foco fijo sólo admiten OFF). */
    private fun CaptureRequest.Builder.setAf(ch: CameraCharacteristics, mode: Int) {
        val modes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        if (mode in modes) set(CaptureRequest.CONTROL_AF_MODE, mode)
    }

    /**
     * @param preview superficie de previsualización para el grupo primario.
     * @param previewSize tamaño con el que se configuró `preview`.
     */
    suspend fun open(groups: List<CameraGroup>, preview: Surface?, previewSize: Size) {
        close()
        for (g in groups) {
            val device = openDevice(g.deviceId)
            if (device == null) { listener.onLog("No se pudo abrir la cámara ${g.deviceId}"); continue }
            val result = configure(device, g, if (g.primary) preview else null, previewSize)
            if (result == null) {
                listener.onLog("Ninguna configuración válida para ${g.deviceId}")
                device.close(); continue
            }
            active += result
            result.readers.forEach { (id, r) -> openedStreams += OpenedStream(id, Size(r.width, r.height), g.deviceId) }
        }
    }

    /** Petición de foto común a todos los modos. */
    private fun stillRequest(a: Active): CaptureRequest.Builder =
        a.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(a.streamSurface)
            a.readers.values.forEach { addTarget(it.surface) }
            // Sin rotación: la imagen queda en la orientación nativa del sensor,
            // coherente con los intrínsecos de LENS_INTRINSIC_CALIBRATION.
            set(CaptureRequest.JPEG_ORIENTATION, 0)
            set(CaptureRequest.JPEG_QUALITY, jpegQuality.coerceIn(50, 100).toByte())
            setAf(a.characteristics, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            // El OIS/EIS mueve el centro óptico: malo para la geometría.
            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            applyQuality(a)
        }

    /**
     * Dispara una captura en todas las cámaras abiertas. Devuelve el nº de imágenes pedidas.
     *
     * Por cámara, en este orden de preferencia:
     *  1. `focusPlan` y objetivo con foco ajustable -> ráfaga de enfoques (focus stacking).
     *  2. `bracketEv` y control manual -> ráfaga HDR (ISO fijo, tiempo × 2^EV).
     *  3. Foto normal (p.ej. la macro de foco fijo cuando se pide un apilado).
     */
    fun capture(bracketEv: List<Int> = emptyList(), focusPlan: FocusPlan? = null): Int {
        var n = 0
        for (a in active) {
            try {
                val minFocus = a.characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val focusList = if (focusPlan != null && minFocus > 0f) {
                    focusPlan.diopters(a.group.deviceId, minFocus, a.lastFocus).map { it.coerceIn(0f, minFocus) }.distinct()
                } else emptyList()
                val burst: List<CaptureRequest> = when {
                    focusList.size > 1 -> focusList.map { d ->
                        stillRequest(a).apply {
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            set(CaptureRequest.LENS_FOCUS_DISTANCE, d)
                            // Exposición constante entre planos: si no, cambiaría la medida de nitidez.
                            if (a.manualSensor && a.lastExposureNs > 0 && a.lastIso > 0) {
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                set(CaptureRequest.SENSOR_EXPOSURE_TIME, a.lastExposureNs)
                                set(CaptureRequest.SENSOR_SENSITIVITY, a.lastIso)
                            } else set(CaptureRequest.CONTROL_AE_LOCK, true)
                            setTag(ShotTag(focusDiopters = d))
                        }.build()
                    }
                    bracketEv.isNotEmpty() && a.manualSensor && a.lastExposureNs > 0 && a.lastIso > 0 ->
                        bracketEv.map { ev ->
                            var exp = (a.lastExposureNs * Math.pow(2.0, ev.toDouble())).toLong()
                            var iso = a.lastIso
                            // Si el tiempo no cabe en el rango, compensa con ISO.
                            a.exposureRange?.let { r ->
                                val clamped = exp.coerceIn(r.lower, minOf(r.upper, 250_000_000L))
                                iso = (iso * exp.toDouble() / clamped).toInt()
                                exp = clamped
                            }
                            a.isoRange?.let { r -> iso = iso.coerceIn(r.lower, r.upper) }
                            stillRequest(a).apply {
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                                setTag(ShotTag(ev = ev))
                            }.build()
                        }
                    else -> listOf(stillRequest(a).build())
                }
                if (burst.size == 1) a.session.capture(burst[0], metadataCallback(a), camHandler)
                else a.session.captureBurst(burst, metadataCallback(a), camHandler)
                n += a.readers.size * burst.size
            } catch (e: Exception) {
                listener.onLog("Error al capturar en ${a.group.deviceId}: ${e.message}")
            }
        }
        return n
    }

    fun close() {
        previewCameraId = null
        for (a in active) {
            runCatching { a.session.close() }
            runCatching { a.device.close() }
            a.readers.values.forEach { runCatching { it.close() } }
            runCatching { a.sink?.close() }
        }
        active.clear(); openedStreams.clear()
    }

    fun release() {
        close(); camThread.quitSafely(); ioThread.quitSafely()
    }

    // ---------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(id: String): CameraDevice? = suspendCancellableCoroutine { cont ->
        try {
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                // Si quien esperaba ya se canceló, cerrar: si no, la cámara quedaría ocupada.
                override fun onOpened(camera: CameraDevice) { if (cont.isActive) cont.resume(camera) else camera.close() }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); if (cont.isActive) cont.resume(null)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    listener.onLog("Cámara $id error $error")
                    camera.close(); if (cont.isActive) cont.resume(null)
                }
            })
        } catch (e: Exception) {
            listener.onLog("openCamera($id): ${e.message}")
            if (cont.isActive) cont.resume(null)
        }
    }

    /**
     * Prueba configuraciones de mayor a menor ambición hasta que una funciona:
     * físicas a máxima resolución → físicas a 4 MP → sólo lógica a máx → sólo lógica a 1440p.
     */
    private suspend fun configure(device: CameraDevice, g: CameraGroup, preview: Surface?, previewSize: Size): Active? {
        val concurrentCap = 1920L * 1440
        val attempts = buildList {
            if (g.physicalStreams.isNotEmpty()) {
                if (!g.concurrent) add(g.physicalStreams to 12_500_000L)
                add(g.physicalStreams to 4_000_000L)
            }
            if (!g.concurrent) add(emptyList<String>() to 12_500_000L)
            add(emptyList<String>() to concurrentCap)
        }
        for ((physical, maxPixels) in attempts) {
            // El objetivo de la vista previa va primero: es el "principal" de esta pasada.
            val ids = physical.ifEmpty { listOf(g.deviceId) }
                .sortedBy { if (it == g.previewPhysicalId) 0 else 1 }
            val readers = LinkedHashMap<String, ImageReader>()
            for (id in ids) {
                val size = pickJpegSize(id, maxPixels) ?: continue
                val r = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 4)
                r.setOnImageAvailableListener({ reader -> drain(id, reader) }, ioHandler)
                readers[id] = r
            }
            if (readers.isEmpty()) continue

            var sink: ImageReader? = null
            val streamSurface = preview ?: ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).also {
                sink = it
                it.setOnImageAvailableListener({ rd -> rd.acquireLatestImage()?.close() }, ioHandler)
            }.surface

            // La vista previa puede mostrar un objetivo físico concreto (la macro) en vez del
            // que elija la cámara lógica. Sólo si en este intento se piden flujos físicos.
            val previewPhys = g.previewPhysicalId?.takeIf { preview != null && physical.isNotEmpty() && it != g.deviceId }
            val outputs = mutableListOf(OutputConfiguration(streamSurface).apply {
                if (previewPhys != null) setPhysicalCameraId(previewPhys)
            })
            if (preview != null && g.previewPhysicalId != null && previewPhys == null && physical.isEmpty()) {
                listener.onLog("La vista previa no puede mostrar #${g.previewPhysicalId}: se ve la cámara #${g.deviceId}")
            }
            for ((id, r) in readers) {
                outputs += OutputConfiguration(r.surface).apply {
                    if (physical.isNotEmpty()) setPhysicalCameraId(id)
                }
            }
            val session = createSession(device, outputs)
            if (session != null) {
                if (preview != null) previewCameraId = previewPhys ?: g.deviceId
                val ch = manager.getCameraCharacteristics(g.deviceId)
                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                val act = Active(
                    g, device, session, streamSurface, readers, sink,
                    manualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                    exposureRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                    isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                )
                act.characteristics = ch
                startRepeating(act)
                listener.onLog("Cámara ${g.deviceId}: " + readers.entries.joinToString { "${it.key}@${it.value.width}x${it.value.height}" } +
                    if (act.manualSensor) " (manual/HDR ok)" else " (sin control manual)")
                return act
            }
            readers.values.forEach { it.close() }
            sink?.close()
        }
        return null
    }

    private suspend fun createSession(device: CameraDevice, outputs: List<OutputConfiguration>): CameraCaptureSession? =
        suspendCancellableCoroutine { cont ->
            val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) { if (cont.isActive) cont.resume(session) else session.close() }
                    override fun onConfigureFailed(session: CameraCaptureSession) { if (cont.isActive) cont.resume(null) }
                })
            try {
                @Suppress("DEPRECATION")
                val supported = runCatching { device.isSessionConfigurationSupported(cfg) }.getOrDefault(true)
                if (!supported) { cont.resume(null); return@suspendCancellableCoroutine }
                device.createCaptureSession(cfg)
            } catch (e: Exception) {
                Log.w(TAG, "createCaptureSession", e)
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun pickJpegSize(cameraId: String, maxPixels: Long): Size? {
        val c = manager.getCameraCharacteristics(cameraId)
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG) ?: return null
        // Preferir 4:3 (sensor completo, sin recorte) dentro del límite de píxeles.
        val within = sizes.filter { it.width.toLong() * it.height <= maxPixels }
        val fourThree = within.filter { kotlin.math.abs(it.width * 3 - it.height * 4) < it.width / 50 }
        return (fourThree.ifEmpty { within }).maxByOrNull { it.width.toLong() * it.height }
    }

    private fun drain(cameraId: String, reader: ImageReader) {
        val img = reader.acquireNextImage() ?: return
        try {
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            listener.onImage(cameraId, img.timestamp, img.width, img.height, bytes)
        } finally {
            img.close()
        }
    }

    private fun metadataCallback(a: Active) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val tag = request.tag as? ShotTag
            if (a.group.physicalStreams.isEmpty()) {
                emitMeta(a.group.deviceId, result, tag)
            } else {
                @Suppress("DEPRECATION")
                val phys = result.physicalCameraResults
                for (id in a.readers.keys) emitMeta(id, phys[id] ?: result, tag)
            }
        }
    }

    private fun emitMeta(cameraId: String, r: CaptureResult, tag: ShotTag?) {
        val ts = r.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val m = JSONObject()
        m.put("bracket_ev", tag?.ev ?: 0)
        tag?.focusDiopters?.let { m.put("focus_sweep_target_diopters", it.toDouble()) }
        r.get(CaptureResult.LENS_STATE)?.let { m.put("lens_moving", it == CaptureResult.LENS_STATE_MOVING) }
        r.get(CaptureResult.LENS_APERTURE)?.let { m.put("aperture_f", it.toDouble()) }
        r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { m.put("exposure_ns", it) }
        r.get(CaptureResult.SENSOR_SENSITIVITY)?.let { m.put("iso", it) }
        r.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)?.let { m.put("rolling_shutter_skew_ns", it) }
        r.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { m.put("focus_distance_diopters", it.toDouble()) }
        r.get(CaptureResult.LENS_FOCAL_LENGTH)?.let { m.put("focal_length_mm", it.toDouble()) }
        r.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)?.let { m.put("intrinsics_fx_fy_cx_cy_s", JSONArray(it.map { v -> v.toDouble() })) }
        r.get(CaptureResult.LENS_DISTORTION)?.let { m.put("distortion_k1_k2_k3_p1_p2", JSONArray(it.map { v -> v.toDouble() })) }
        r.get(CaptureResult.LENS_POSE_ROTATION)?.let { m.put("pose_rotation_xyzw", JSONArray(it.map { v -> v.toDouble() })) }
        r.get(CaptureResult.LENS_POSE_TRANSLATION)?.let { m.put("pose_translation_m", JSONArray(it.map { v -> v.toDouble() })) }
        listener.onCaptureMetadata(cameraId, ts, m)
    }

    /** Elige un tamaño de previsualización 4:3 razonable para el grupo primario. */
    fun previewSizeFor(cameraId: String): Size {
        val c = manager.getCameraCharacteristics(cameraId)
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: return Size(1280, 960)
        return sizes.filter { it.width <= 1600 && kotlin.math.abs(it.width * 3 - it.height * 4) < 20 }
            .maxByOrNull { it.width * it.height } ?: Size(1280, 960)
    }

    fun sensorOrientation(cameraId: String): Int =
        manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    companion object {
        private const val TAG = "MultiCamera"
        /** ~0,7 s a 30 fps: suficiente para que AE/AF/AWB converjan tras abrir. */
        private const val SETTLE_FRAMES = 20
    }
}
