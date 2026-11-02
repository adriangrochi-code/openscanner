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
import com.tisma.scanner.camera.CameraGroup
import com.tisma.scanner.camera.CameraRoles
import com.tisma.scanner.camera.Lens
import com.tisma.scanner.camera.Optics
import com.tisma.scanner.camera.PassRigPlanner
import com.tisma.scanner.camera.RigPlan
import com.tisma.scanner.camera.ScanPass
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
import kotlinx.coroutines.sync.withLock
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
    /** Si la cámara compañera no se puede abrir a la vez, dispararla por turnos. */
    val rotateCameras: Boolean = true,
    /** Papel detectado de cada objetivo y cómo se combinan en la pasada actual. */
    val rolesText: String = "",
    val rigText: String = "",
    /** Aviso si la pasada no puede usar la cámara que le toca (p.ej. el móvil oculta la macro). */
    val rigWarning: String = "",
    /** Cámaras traseras elegibles a mano para la vista previa de cada pasada: id -> etiqueta. */
    val cameraChoices: List<Pair<String, String>> = emptyList(),
    /** Elección manual de la vista previa por pasada (null = automática). */
    val previewChoice: Map<ScanPass, String?> = emptyMap(),
    // --- Precisión de la INS ---
    /** Segundos restantes de la prueba de quietud (0 = no está en marcha). */
    val imuTestRemaining: Int = 0,
    val imuReport: List<String> = emptyList(),
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
    // --- Pasadas lejos/cerca + enfoque ---
    val pass: ScanPass = ScanPass.FAR,
    val passCoverage: Map<ScanPass, Double> = emptyMap(),
    val partDepthCm: Int = 3,
    /** Distancia, resolución y profundidad de campo actuales según el enfoque. */
    val opticsText: String = "",
    // --- Descarte de movidas y compresión ---
    val discardBlurry: Boolean = true,
    val jpegQuality: Int = 92,
    val discarded: Int = 0,
    val savedMb: Double = 0.0,
    val storedMb: Double = 0.0,
)

class ScanViewModel(app: Application) : AndroidViewModel(app), MultiCameraController.Listener, ScanActions {

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
    private val coverageMaps = ScanPass.entries.associateWith { CoverageMap() }
    @Volatile private var activePass = ScanPass.FAR
    private val coverage get() = coverageMaps.getValue(activePass)
    /** Disparos que son apilados de enfoque: sus planos desenfocados no son "fotos movidas". */
    private val stackTriggers = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** (disparo, cámara) ya contados en la cobertura: una ráfaga HDR/apilado cuenta una vez. */
    private val coveredShots = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val blurJudge = BlurJudge()
    private val analysis = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pendingAnalysis = java.util.concurrent.atomic.AtomicInteger(0)
    private val recentTexture = ArrayDeque<Double>()
    @Volatile private var analyzedCount = 0
    @Volatile private var blurryCount = 0
    @Volatile private var skippedAnalysis = 0
    @Volatile private var discardedCount = 0
    @Volatile private var discardedBytes = 0L
    private val storedBytes = java.util.concurrent.atomic.AtomicLong(0)

    /** Evita disparos solapados (ráfagas HDR o secuencias de rotación en curso). */
    @Volatile private var sequenceRunning = false
    private val cameras = MultiCameraController(app, this)
    private var inventory: Map<String, CameraInfo> = emptyMap()
    private var lenses: List<Lens> = emptyList()
    private var roles = CameraRoles(null, null, null, null)
    private var concurrentSets: List<Set<String>> = emptyList()
    @Volatile private var rigPlan = RigPlan(emptyList(), emptyList(), null, null, RigPlan.Mode.SINGLE)

    @Volatile private var session: ScanSession? = null
    /** Escaneo que se está cerrando: sigue aceptando las fotos y metadatos aún en vuelo. */
    @Volatile private var stopping: ScanSession? = null
    /** Escritura de JPEG a disco fuera del hilo de la cámara (que no debe bloquearse). */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pendingWrites = java.util.concurrent.atomic.AtomicInteger(0)
    /** Exposición real de cada foto ("cámara/t" -> ns), del resultado de captura. */
    private val exposures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Serializa aperturas de cámara (rotación, cambios de ajustes, vista previa). */
    private val rigMutex = kotlinx.coroutines.sync.Mutex()
    private var previewSurface: Surface? = null
    @Volatile private var trigger = 0
    private var loop: Job? = null
    private var previewTexture: SurfaceTexture? = null
    /** Corrección de base de tiempos para cámaras cuyo timestamp no es REALTIME. */
    private val clockOffset = HashMap<String, Long>()

    init {
        imu.start()
        inventory = runCatching { CameraInventory.enumerate(cameraManager) }.getOrDefault(emptyMap())
        concurrentSets = CameraInventory.concurrentSets(cameraManager)
        val public = runCatching { cameraManager.cameraIdList.toSet() }.getOrDefault(emptySet())
        lenses = inventory.values.map { c ->
            Lens(
                id = c.id, back = c.facing == CameraMetadata.LENS_FACING_BACK,
                // Las encontradas probando su número también se intentan abrir directamente.
                logical = c.isLogicalMultiCamera, physicalIds = c.physicalIds, isPublic = c.id in public || c.probed,
                pixels = c.sensorPixels,
                hfovDeg = c.hfovDeg,
            )
        }
        roles = CameraRoles.detect(lenses)
        updateClockOffsets()
        _state.update {
            it.copy(
                cameras = inventory.values.map { c -> c.label },
                concurrentSupported = concurrentSets.any { s -> s.size > 1 },
                rolesText = roles.describe(),
                cameraChoices = inventory.values
                    .filter { c -> c.facing == CameraMetadata.LENS_FACING_BACK }
                    .map { c -> c.id to roleName(c.id) + " · %.0f MP".format(c.sensorPixels / 1e6) + (c.hfovDeg?.let { f -> " · ${f.toInt()}°" } ?: "") },
            )
        }
        val hidden = inventory.values.filter { it.probed }.map { it.id }
        if (hidden.isNotEmpty()) log("Cámaras ocultas encontradas por número: ${hidden.joinToString()}")
        log("Objetivos: ${roles.describe()}")
        log("Conjuntos concurrentes: " + concurrentSets.joinToString { s -> s.joinToString("+", "{", "}") })
        loop = viewModelScope.launch(Dispatchers.Default) { insLoop() }
    }

    /**
     * Base MONOTONIC (System.nanoTime) -> base elapsedRealtime (la de los sensores).
     * La diferencia crece cada vez que el móvil duerme, así que se recalcula en cada escaneo.
     */
    private fun updateClockOffsets() {
        val off = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
        for ((id, info) in inventory) {
            if (info.timestampSource != CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) clockOffset[id] = off
        }
    }

    // ------------------------------------------------------------------ cámaras

    /** Llamar cuando se concede el permiso de ubicación. */
    fun onLocationPermission(granted: Boolean) {
        if (granted && gps.start()) log("GPS activo") else log("GPS no disponible (sin permiso o desactivado)")
    }

    override fun onPreviewAvailable(texture: SurfaceTexture) {
        previewTexture = texture
        reopenCameras()
    }

    override fun onPreviewDestroyed() {
        previewTexture = null
        cameras.close()
        previewSurface?.release(); previewSurface = null
    }

    private fun reopenCameras() {
        viewModelScope.launch { openRig(planCurrentPass().groups) }
    }

    /** Recalcula qué cámaras abre la pasada actual (vista previa + compañera). */
    private fun roleName(id: String?): String = when (id) {
        null -> "—"; roles.main -> "principal #$id"; roles.macro -> "macro #$id"
        roles.ultrawide -> "ultra gran angular #$id"; roles.tele -> "tele #$id"; else -> "cámara #$id"
    }

    /** Papeles con la elección manual aplicada: Lejos cambia la principal, Cerca la macro. */
    private fun rolesFor(p: ScanPass): CameraRoles {
        val choice = _state.value.previewChoice[p] ?: return roles
        return when (p) {
            ScanPass.FAR -> roles.copy(main = choice)
            ScanPass.CLOSE -> roles.copy(macro = choice)
        }
    }

    private fun planFor(p: ScanPass) =
        PassRigPlanner.plan(p, rolesFor(p), lenses, concurrentSets, _state.value.useConcurrent)

    /** Recalcula qué cámaras abre la pasada actual (vista previa + compañera). */
    private fun planCurrentPass(): RigPlan {
        val plan = planFor(activePass)
        rigPlan = plan
        val text = "Vista: ${roleName(plan.previewId)}" +
            (plan.companionId?.let { " + ${roleName(it)}" } ?: "") + " — ${plan.mode.label}"
        val other = planFor(if (activePass == ScanPass.FAR) ScanPass.CLOSE else ScanPass.FAR)
        val warning = when {
            plan.previewId == null -> "No se encontró ninguna cámara trasera"
            plan.previewId == other.previewId ->
                "Lejos y Cerca usan la misma cámara (${roleName(plan.previewId)}): no se detectó " +
                    (if (activePass == ScanPass.CLOSE) "la macro" else "otra cámara") +
                    ". Elige una en la pestaña Cámaras o comparte el diagnóstico."
            else -> ""
        }
        _state.update { it.copy(rigText = text, rigWarning = warning) }
        if (warning.isNotEmpty()) log(warning)
        return plan
    }

    /** Elegir a mano qué cámara se ve en una pasada (null = automática). */
    override fun setPreviewCamera(pass: ScanPass, id: String?) {
        _state.update { it.copy(previewChoice = it.previewChoice + (pass to id)) }
        if (pass == activePass && !sequenceRunning) {
            val plan = planCurrentPass()
            viewModelScope.launch { openRig(plan.groups) }
        }
    }

    /**
     * Prueba de quietud: 60 s con el móvil apoyado. Mide ruido, sesgo y la deriva real de la
     * INS en este móvil, y deja el sesgo del giróscopo calibrado para los escaneos.
     */
    override fun runImuStillTest() {
        if (_state.value.imuTestRemaining > 0 || session != null) return
        viewModelScope.launch {
            imu.startStillCapture()
            for (s in IMU_TEST_SECONDS downTo 1) {
                _state.update { it.copy(imuTestRemaining = s, imuReport = listOf("Midiendo… no toques el móvil ($s s)")) }
                delay(1000)
            }
            val (acc, gyr) = imu.stopStillCapture()
            _state.update { it.copy(imuTestRemaining = 0, imuReport = listOf("Analizando…")) }
            val report = kotlinx.coroutines.withContext(Dispatchers.Default) {
                com.tisma.scanner.ins.ImuStillTest.analyze(acc, gyr)
            }
            if (report == null) {
                _state.update { it.copy(imuReport = listOf("No hay datos suficientes del IMU")) }
                return@launch
            }
            if (!report.moved) {
                ins.gyroBiasPrior = report.gyroBiasDegS * Math.toRadians(1.0)
                ins.reset()
            }
            _state.update { it.copy(imuReport = report.lines()) }
            report.lines().forEach { log(it) }
            runCatching {
                val dir = File(getApplication<Application>().getExternalFilesDir(null), "scans").apply { mkdirs() }
                File(dir, "imu_test_${System.currentTimeMillis()}.txt").writeText(report.lines().joinToString("\n"))
            }
        }
    }

    override fun diagnostics(): String = buildString {
        append(CameraInventory.diagnostics(cameraManager, inventory))
        appendLine("Papeles: ${roles.describe()}")
        for (p in ScanPass.entries) {
            val plan = planFor(p)
            appendLine("${p.label}: vista ${plan.previewId} + ${plan.companionId} · ${plan.mode} · grupos " +
                plan.groups.joinToString { "${it.deviceId}${it.physicalStreams}${it.previewPhysicalId?.let { v -> "/vista $v" } ?: ""}" } +
                (if (plan.separate.isNotEmpty()) " · por turnos " + plan.separate.joinToString { it.deviceId } else ""))
        }
        appendLine("Flujos abiertos: " + cameras.openedStreams.joinToString { "${it.cameraId}@${it.size.width}x${it.size.height}" })
        appendLine("Registro:")
        _state.value.log.forEach { appendLine("  $it") }
    }

    /** Abre un conjunto de cámaras; el primer grupo lleva la vista previa. */
    private suspend fun openRig(groups: List<CameraGroup>) = rigMutex.withLock {
        val tex = previewTexture ?: return@withLock
        run {
            _state.update { it.copy(busy = true) }
            if (groups.isEmpty()) { log("No hay cámaras disponibles"); _state.update { it.copy(busy = false) }; return@withLock }
            val first = groups.first()
            val previewSize: Size = cameras.previewSizeFor(first.previewPhysicalId ?: first.deviceId)
            tex.setDefaultBufferSize(previewSize.width, previewSize.height)
            // Una sola Surface por textura: crear una nueva en cada apertura las filtraba.
            val surface = previewSurface ?: Surface(tex).also { previewSurface = it }
            cameras.open(groups, surface, previewSize)
            if (cameras.openedStreams.isEmpty()) {
                // Una cámara oculta puede aparecer al probar su número pero negarse a abrir:
                // volver a la principal para no quedarse sin vista previa.
                val fallback = runCatching { cameraManager.cameraIdList.firstOrNull { id ->
                    inventory[id]?.facing == CameraMetadata.LENS_FACING_BACK } }.getOrNull()
                if (fallback != null && groups.none { it.deviceId == fallback && it.physicalStreams.isEmpty() }) {
                    log("No se pudo abrir ${groups.joinToString { "#" + it.deviceId }}: se usa la principal #$fallback")
                    _state.update { it.copy(rigWarning = "El móvil no deja abrir esa cámara; se muestra la principal #$fallback.") }
                    val g = CameraGroup(fallback, emptyList(), primary = true, concurrent = false)
                    tex.setDefaultBufferSize(cameras.previewSizeFor(fallback).width, cameras.previewSizeFor(fallback).height)
                    cameras.open(listOf(g), surface, cameras.previewSizeFor(fallback))
                }
            }
            // ¿Se ve la cámara pedida? (el móvil puede rechazar el flujo físico de la macro).
            val wanted = first.previewPhysicalId ?: first.deviceId
            val shown = cameras.previewCameraId
            if (shown != null && shown != wanted && rigPlan.previewId == wanted) {
                val msg = "El móvil no deja mostrar ${roleName(wanted)} en la vista previa; se ve ${roleName(shown)}."
                log(msg)
                _state.update { it.copy(rigWarning = msg) }
            }
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

    override fun setUseConcurrent(v: Boolean) {
        _state.update { it.copy(useConcurrent = v) }
        if (!_state.value.scanning) reopenCameras()
    }

    override fun setAutoTrigger(v: Boolean) = _state.update { it.copy(autoTrigger = v) }
    override fun setHdr(v: Boolean) = _state.update { it.copy(hdr = v) }
    override fun setTorch(v: Boolean) {
        _state.update { it.copy(torch = v) }
        cameras.torch = v; cameras.refreshRepeating()
    }
    /** El bloqueo se aplica al iniciar el escaneo (antes, el AF/AE se ajustan libremente). */
    override fun setLock3A(v: Boolean) = _state.update { it.copy(lock3A = v) }
    override fun setRotateCameras(v: Boolean) = _state.update { it.copy(rotateCameras = v) }
    override fun setDiscardBlurry(v: Boolean) = _state.update { it.copy(discardBlurry = v) }
    override fun setJpegQuality(q: Int) {
        _state.update { it.copy(jpegQuality = q) }
        cameras.jpegQuality = q
    }
    override fun setPartDepth(cm: Int) = _state.update { it.copy(partDepthCm = cm.coerceIn(1, 30)) }

    /**
     * Cambia de pasada (lejos ↔ cerca): reabre las cámaras de esa pasada, así la vista previa
     * pasa a la principal (lejos) o a la macro (cerca). Al reabrir, AE/AF convergen de nuevo y
     * sólo después se bloquean (ver MultiCameraController.settled): cada pasada queda con su
     * propio enfoque fijo y, por tanto, sus propios intrínsecos.
     */
    override fun setPass(p: ScanPass) {
        if (p == activePass || sequenceRunning) return
        activePass = p
        selector.minAngleDeg = p.minAngleDeg
        selector.reset()
        _state.update {
            it.copy(pass = p, coverage = coverage.snapshot(),
                coveragePct = coverage.coverage(), hints = coverage.hints().take(3))
        }
        val plan = planCurrentPass()
        sequenceRunning = true
        viewModelScope.launch {
            try {
                log("Pasada: ${p.label}. ${_state.value.rigText}")
                openRig(plan.groups)
                if (session != null) {
                    delay(1200) // convergencia antes de permitir disparos
                    Optics.distanceM(cameras.primaryFocusDiopters())?.let { log("Enfocado a %.0f cm".format(it * 100)) }
                }
            } finally { sequenceRunning = false }
        }
    }

    /** Profundidad de campo (dioptrías) de una cámara con la resolución abierta. */
    private fun dofDiopters(cameraId: String): Double {
        val info = inventory[cameraId] ?: return 0.3
        val f = info.focalLengthsMm.firstOrNull()?.toDouble() ?: return 0.3
        val n = info.apertures.firstOrNull()?.toDouble() ?: 1.8
        val sw = info.sensorSizeMm?.first?.toDouble() ?: return 0.3
        val w = cameras.openedStreams.firstOrNull { it.cameraId == cameraId }?.size?.width
            ?: info.jpegSizes.firstOrNull()?.width ?: 4000
        return Optics.dofDiopters(f, n, Optics.cocMm(sw, w))
    }

    private fun focusPlanFor(p: ScanPass): MultiCameraController.FocusPlan? {
        if (!p.focusStack) return null
        val depthM = _state.value.partDepthCm / 100.0
        return MultiCameraController.FocusPlan { camId, minFocus, current ->
            Optics.focusStack(current.toDouble(), depthM, dofDiopters(camId), minFocus.toDouble())
        }
    }

    /** "a 18 cm · 0,06 mm/px · nítido 4 mm · 5 planos" para la cámara principal. */
    private fun opticsText(): String {
        val stream = cameras.openedStreams.firstOrNull() ?: return ""
        val info = inventory[stream.cameraId] ?: inventory[stream.viaGroup] ?: return ""
        val f = info.focalLengthsMm.firstOrNull()?.toDouble() ?: return ""
        val sw = info.sensorSizeMm?.first?.toDouble() ?: return ""
        if ((info.minFocusDiopters ?: 0f) <= 0f) {
            // Foco fijo (macro y ultra gran angular del Honor): no hay distancia que leer.
            // La macro sólo enfoca a unos ~4 cm: hay que mover el móvil hasta verla nítida.
            return if (info.id == roles.macro) {
                val gsd = Optics.gsdMm(MACRO_FOCUS_M, Optics.fxPixels(f, sw, stream.size.width))
                "macro foco fijo · a ~%.0f cm · ≈%.3f mm/px · acércate hasta verla nítida".format(MACRO_FOCUS_M * 100, gsd)
            } else "foco fijo"
        }
        val dist = Optics.distanceM(cameras.primaryFocusDiopters()) ?: return "enfoque: lejos/infinito"
        val gsd = Optics.gsdMm(dist, Optics.fxPixels(f, sw, stream.size.width))
        val dofD = dofDiopters(info.id)
        val dofMm = dofD * dist * dist * 1000
        val approx = if (info.focusCalibration == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED) "~" else ""
        val planes = focusPlanFor(activePass)?.let {
            Optics.focusStack(1 / dist, _state.value.partDepthCm / 100.0, dofD, (info.minFocusDiopters ?: 10f).toDouble()).size
        }
        return "a $approx%.0f cm · %.3f mm/px · nítido %.0f mm".format(dist * 100, gsd, dofMm) +
            (planes?.let { " · apilado $it planos" } ?: "")
    }

    // ------------------------------------------------------------------ escaneo

    override fun startScan() {
        if (session != null || stopping != null) return
        updateClockOffsets()
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
                put("part_depth_cm", _state.value.partDepthCm)
                put("jpeg_quality", _state.value.jpegQuality)
                put("discard_blurry", _state.value.discardBlurry)
                put("max_motion_blur_px", MAX_MOTION_BLUR_PX)
                put("min_angle_deg", selector.minAngleDeg)
                put("min_translation_m", selector.minTranslationM)
                put("max_gyro_rad_s", selector.maxGyroRadS)
                put("imu_sensors", org.json.JSONArray(imu.availableSensors().map { "${it.name} (${it.vendor}) ${it.minDelay}us" }))
            },
        )
        ins.reset(); selector.reset(); trigger = 0
        coverageMaps.values.forEach { it.reset() }; blurJudge.reset()
        stackTriggers.clear(); coveredShots.clear(); exposures.clear()
        synchronized(recentTexture) { recentTexture.clear() }
        analyzedCount = 0; blurryCount = 0; skippedAnalysis = 0
        discardedCount = 0; discardedBytes = 0; storedBytes.set(0)
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

    override fun stopScan() {
        val s = session ?: return
        // La sesión pasa a "cerrándose": las fotos de la última ráfaga aún llegan y se guardan.
        stopping = s
        session = null
        imu.stopLogging()
        gps.stopLogging()
        cameras.lock3A = false; cameras.refreshRepeating()
        _state.update { it.copy(scanning = false, busy = true) }
        viewModelScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { finish(s) }
    }

    /** Espera a lo que queda en vuelo y cierra. NonCancellable: salir de la app no corta el cierre. */
    private suspend fun finish(s: ScanSession) {
        delay(800) // últimos JPEG del HAL
        val deadline = SystemClock.uptimeMillis() + 8000
        while ((pendingWrites.get() > 0 || pendingAnalysis.get() > 0) && SystemClock.uptimeMillis() < deadline) delay(50)
        runCatching { s.writeQualitySummary(qualitySummary()) }
        s.close()
        stopping = null
        val zip = runCatching { s.zip() }.getOrNull()
        log("Escaneo guardado: ${s.imageCount.get()} imágenes en ${s.dir.absolutePath}")
        _state.update { it.copy(busy = false, lastZip = zip) }
    }

    /** Barrido de foco: mantener el móvil quieto (mejor apoyado) ~1-2 s. */
    override fun focusSweep() {
        val s = session ?: return
        if (sequenceRunning) return
        sequenceRunning = true
        viewModelScope.launch {
            try {
                trigger++
                stackTriggers += trigger
                val before = s.imageCount.get()
                val expected = cameras.captureFocusSweep()
                log("Barrido de foco: $expected fotos" + if (!ins.isStationary()) " (¡el móvil se movía!)" else "")
                val deadline = SystemClock.uptimeMillis() + 1500 + 400L * expected
                while (s.imageCount.get() - before < expected && SystemClock.uptimeMillis() < deadline) delay(20)
            } finally {
                sequenceRunning = false
                cameras.refreshRepeating() // devuelve el AF a continuo
            }
        }
    }

    override fun captureNow() {
        if (session == null) return
        fire(ins.latest())
    }

    private fun fire(st: InsState?) {
        if (sequenceRunning) return
        st?.let { selector.markTriggered(it) }
        val ui = _state.value
        val pass = activePass
        // HDR sólo si el móvil está quieto: la ráfaga dura 3 exposiciones y no debe haber movimiento.
        val bracket = if (ui.hdr && pass.hdr && (st?.stationary == true || imu.gyroRate < 0.05)) HDR_EV else emptyList()
        val plan = focusPlanFor(pass)
        sequenceRunning = true
        viewModelScope.launch {
            try {
                val rig = rigPlan
                shoot(bracket, plan)
                if (ui.rotateCameras && rig.separate.isNotEmpty()) {
                    // La compañera no se puede abrir a la vez: se dispara justo después, desde
                    // la misma pose (quieto), y se vuelve a la vista de la pasada.
                    for (g in rig.separate) {
                        openRig(listOf(g))
                        delay(700) // convergencia de AE/AF
                        shoot(bracket, plan)
                    }
                    openRig(rig.groups)
                }
            } finally {
                sequenceRunning = false
                ins.latest()?.let { selector.markTriggered(it) }
            }
        }
    }

    private suspend fun shoot(bracket: List<Int>, plan: MultiCameraController.FocusPlan?) {
        val s = session ?: return
        trigger++
        if (plan != null) stackTriggers += trigger
        val before = s.imageCount.get()
        val expected = cameras.capture(bracket, plan)
        _state.update { it.copy(triggers = trigger) }
        // Espera a que lleguen los JPEG antes de otro disparo; si no, una ráfaga larga
        // (HDR, apilado) quedaría etiquetada con el disparo siguiente.
        val deadline = SystemClock.uptimeMillis() + 1500 + 400L * expected
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
                        opticsText = runCatching { opticsText() }.getOrDefault(""),
                    )
                }
            }
            delay(10)
        }
    }

    // ------------------------------------------------------------------ callbacks de cámara

    override fun onImage(cameraId: String, timestampNs: Long, width: Int, height: Int, jpeg: ByteArray) {
        val s = session ?: stopping ?: return
        val t = timestampNs + (clockOffset[cameraId] ?: 0L)
        val trig = trigger
        val pass = activePass
        storedBytes.addAndGet(jpeg.size.toLong())
        pendingWrites.incrementAndGet()
        // El hilo de la cámara sólo copia bytes; escribir 5 MB aquí dejaría al HAL sin buffers
        // en las ráfagas (apilados × cámaras físicas).
        writer.execute {
            try {
                val pose = ins.stateAt(t)
                runCatching { s.addImage(trig, pass.name, cameraId, t, width, height, jpeg, pose) }
                    .onFailure { log("Error guardando imagen: ${it.message}") }
                val blurPx = motionBlurPx(cameraId, t, width)
                // Análisis en segundo plano; si se acumula (ráfagas), se salta para no agotar memoria.
                if (pendingAnalysis.get() >= 6) { skippedAnalysis++; return@execute }
                pendingAnalysis.incrementAndGet()
                analysis.execute {
                    try { analyze(s, trig, pass, cameraId, t, width, jpeg, pose, blurPx) } catch (e: Exception) {
                        log("Análisis de calidad: ${e.message}")
                    } finally { pendingAnalysis.decrementAndGet() }
                }
            } finally { pendingWrites.decrementAndGet() }
        }
    }

    private fun analyze(s: ScanSession, trig: Int, pass: ScanPass, cameraId: String, t: Long, width: Int,
                        jpeg: ByteArray, pose: InsState?, blurPx: Double) {
        val q = JpegAnalyzer.analyze(jpeg, width) ?: return
        // Dos pruebas: física (giro durante la exposición) y de imagen (nitidez relativa).
        // En un apilado los planos están desenfocados a propósito: sólo cuenta la física.
        val blurry = blurPx > MAX_MOTION_BLUR_PX ||
            (trig !in stackTriggers && blurJudge.isBlurry(cameraId, q.sharpness))
        var deleted = false
        if (blurry && _state.value.discardBlurry) {
            val freed = s.deleteImage(cameraId, t)
            if (freed > 0) { deleted = true; discardedCount++; discardedBytes += freed; storedBytes.addAndGet(-freed) }
        }
        val cov = coverageMaps.getValue(pass)
        coverageMaps.values.firstNotNullOfOrNull { it.origin() }?.let { cov.adoptOrigin(it) }
        val backFacing = inventory[cameraId]?.facing != CameraMetadata.LENS_FACING_FRONT
        var azEl: Pair<Double, Double>? = null
        var bin: Pair<Int, Int>? = null
        if (pose != null && backFacing) {
            val dir = CoverageMap.viewDirection(pose.orientation, backFacing = true)
            azEl = CoverageMap.azEl(dir)
            // Sólo las fotos nítidas y bien expuestas cuentan como cobertura.
            if (!blurry && q.clippedHigh < 0.25 && q.clippedLow < 0.4 && coveredShots.add("$trig/$cameraId")) {
                bin = cov.add(dir)
            }
        }
        s.addQuality(pass.name, cameraId, t, q, blurry, blurPx, deleted, azEl, bin)
        analyzedCount++
        if (blurry) blurryCount++
        val tex = synchronized(recentTexture) {
            recentTexture.addLast(q.texture); while (recentTexture.size > 10) recentTexture.removeFirst()
            recentTexture.sorted()[recentTexture.size / 2]
        }
        val (ready, text) = readiness()
        val shown = coverage // la pasada visible puede haber cambiado
        _state.update {
            it.copy(
                coverage = shown.snapshot(), coveragePct = shown.coverage(), analyzed = analyzedCount,
                blurry = blurryCount, textureRecent = tex, hints = shown.hints().take(3),
                ready = ready, readinessText = text,
                passCoverage = coverageMaps.mapValues { (_, m) -> m.coverage() },
                discarded = discardedCount, savedMb = discardedBytes / 1e6, storedMb = storedBytes.get() / 1e6,
            )
        }
    }

    /**
     * Desenfoque por movimiento en píxeles = ángulo girado durante la exposición × focal (px).
     * Usa la orientación de la INS (precisa a escala de milisegundos) en [t, t + exposición].
     */
    private fun motionBlurPx(cameraId: String, t: Long, width: Int): Double {
        // Exposición real de esa foto (en HDR, +2 EV es 4× más larga que la previsualización).
        val exp = exposures.remove("$cameraId/$t") ?: cameras.exposureNsFor(cameraId)
        if (exp <= 0) return 0.0
        val a = ins.stateAt(t) ?: return 0.0
        val b = ins.stateAt(t + exp) ?: return 0.0
        val info = inventory[cameraId] ?: return 0.0
        val f = info.focalLengthsMm.firstOrNull()?.toDouble() ?: return 0.0
        val sw = info.sensorSizeMm?.first?.toDouble() ?: return 0.0
        return a.orientation.angleTo(b.orientation) * Optics.fxPixels(f, sw, width)
    }

    /** Criterios mínimos antes de gastar cómputo en la nube. */
    private fun readiness(): Pair<Boolean, String> {
        // La pasada lejana es obligatoria (une todo); la cercana añade detalle y es opcional.
        val cov = coverageMaps.getValue(ScanPass.FAR).coverage()
        val blurRatio = if (analyzedCount == 0) 0.0 else blurryCount.toDouble() / analyzedCount
        val problems = buildList {
            if (cov < MIN_COVERAGE) add("cobertura ${(cov * 100).toInt()} % (mín. ${(MIN_COVERAGE * 100).toInt()} %)")
            if (analyzedCount < MIN_IMAGES) add("$analyzedCount fotos (mín. $MIN_IMAGES)")
            if (blurRatio > MAX_BLUR_RATIO) add("${(blurRatio * 100).toInt()} % movidas (máx. ${(MAX_BLUR_RATIO * 100).toInt()} %)")
        }
        val close = coverageMaps.getValue(ScanPass.CLOSE).coverage()
        val detail = if (close > 0) " · detalle cerca ${(close * 100).toInt()} %" else " · sin pasada de detalle"
        return if (problems.isEmpty()) true to "✓ Listo para enviar$detail"
        else false to "Falta (pasada lejos): " + problems.joinToString(", ")
    }

    private fun qualitySummary() = JSONObject().apply {
        val far = coverageMaps.getValue(ScanPass.FAR)
        put("coverage", far.coverage())
        put("coverage_2plus", far.coverage(2))
        put("rings", org.json.JSONArray(far.rings.indices.map { far.ringCoverage(it) }))
        put("passes", JSONObject().apply {
            for ((p, m) in coverageMaps) put(p.name, JSONObject().apply {
                put("coverage", m.coverage())
                put("rings", org.json.JSONArray(m.rings.indices.map { m.ringCoverage(it) }))
                put("hints", org.json.JSONArray(m.hints()))
            })
        })
        put("focus_stack_triggers", stackTriggers.size)
        put("discarded_blurry", discardedCount)
        put("discarded_mb", discardedBytes / 1e6)
        put("images_analyzed", analyzedCount)
        put("images_skipped_analysis", skippedAnalysis)
        put("blurry", blurryCount)
        put("texture_recent_median", _state.value.textureRecent)
        put("ready", readiness().first)
        put("hints", org.json.JSONArray(far.hints()))
    }

    override fun onCaptureMetadata(cameraId: String, timestampNs: Long, meta: JSONObject) {
        val t = timestampNs + (clockOffset[cameraId] ?: 0L)
        meta.optLong("exposure_ns", 0L).takeIf { it > 0 }?.let {
            if (exposures.size > 500) exposures.clear() // fotos borradas antes de analizarse
            exposures["$cameraId/$t"] = it
        }
        (session ?: stopping)?.addCaptureMeta(trigger, cameraId, t, meta)
    }

    override fun onLog(msg: String) = log(msg)

    private fun log(msg: String) {
        android.util.Log.i("TismaScanner", msg)
        _state.update { it.copy(log = (it.log + msg).takeLast(8)) }
    }

    override fun onCleared() {
        // viewModelScope ya está cancelado aquí: cerrar en síncrono para no perder datos.
        (session ?: stopping)?.let { s ->
            session = null; stopping = null
            imu.stopLogging(); gps.stopLogging()
            runCatching { s.writeQualitySummary(qualitySummary()) }
            s.close()
        }
        loop?.cancel()
        imu.stop()
        gps.stop()
        cameras.release()
        analysis.shutdown()
        writer.shutdown()
    }

    companion object {
        val HDR_EV = listOf(-2, 0, 2)
        const val MIN_COVERAGE = 0.75
        const val MIN_IMAGES = 40
        const val MAX_BLUR_RATIO = 0.2
        /** Por debajo de esto la pieza apenas tiene textura emparejable. */
        const val LOW_TEXTURE = 0.3
        /** Más de ~1,5 px de arrastre ya borra la microtextura que usa SIFT. */
        const val MAX_MOTION_BLUR_PX = 1.5
        /** Distancia típica de enfoque de las macro de foco fijo de 2 MP. */
        const val MACRO_FOCUS_M = 0.04
        const val IMU_TEST_SECONDS = 60
    }
}
