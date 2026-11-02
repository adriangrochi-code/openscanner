package com.tisma.scanner.ui

import android.app.Application
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tisma.scanner.camera.CameraInfo
import com.tisma.scanner.camera.CameraInventory
import com.tisma.scanner.camera.MultiCameraController
import com.tisma.scanner.camera.RigPlanner
import com.tisma.scanner.ins.InsState
import com.tisma.scanner.ins.KeyframeSelector
import com.tisma.scanner.ins.StrapdownIns
import com.tisma.scanner.quality.BlurJudge
import com.tisma.scanner.quality.CoverageMap
import com.tisma.scanner.quality.JpegAnalyzer
import com.tisma.scanner.sensors.GpsRecorder
import com.tisma.scanner.sensors.ImuRecorder
import com.tisma.scanner.session.ScanSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2
import kotlin.math.asin

data class UiState(
    val cameras: List<String> = emptyList(),
    val openedStreams: List<String> = emptyList(),
    val concurrentSupported: Boolean = false,
    val useConcurrent: Boolean = true,
    val autoTrigger: Boolean = true,
    val hdr: Boolean = true,
    val torch: Boolean = false,
    val lock3A: Boolean = true,
    /** Modo "todas las cámaras por turnos": para móviles que no abren varias a la vez. */
    val rotateCameras: Boolean = false,
    val rotationIds: List<String> = emptyList(),
    val gpsText: String = "GPS: sin fix",
    val posStdM: Double = 0.0,
    val scanning: Boolean = false,
    val images: Int = 0,
    val triggers: Int = 0,
    val position: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
    val eulerDeg: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
    val stationary: Boolean = false,
    val gyroRate: Double = 0.0,
    val imuSamples: Long = 0,
    val log: List<String> = emptyList(),
    val lastZip: File? = null,
    val busy: Boolean = false,
    // --- Fase 1: cobertura y calidad en vivo ---
    val coverage: List<IntArray> = emptyList(),
    val coverageRings: List<CoverageMap.Ring> = CoverageMap.DEFAULT_RINGS,
    val coveragePct: Double = 0.0,
    val liveAzDeg: Double = 0.0,
    val liveElDeg: Double = 0.0,
    val analyzed: Int = 0,
    val blurry: Int = 0,
    val textureRecent: Double = 1.0,
    val hints: List<String> = emptyList(),
    val ready: Boolean = false,
    val readinessText: String = "",
)

class ScanViewModel(app: Application) : AndroidViewModel(app), MultiCameraController.Listener {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val cameraManager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val ins = StrapdownIns()
    private val imu = ImuRecorder(app, ins)
    private val selector = KeyframeSelector()
    private val gps = GpsRecorder(app) { _, loc ->
        ins.onGnss(
            loc.latitude, loc.longitude, loc.altitude, loc.accuracy.toDouble(),
            if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null,
        )
        _state.update {
            it.copy(gpsText = "GPS: %.6f, %.6f ±%.1f m".format(loc.latitude, loc.longitude, loc.accuracy))
        }
    }
    private val coverage = CoverageMap()
    private val blurJudge = BlurJudge()
    private val analysis = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pendingAnalysis = java.util.concurrent.atomic.AtomicInteger(0)
    private val recentTexture = ArrayDeque<Double>()
    @Volatile private var analyzedCount = 0
    @Volatile private var blurryCount = 0
    @Volatile private var skippedAnalysis = 0

    /** Evita disparos solapados (ráfagas HDR o secuencias de rotación en curso). */
    @Volatile private var sequenceRunning = false
    private val cameras = MultiCameraController(app, this)
    private var inventory: Map<String, CameraInfo> = emptyMap()

    @Volatile private var session: ScanSession? = null
    @Volatile private var trigger = 0
    private var loop: Job? = null
    private var previewTexture: SurfaceTexture? = null
    /** Corrección de base de tiempos para cámaras cuyo timestamp no es REALTIME. */
    private val clockOffset = HashMap<String, Long>()

    init {
        imu.start()
        inventory = runCatching { CameraInventory.enumerate(cameraManager) }.getOrDefault(emptyMap())
        val concurrentSets = CameraInventory.concurrentSets(cameraManager)
        for ((id, info) in inventory) {
            if (info.timestampSource != CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                // Base MONOTONIC (System.nanoTime) -> base elapsedRealtime (la de los sensores).
                clockOffset[id] = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
            }
        }
        _state.update {
            it.copy(
                cameras = inventory.values.map { c -> c.label },
                concurrentSupported = concurrentSets.any { s -> s.size > 1 },
                rotationIds = cameraManager.cameraIdList.toList(),
            )
        }
        log("Conjuntos concurrentes: " + concurrentSets.joinToString { s -> s.joinToString("+", "{", "}") })
        loop = viewModelScope.launch(Dispatchers.Default) { insLoop() }
    }

    // ------------------------------------------------------------------ cámaras

    /** Llamar cuando se concede el permiso de ubicación. */
    fun onLocationPermission(granted: Boolean) {
        if (granted && gps.start()) log("GPS activo") else log("GPS no disponible (sin permiso o desactivado)")
    }

    fun onPreviewAvailable(texture: SurfaceTexture) {
        previewTexture = texture
        reopenCameras()
    }

    fun onPreviewDestroyed() {
        previewTexture = null
        cameras.close()
    }

    private fun reopenCameras() {
        viewModelScope.launch { openRig(null) }
    }

    /** Abre el rig completo (single == null) o una única cámara (modo rotativo). */
    private suspend fun openRig(single: String?) {
        val tex = previewTexture ?: return
        run {
            _state.update { it.copy(busy = true) }
            val groups = if (single != null) {
                val info = inventory[single]
                listOf(com.tisma.scanner.camera.CameraGroup(
                    single, if (info?.isLogicalMultiCamera == true) info.physicalIds.take(3) else emptyList(),
                    primary = true, concurrent = false,
                ))
            } else RigPlanner.plan(
                inventory, CameraInventory.concurrentSets(cameraManager), _state.value.useConcurrent,
            )
            if (groups.isEmpty()) { log("No hay cámaras disponibles"); _state.update { it.copy(busy = false) }; return }
            val previewSize: Size = cameras.previewSizeFor(groups.first().deviceId)
            tex.setDefaultBufferSize(previewSize.width, previewSize.height)
            cameras.open(groups, Surface(tex), previewSize)
            _state.update {
                it.copy(
                    busy = false,
                    openedStreams = cameras.openedStreams.map { s ->
                        val label = inventory[s.cameraId]?.label ?: "#${s.cameraId}"
                        "$label → ${s.size.width}x${s.size.height}"
                    },
                )
            }
        }
    }

    fun setUseConcurrent(v: Boolean) {
        _state.update { it.copy(useConcurrent = v) }
        if (!_state.value.scanning) reopenCameras()
    }

    fun setAutoTrigger(v: Boolean) = _state.update { it.copy(autoTrigger = v) }
    fun setHdr(v: Boolean) = _state.update { it.copy(hdr = v) }
    fun setTorch(v: Boolean) {
        _state.update { it.copy(torch = v) }
        cameras.torch = v; cameras.refreshRepeating()
    }
    /** El bloqueo se aplica al iniciar el escaneo (antes, el AF/AE se ajustan libremente). */
    fun setLock3A(v: Boolean) = _state.update { it.copy(lock3A = v) }
    fun setRotateCameras(v: Boolean) = _state.update { it.copy(rotateCameras = v) }

    // ------------------------------------------------------------------ escaneo

    fun startScan() {
        if (session != null) return
        val base = File(getApplication<Application>().getExternalFilesDir(null), "scans").apply { mkdirs() }
        val s = ScanSession(base)
        s.writeSessionInfo(
            inventory.values,
            cameras.openedStreams.map { it.cameraId to "${it.size.width}x${it.size.height}" },
            JSONObject().apply {
                put("auto_trigger", _state.value.autoTrigger)
                put("concurrent", _state.value.useConcurrent)
                put("hdr_bracket_ev", org.json.JSONArray(HDR_EV))
                put("rotate_cameras", _state.value.rotateCameras)
                put("torch", _state.value.torch)
                put("lock_3a", _state.value.lock3A)
                put("min_angle_deg", selector.minAngleDeg)
                put("min_translation_m", selector.minTranslationM)
                put("max_gyro_rad_s", selector.maxGyroRadS)
                put("imu_sensors", org.json.JSONArray(imu.availableSensors().map { "${it.name} (${it.vendor}) ${it.minDelay}us" }))
            },
        )
        ins.reset(); selector.reset(); trigger = 0
        coverage.reset(); blurJudge.reset()
        synchronized(recentTexture) { recentTexture.clear() }
        analyzedCount = 0; blurryCount = 0; skippedAnalysis = 0
        cameras.lock3A = _state.value.lock3A; cameras.refreshRepeating()
        imu.startLogging(s.imuWriter())
        gps.startLogging(java.io.File(s.dir, "gps.csv").bufferedWriter())
        session = s
        _state.update {
            it.copy(scanning = true, images = 0, triggers = 0, lastZip = null, analyzed = 0, blurry = 0,
                coverage = coverage.snapshot(), coveragePct = 0.0, hints = coverage.hints(), ready = false)
        }
        log("Escaneo iniciado: ${s.dir.name}")
    }

    fun stopScan() {
        val s = session ?: return
        session = null
        imu.stopLogging()
        gps.stopLogging()
        cameras.lock3A = false; cameras.refreshRepeating()
        _state.update { it.copy(scanning = false, busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            delay(800) // deja llegar los últimos JPEG en vuelo
            // Espera a que termine el análisis pendiente (máx. 5 s).
            val deadline = SystemClock.uptimeMillis() + 5000
            while (pendingAnalysis.get() > 0 && SystemClock.uptimeMillis() < deadline) delay(50)
            s.writeQualitySummary(qualitySummary())
            s.close()
            val zip = runCatching { s.zip() }.getOrNull()
            log("Escaneo guardado: ${s.imageCount.get()} imágenes en ${s.dir.absolutePath}")
            _state.update { it.copy(busy = false, lastZip = zip) }
        }
    }

    /** Barrido de foco: mantener el móvil quieto (mejor apoyado) ~1-2 s. */
    fun focusSweep() {
        val s = session ?: return
        if (sequenceRunning) return
        sequenceRunning = true
        viewModelScope.launch {
            try {
                trigger++
                val before = s.imageCount.get()
                val expected = cameras.captureFocusSweep()
                log("Barrido de foco: $expected fotos" + if (!ins.isStationary()) " (¡el móvil se movía!)" else "")
                val deadline = SystemClock.uptimeMillis() + 5000
                while (s.imageCount.get() - before < expected && SystemClock.uptimeMillis() < deadline) delay(20)
            } finally {
                sequenceRunning = false
                cameras.refreshRepeating() // devuelve el AF a continuo
            }
        }
    }

    fun captureNow() {
        if (session == null) return
        fire(ins.latest())
    }

    private fun fire(st: InsState?) {
        if (sequenceRunning) return
        st?.let { selector.markTriggered(it) }
        val ui = _state.value
        // HDR sólo si el móvil está quieto: la ráfaga dura 3 exposiciones y no debe haber movimiento.
        val bracket = if (ui.hdr && (st?.stationary == true || imu.gyroRate < 0.05)) HDR_EV else emptyList()
        sequenceRunning = true
        viewModelScope.launch {
            try {
                if (ui.rotateCameras && ui.rotationIds.size > 1) {
                    // Pasada por todas las cámaras en la misma pose y vuelta a la principal.
                    for (id in ui.rotationIds) {
                        openRig(id)
                        delay(700) // convergencia de AE/AF
                        shoot(bracket)
                    }
                    openRig(null)
                } else {
                    shoot(bracket)
                }
            } finally {
                sequenceRunning = false
                ins.latest()?.let { selector.markTriggered(it) }
            }
        }
    }

    private suspend fun shoot(bracket: List<Int>) {
        val s = session ?: return
        trigger++
        val before = s.imageCount.get()
        val expected = cameras.capture(bracket)
        _state.update { it.copy(triggers = trigger) }
        // Espera a que lleguen los JPEG (o 2 s) antes de permitir otro disparo.
        val deadline = SystemClock.uptimeMillis() + 2000
        while (s.imageCount.get() - before < expected && SystemClock.uptimeMillis() < deadline) delay(20)
    }

    /** Bucle a ~100 Hz: refresca la UI, guarda trayectoria y decide disparos automáticos. */
    private suspend fun insLoop() {
        var lastUi = 0L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val st = ins.latest()
            val s = session
            if (st != null && s != null) {
                s.addTrajectory(st)
                if (_state.value.autoTrigger && !sequenceRunning && selector.shouldTrigger(st, imu.gyroRate)) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) { fire(st) }
                }
            }
            val now = SystemClock.uptimeMillis()
            if (st != null && now - lastUi > 100) {
                lastUi = now
                val q = st.orientation
                val roll = Math.toDegrees(atan2(2 * (q.w * q.x + q.y * q.z), 1 - 2 * (q.x * q.x + q.y * q.y)))
                val pitch = Math.toDegrees(asin((2 * (q.w * q.y - q.z * q.x)).coerceIn(-1.0, 1.0)))
                val yaw = Math.toDegrees(atan2(2 * (q.w * q.z + q.x * q.y), 1 - 2 * (q.y * q.y + q.z * q.z)))
                val (liveAz, liveEl) = CoverageMap.azEl(CoverageMap.viewDirection(st.orientation, backFacing = true))
                _state.update {
                    it.copy(
                        liveAzDeg = coverage.relativeAz(liveAz),
                        liveElDeg = liveEl,
                        position = Triple(st.position.x, st.position.y, st.position.z),
                        eulerDeg = Triple(roll, pitch, yaw),
                        stationary = st.stationary,
                        gyroRate = imu.gyroRate,
                        imuSamples = imu.sampleCount,
                        images = s?.imageCount?.get() ?: it.images,
                        posStdM = kotlin.math.sqrt(ins.positionVariance),
                    )
                }
            }
            delay(10)
        }
    }

    // ------------------------------------------------------------------ callbacks de cámara

    override fun onImage(cameraId: String, timestampNs: Long, width: Int, height: Int, jpeg: ByteArray) {
        val s = session ?: return
        val t = timestampNs + (clockOffset[cameraId] ?: 0L)
        val pose = ins.stateAt(t)
        runCatching { s.addImage(trigger, cameraId, t, width, height, jpeg, pose) }
            .onFailure { log("Error guardando imagen: ${it.message}") }
        // Análisis en segundo plano; si se acumula (ráfagas), se salta para no agotar memoria.
        if (pendingAnalysis.get() >= 6) { skippedAnalysis++; return }
        pendingAnalysis.incrementAndGet()
        analysis.execute {
            try { analyze(s, cameraId, t, width, jpeg, pose) } catch (e: Exception) {
                log("Análisis de calidad: ${e.message}")
            } finally { pendingAnalysis.decrementAndGet() }
        }
    }

    private fun analyze(s: ScanSession, cameraId: String, t: Long, width: Int, jpeg: ByteArray, pose: InsState?) {
        val q = JpegAnalyzer.analyze(jpeg, width) ?: return
        val blurry = blurJudge.isBlurry(cameraId, q.sharpness)
        val backFacing = inventory[cameraId]?.facing != CameraMetadata.LENS_FACING_FRONT
        var azEl: Pair<Double, Double>? = null
        var bin: Pair<Int, Int>? = null
        if (pose != null && backFacing) {
            val dir = CoverageMap.viewDirection(pose.orientation, backFacing = true)
            azEl = CoverageMap.azEl(dir)
            // Sólo las fotos nítidas y bien expuestas cuentan como cobertura.
            if (!blurry && q.clippedHigh < 0.25 && q.clippedLow < 0.4) bin = coverage.add(dir)
        }
        s.addQuality(cameraId, t, q, blurry, azEl, bin)
        analyzedCount++
        if (blurry) blurryCount++
        val tex = synchronized(recentTexture) {
            recentTexture.addLast(q.texture); while (recentTexture.size > 10) recentTexture.removeFirst()
            recentTexture.sorted()[recentTexture.size / 2]
        }
        val cov = coverage.coverage()
        val (ready, text) = readiness(cov)
        _state.update {
            it.copy(
                coverage = coverage.snapshot(), coveragePct = cov, analyzed = analyzedCount,
                blurry = blurryCount, textureRecent = tex, hints = coverage.hints().take(3),
                ready = ready, readinessText = text,
            )
        }
    }

    /** Criterios mínimos antes de gastar cómputo en la nube. */
    private fun readiness(cov: Double): Pair<Boolean, String> {
        val blurRatio = if (analyzedCount == 0) 0.0 else blurryCount.toDouble() / analyzedCount
        val problems = buildList {
            if (cov < MIN_COVERAGE) add("cobertura ${(cov * 100).toInt()} % (mín. ${(MIN_COVERAGE * 100).toInt()} %)")
            if (analyzedCount < MIN_IMAGES) add("$analyzedCount fotos (mín. $MIN_IMAGES)")
            if (blurRatio > MAX_BLUR_RATIO) add("${(blurRatio * 100).toInt()} % movidas (máx. ${(MAX_BLUR_RATIO * 100).toInt()} %)")
        }
        return if (problems.isEmpty()) true to "✓ Listo para enviar" else false to "Falta: " + problems.joinToString(", ")
    }

    private fun qualitySummary() = JSONObject().apply {
        val cov = coverage.coverage()
        put("coverage", cov)
        put("coverage_2plus", coverage.coverage(2))
        put("rings", org.json.JSONArray(coverage.rings.indices.map { coverage.ringCoverage(it) }))
        put("images_analyzed", analyzedCount)
        put("images_skipped_analysis", skippedAnalysis)
        put("blurry", blurryCount)
        put("texture_recent_median", _state.value.textureRecent)
        put("ready", readiness(cov).first)
        put("hints", org.json.JSONArray(coverage.hints()))
    }

    override fun onCaptureMetadata(cameraId: String, timestampNs: Long, meta: JSONObject) {
        val t = timestampNs + (clockOffset[cameraId] ?: 0L)
        session?.addCaptureMeta(trigger, cameraId, t, meta)
    }

    override fun onLog(msg: String) = log(msg)

    private fun log(msg: String) {
        android.util.Log.i("TismaScanner", msg)
        _state.update { it.copy(log = (it.log + msg).takeLast(8)) }
    }

    override fun onCleared() {
        stopScan()
        loop?.cancel()
        imu.stop()
        gps.stop()
        cameras.release()
        analysis.shutdown()
    }

    companion object {
        val HDR_EV = listOf(-2, 0, 2)
        const val MIN_COVERAGE = 0.75
        const val MIN_IMAGES = 40
        const val MAX_BLUR_RATIO = 0.2
        /** Por debajo de esto la pieza apenas tiene textura emparejable. */
        const val LOW_TEXTURE = 0.3
    }
}
