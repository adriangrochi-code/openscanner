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
import com.tisma.scanner.diag.DiagLog
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
        /**
         * Fotograma pequeño en grises de la vista previa (p.ej. 160×120) para el velocímetro
         * óptico. `cameraId` es el objetivo que se ve.
         */
        fun onFlowFrame(cameraId: String, timestampNs: Long, w: Int, h: Int, gray: FloatArray) {}
        /**
         * Modo vídeo: ¿guardar este fotograma del flujo continuo? Se pregunta por cada uno; si
         * devuelve true, se codifica a JPEG y llega por [onImage] como una foto más.
         */
        fun wantVideoFrame(cameraId: String, timestampNs: Long, width: Int): Boolean = false
        /**
         * Modo vídeo: fotograma clave reducido para la reconstrucción en vivo — grises
         * [KF_W]×[KF_H] y miniatura en color [THUMB_W]×[THUMB_H], en la orientación del sensor.
         */
        fun onVideoKeyframe(cameraId: String, timestampNs: Long, gray: FloatArray, thumb: IntArray) {}
    }

    /** Lo que finalmente se ha conseguido abrir. */
    data class OpenedStream(val cameraId: String, val size: Size, val viaGroup: String)

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val camThread = HandlerThread("camera").apply { start() }
    private val ioThread = HandlerThread("camera-io").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private val ioHandler = Handler(ioThread.looper)
    private val flowThread = HandlerThread("camera-flow").apply { start() }
    private val flowHandler = Handler(flowThread.looper)
    private val videoThread = HandlerThread("camera-video").apply { start() }
    private val videoHandler = Handler(videoThread.looper)
    private val encodeThread = HandlerThread("camera-encode").apply { start() }
    private val encodeHandler = Handler(encodeThread.looper)
    @Volatile private var encoding = false

    /**
     * Modo "Vídeo 360": sólo la cámara de la vista previa, con un flujo YUV continuo grande en vez
     * de fotos. La vista previa nunca se para; los fotogramas clave salen del flujo.
     */
    @Volatile var videoMode = false
    private val executor = Executor { camHandler.post(it) }

    private inner class Active(
        val group: CameraGroup,
        val device: CameraDevice,
        val session: CameraCaptureSession,
        val streamSurface: Surface,           // previsualización o flujo "sumidero"
        val readers: Map<String, ImageReader>, // cameraId -> lector JPEG
        val sink: ImageReader?,
        /** Flujo YUV pequeño para el velocímetro óptico (null si el móvil no admite el flujo extra). */
        val flow: ImageReader?,
        val manualSensor: Boolean,
        val exposureRange: android.util.Range<Long>?,
        val isoRange: android.util.Range<Int>?,
        /** Modo vídeo: flujo YUV continuo grande (fotogramas clave + velocímetro). */
        val video: ImageReader? = null,
    ) {
        /** Resultados recientes de la repetitiva (por timestamp) para los metadatos de cada fotograma. */
        val recentResults = java.util.concurrent.ConcurrentHashMap<Long, TotalCaptureResult>()
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
    /**
     * Con lock3A, fijar también el foco a mano. Algunos móviles (cámara lógica multi-objetivo)
     * paran la vista previa con el foco manual: entonces se bloquea sólo exposición y balance.
     */
    @Volatile var lockFocus = true

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
            if (lockFocus && minFocus > 0f && a.lastFocus > 0f) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, a.lastFocus)
            }
        }
    }

    /** Etiqueta de cada foto de una ráfaga (HDR o barrido de foco). */
    data class ShotTag(val ev: Int = 0, val focusDiopters: Float? = null, val burstIndex: Int? = null)

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

    /** Tamaño del flujo de vídeo abierto (null si no se está en modo vídeo). */
    fun videoSize(): Size? = active.firstNotNullOfOrNull { it.video }?.let { Size(it.width, it.height) }

    /** Distancia de enfoque actual de la cámara principal (dioptrías; 0 = infinito/desconocido). */
    fun primaryFocusDiopters(): Float = active.firstOrNull()?.lastFocus ?: 0f

    /** Re-aplica la petición repetitiva (tras cambiar torch / lock3A). */
    fun refreshRepeating() {
        for (a in active) runCatching { startRepeating(a) }
    }

    private fun startRepeating(act: Active) {
        val repeating = act.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(act.streamSurface)
            act.flow?.let { addTarget(it.surface) }
            act.video?.let { v ->
                addTarget(v.surface)
                // Exposición corta y constante: a 30 fps fijos cada fotograma dura ≤ 33 ms (menos arrastre).
                act.characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.filter { it.lower >= 24 }?.maxWithOrNull(compareBy({ it.lower }, { -it.upper }))
                    ?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            }
            setAf(act.characteristics, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyQuality(act)
        }.build()
        DiagLog.event("cam", "repetitiva #${act.group.deviceId}: lock3A=$lock3A foco_fijo=${lockFocus && lock3A && act.settled} " +
            "asentada=${act.settled} foco=%.2f dpt linterna=$torch flujo=${act.flow != null}".format(act.lastFocus))
        act.session.setRepeatingRequest(repeating, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                DiagLog.count("cam_fail")
                if ((DiagLog.sinceMs("cam_fail_ev") ?: Long.MAX_VALUE) > 1000) {
                    DiagLog.tick("cam_fail_ev")
                    DiagLog.event("cam", "fallo de fotograma #${act.group.deviceId}: motivo ${if (f.reason == 0) "error" else "descartado"} · imagen capturada=${f.wasImageCaptured()}")
                }
            }
            override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frame: Long) {
                DiagLog.count("cam_buffer_lost")
            }
            override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) {
                DiagLog.event("cam", "repetitiva #${act.group.deviceId} abortada (secuencia $sequenceId)")
            }
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                DiagLog.tick("cam_frame")
                if (act.video != null) res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { ts ->
                    act.recentResults[ts] = res
                    if (act.recentResults.size > 12) act.recentResults.keys.sorted().dropLast(12).forEach { act.recentResults.remove(it) }
                }
                res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { act.lastExposureNs = it }
                res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { act.lastIso = it }
                val locked = lock3A && lockFocus && act.settled
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
        // En modo vídeo sólo se abre la cámara de la vista previa.
        @Suppress("NAME_SHADOWING")
        val groups = if (videoMode && preview != null) groups.filter { it.primary }.take(1) else groups
        DiagLog.event("cam", "abrir: " + groups.joinToString { "#${it.deviceId}${it.physicalStreams}" + (it.previewPhysicalId?.let { v -> " vista $v" } ?: "") } +
            " · vista ${previewSize.width}x${previewSize.height}")
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
            result.video?.let { v -> openedStreams += OpenedStream(g.deviceId, Size(v.width, v.height), g.deviceId) }
            DiagLog.event("cam", "abierta #${g.deviceId}: JPEG " + result.readers.entries.joinToString { (id, r) -> "$id ${r.width}x${r.height}" } +
                " · velocímetro ${if (result.flow != null) "sí" else "no"}")
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
    fun capture(bracketEv: List<Int> = emptyList(), focusPlan: FocusPlan? = null, burstFrames: Int = 0): Int {
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
                    // Ráfaga anti-movimiento: N fotos de exposición 1/N (ISO ×N para el mismo brillo).
                    // Cada una tiene N veces menos arrastre; el servidor las alinea y promedia,
                    // lo que devuelve buena parte del ruido extra (≈ √N).
                    burstFrames > 1 && a.manualSensor && a.lastExposureNs > 0 && a.lastIso > 0 -> {
                        var iso = a.lastIso * burstFrames
                        a.isoRange?.let { r -> iso = iso.coerceIn(r.lower, r.upper) }
                        var exp = (a.lastExposureNs.toDouble() * a.lastIso / iso).toLong()
                        a.exposureRange?.let { r -> exp = exp.coerceIn(r.lower, r.upper) }
                        (0 until burstFrames).map { k ->
                            stillRequest(a).apply {
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                                setTag(ShotTag(burstIndex = k))
                            }.build()
                        }
                    }
                    else -> listOf(stillRequest(a).build())
                }
                DiagLog.event("foto", "#${a.group.deviceId}: ${burst.size} petición(es)" +
                    (if (focusList.size > 1) " · barrido de foco" else if (bracketEv.isNotEmpty()) " · HDR" else if (burstFrames > 1) " · ráfaga" else "") +
                    " · ${a.readers.size} lector(es) JPEG")
                if (burst.size == 1) a.session.capture(burst[0], metadataCallback(a), camHandler)
                else a.session.captureBurst(burst, metadataCallback(a), camHandler)
                n += a.readers.size * burst.size
            } catch (e: Exception) {
                listener.onLog("Error al capturar en ${a.group.deviceId}: ${e.message}")
                DiagLog.event("foto", "error #${a.group.deviceId}: $e")
            }
        }
        return n
    }

    fun close() {
        if (active.isNotEmpty()) DiagLog.event("cam", "cerrar " + active.joinToString { "#${it.group.deviceId}" })
        previewCameraId = null
        for (a in active) {
            runCatching { a.session.close() }
            runCatching { a.device.close() }
            a.readers.values.forEach { runCatching { it.close() } }
            runCatching { a.sink?.close() }
            runCatching { a.flow?.close() }
            runCatching { a.video?.close() }
        }
        active.clear(); openedStreams.clear()
    }

    fun release() {
        close(); camThread.quitSafely(); ioThread.quitSafely(); flowThread.quitSafely()
        videoThread.quitSafely(); encodeThread.quitSafely()
    }

    // ---------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(id: String): CameraDevice? = suspendCancellableCoroutine { cont ->
        try {
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                // Si quien esperaba ya se canceló, cerrar: si no, la cámara quedaría ocupada.
                override fun onOpened(camera: CameraDevice) { if (cont.isActive) cont.resume(camera) else camera.close() }
                override fun onDisconnected(camera: CameraDevice) {
                    DiagLog.event("cam", "cámara $id desconectada (otra app o el sistema la tomó)")
                    camera.close(); if (cont.isActive) cont.resume(null)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    listener.onLog("Cámara $id error $error")
                    DiagLog.event("cam", "cámara $id error $error")
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
        if (videoMode && preview != null) configureVideo(device, g, preview)?.let { return it }
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

            // Con vista previa se intenta añadir un flujo YUV pequeño para el velocímetro óptico;
            // si el móvil no admite esa combinación, se abre sin él (como antes).
            for (withFlow in if (preview != null) listOf(true, false) else listOf(false)) {
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
                val flowCam = previewPhys ?: g.deviceId
                val flow = if (withFlow) flowReaderFor(flowCam) else null
                flow?.let { fr ->
                    outputs += OutputConfiguration(fr.surface).apply { if (previewPhys != null) setPhysicalCameraId(previewPhys) }
                }
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
                        g, device, session, streamSurface, readers, sink, flow,
                        manualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                        exposureRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                        isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                    )
                    act.characteristics = ch
                    startRepeating(act)
                    listener.onLog("Cámara ${g.deviceId}: " + readers.entries.joinToString { "${it.key}@${it.value.width}x${it.value.height}" } +
                        (if (act.manualSensor) " (manual/HDR ok)" else " (sin control manual)") +
                        (if (flow != null) " + velocímetro ${flow.width}x${flow.height}" else ""))
                    return act
                }
                flow?.close()
                sink?.close()
            }
            readers.values.forEach { it.close() }
        }
        return null
    }

    /** Modo vídeo: vista previa + flujo YUV continuo, el mayor tamaño 4:3 que el móvil acepte. */
    private suspend fun configureVideo(device: CameraDevice, g: CameraGroup, preview: Surface): Active? {
        val ch = manager.getCameraCharacteristics(g.deviceId)
        val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: return null
        for (size in VideoFrames.candidateSizes(sizes)) {
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            val session = createSession(device, listOf(OutputConfiguration(preview), OutputConfiguration(reader.surface)))
            if (session == null) { reader.close(); DiagLog.event("cam", "vídeo ${size.width}x${size.height} no admitido"); continue }
            previewCameraId = g.deviceId
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val act = Active(
                g, device, session, preview, emptyMap(), null, null,
                manualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                exposureRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                video = reader,
            )
            act.characteristics = ch
            reader.setOnImageAvailableListener({ rd -> onVideoFrame(act, rd) }, videoHandler)
            startRepeating(act)
            listener.onLog("Cámara ${g.deviceId}: vídeo 360 a ${size.width}x${size.height}")
            return act
        }
        return null
    }

    private fun onVideoFrame(act: Active, rd: ImageReader) {
        val img = rd.acquireLatestImage() ?: return
        val id = act.group.deviceId
        var nv21: ByteArray? = null
        val ts = img.timestamp
        val w = img.width; val h = img.height
        try {
            DiagLog.tick("flujo")
            // Velocímetro a ~15 fps (cada 2 fotogramas): suficiente y libera la CPU para el JPEG.
            if (act.frames % 2 == 0) listener.onFlowFrame(id, ts, FLOW_W, FLOW_H, VideoFrames.gray(img, FLOW_W, FLOW_H))
            if (!encoding && listener.wantVideoFrame(id, ts, w)) {
                encoding = true
                nv21 = VideoFrames.toNv21(img)
            }
        } catch (e: Exception) {
            DiagLog.event("vídeo", "error de fotograma: $e"); encoding = false
        } finally {
            img.close()
        }
        val data = nv21 ?: return
        encodeHandler.post {
            try {
                // Primero lo pequeño (la nube en vivo no espera al JPEG).
                listener.onVideoKeyframe(id, ts, VideoFrames.grayFromNv21(data, w, h, KF_W, KF_H),
                    VideoFrames.rgbFromNv21(data, w, h, THUMB_W, THUMB_H))
                val t0 = System.nanoTime()
                val bos = java.io.ByteArrayOutputStream(w * h / 4)
                android.graphics.YuvImage(data, ImageFormat.NV21, w, h, null)
                    .compressToJpeg(android.graphics.Rect(0, 0, w, h), jpegQuality.coerceIn(50, 100), bos)
                DiagLog.max("jpeg_ms", (System.nanoTime() - t0) / 1_000_000)
                // Metadatos del mismo fotograma (exposición, foco, intrínsecos) antes que la imagen.
                act.recentResults.remove(ts)?.let { emitMeta(id, it, null) }
                listener.onImage(id, ts, w, h, bos.toByteArray())
            } catch (e: Exception) {
                DiagLog.event("vídeo", "error JPEG: $e")
            } finally {
                encoding = false
            }
        }
    }

    /** Lector YUV ~320×240 (4:3) para el flujo óptico; entrega fotogramas 160×120 en grises. */
    private fun flowReaderFor(cameraId: String): ImageReader? {
        val sizes = runCatching {
            manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
        }.getOrNull() ?: return null
        val size = sizes.filter { it.width >= 320 && kotlin.math.abs(it.width * 3 - it.height * 4) < 8 }
            .minByOrNull { it.width * it.height } ?: return null
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            DiagLog.tick("flujo")
            try {
                val plane = img.planes[0]
                val buf = plane.buffer
                val step = img.width / FLOW_W
                val out = FloatArray(FLOW_W * FLOW_H)
                // Promedio de bloques step×step de la luminancia (Y): reduce ruido y tamaño.
                for (y in 0 until FLOW_H) for (x in 0 until FLOW_W) {
                    var acc = 0
                    for (j in 0 until step) for (i in 0 until step) {
                        acc += buf.get((y * step + j) * plane.rowStride + (x * step + i) * plane.pixelStride).toInt() and 0xFF
                    }
                    out[y * FLOW_W + x] = acc.toFloat() / (step * step)
                }
                listener.onFlowFrame(cameraId, img.timestamp, FLOW_W, FLOW_H, out)
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, flowHandler)
        return r
    }

    private suspend fun createSession(device: CameraDevice, outputs: List<OutputConfiguration>): CameraCaptureSession? =
        suspendCancellableCoroutine { cont ->
            val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) { if (cont.isActive) cont.resume(session) else session.close() }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        DiagLog.event("cam", "sesión ${device.id}: configuración rechazada")
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onClosed(session: CameraCaptureSession) { DiagLog.event("cam", "sesión ${device.id} cerrada") }
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
        tag?.burstIndex?.let { m.put("burst_index", it) }
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
        /** Tamaño de la imagen del velocímetro óptico. */
        const val KF_W = 320
        const val KF_H = 240
        const val THUMB_W = 160
        const val THUMB_H = 120
        const val FLOW_W = 160
        const val FLOW_H = 120
    }
}
