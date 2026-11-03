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
    /** Disparo automático por la INS. Apagado: las fotos las saca el usuario (la vista previa no se corta). */
    val autoTrigger: Boolean = false,
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
    /** Usar la macro en la pasada Cerca (opcional; por defecto Cerca usa la principal). */
    val useMacro: Boolean = false,
    /** Medir distancia con el enfoque: barrido automático cada pocos disparos (por defecto sí). */
    val focusDepth: Boolean = true,
    val focusSweeps: Int = 0,
    // --- Nube (Tisma Server) ---
    val cloudUrl: String = ScanViewModel.DEFAULT_CLOUD_URL,
    val cloudKey: String = "",
    /** Texto de estado del envío/proceso en la nube ("" = nada en curso). */
    val cloudStatus: String = "",
    /** 0..1 durante la subida; -1 sin barra. */
    val cloudProgress: Float = -1f,
    val cloudBusy: Boolean = false,
    /** Pieza descargada del servidor (STL en mm). */
    val cloudStl: File? = null,
    // --- Velocímetro de cámara (flujo óptico + enfoque) ---
    val cameraVelocity: Boolean = true,
    /** "12 medidas/s · 0,06 m/s" o "" si no mide. */
    val flowText: String = "",
    // --- Calibración base a tiempo ---
    val driftMinutes: Int = 5,
    val driftRunning: Boolean = false,
    /** "2:13 / 5:00 · deriva 0,4 cm · ±1,2 cm" mientras corre. */
    val driftProgress: String = "",
    val driftReport: List<String> = emptyList(),
    /** Registros guardados (más recientes primero). */
    val driftFiles: List<File> = emptyList(),
    /** Registro de diagnóstico de esta ejecución (vista previa, cámara, bloqueos). */
    val diagFile: File? = null,
    /** Calibración en uso: "02/11 18:40 · giro 0,004 °/s · acel 0,045 m/s²" o "". */
    val calibrationText: String = "",
    // --- Calibración automática (en segundo plano) ---
    /** Aviso breve "✓ Precisión mejorada…" que aparece de tanto en tanto ("" = oculto). */
    val precisionBadge: String = "",
    /** Estado de la calibración automática para Registro. */
    val autoCalText: String = "",
    val accelCalibrated: Boolean = false,
    // --- Precisión de la INS ---
    /** Segundos restantes de la prueba de quietud (0 = no está en marcha). */
    val imuTestRemaining: Int = 0,
    val imuReport: List<String> = emptyList(),
    /** Estado de cada sensor inercial (registrado, muestras, Hz). */
    val sensorStatus: List<String> = emptyList(),
    // --- Estabilización por software ---
    val stabilizePreview: Boolean = true,
    /** Ráfaga anti-movimiento: N fotos con exposición 1/N que el servidor alinea y promedia. */
    val antiShakeBurst: Boolean = false,
    val gpsText: String = "GPS: sin fix",
    val posStdM: Double = 0.0,
    val scanning: Boolean = false,
    /** Hay una toma en curso (ráfaga o cámaras por turnos): la vista previa puede pararse un instante. */
    val capturing: Boolean = false,
    /** Esperando a que el móvil se quede quieto para disparar. */
    val waitingSteady: Boolean = false,
    /** Aviso de la última foto a mano (p.ej. salió movida). */
    val shotWarning: String = "",
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
    /** Último fix del chip GPS (el "fused" sólo se usa si el GPS se calla: no trae Doppler fiable). */
    @Volatile private var lastGpsChipNs = 0L

    private val gps = GpsRecorder(app) { tNs, loc ->
        val fromChip = loc.provider == android.location.LocationManager.GPS_PROVIDER
        if (fromChip) lastGpsChipNs = tNs
        else if (tNs - lastGpsChipNs < 3_000_000_000L) return@GpsRecorder   // ya hay GPS: ignorar el fused
        if (ins.geo == null) {
            // Campo magnético del lugar (modelo magnético mundial de Android): declinación para
            // pasar a norte verdadero, e intensidad/inclinación para reconocer el campo
            // distorsionado de interiores (entonces el rumbo sale sólo del giróscopo).
            val field = android.hardware.GeomagneticField(
                loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis())
            ins.declinationDeg = field.declination.toDouble()
            ins.setExpectedMagneticField(field.fieldStrength / 1000.0, field.inclination.toDouble())
            log("Campo magnético esperado: %.1f µT, inclinación %.1f°, declinación %.1f°".format(
                field.fieldStrength / 1000.0, field.inclination, field.declination))
        }
        ins.onGnss(
            loc.latitude, loc.longitude, loc.altitude, loc.accuracy.toDouble(),
            if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            speedAccMps = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.toDouble() else null,
        )
        _state.update {
            it.copy(gpsText = "GPS: %.6f, %.6f ±%.1f m".format(loc.latitude, loc.longitude, loc.accuracy) +
                (if (loc.hasSpeed()) " · %.2f m/s".format(loc.speed) else "") +
                (if (loc.hasSpeedAccuracy()) " ±%.2f".format(loc.speedAccuracyMetersPerSecond) else "") +
                if (fromChip) "" else " (fused)")
        }
    }
    private val coverageMaps = ScanPass.entries.associateWith { CoverageMap() }
    @Volatile private var activePass = ScanPass.FAR
    private val coverage get() = coverageMaps.getValue(activePass)
    /** Disparos que son apilados de enfoque: sus planos desenfocados no son "fotos movidas". */
    private val stackTriggers = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** Disparos hechos a mano: si salen movidas se avisa, pero no se borran. */
    private val manualTriggers = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** Disparos de varias fotos (HDR, ráfaga, barrido): se fusionan en el servidor, no se borran sueltas. */
    private val multiTriggers = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var warnJob: kotlinx.coroutines.Job? = null
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

    /** Disparos desde el último barrido de enfoque automático. */
    @Volatile private var shotsSinceSweep = 0
    @Volatile private var focusSweepCount = 0

    /** Evita disparos solapados (ráfagas HDR o secuencias de rotación en curso). */
    @Volatile private var sequenceRunning = false
        set(v) {
            field = v
            // Al terminar una toma la vista vuelve: no contarla como vista previa parada.
            if (!v) lastPreviewFrameMs = SystemClock.uptimeMillis()
            _state.update { it.copy(capturing = v) }
        }
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
    private val stabilizer = com.tisma.scanner.camera.PreviewStabilizer()
    private val prefs = app.getSharedPreferences("tisma", Context.MODE_PRIVATE)
    private var cloudJob: Job? = null
    private var driftJob: Job? = null
    private val autoCal = com.tisma.scanner.ins.AccelCalibration()
    private var badgeJob: Job? = null
    /** Calibración nueva calculada durante un escaneo: se aplica al terminarlo. */
    @Volatile private var pendingCal: com.tisma.scanner.ins.AccelCalibration.Result? = null
    // Velocímetro óptico: fotograma anterior y estadísticas.
    private var prevFlow: Triple<Long, FloatArray, String>? = null
    private val flowAccepted = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastFlowSpeed = 0.0
    private val sensorOrientationCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var trigger = 0
    private var loop: Job? = null
    private var previewTexture: SurfaceTexture? = null
    /** Corrección de base de tiempos para cámaras cuyo timestamp no es REALTIME. */
    private val clockOffset = HashMap<String, Long>()

    init {
        runCatching { com.tisma.scanner.diag.DiagLog.start(File(app.getExternalFilesDir(null), "scans/diagnostico")) }
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
        _state.update { it.copy(cloudUrl = prefs.getString("cloud_url", DEFAULT_CLOUD_URL) ?: DEFAULT_CLOUD_URL,
            cloudKey = prefs.getString("cloud_key", "") ?: "") }
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
        _state.update { it.copy(diagFile = com.tisma.scanner.diag.DiagLog.file) }
        loadAccelCalibration()
        loadCalibration()
        refreshDriftFiles()
        // Calibración automática: escucha el acelerómetro siempre; cada reposo en una postura
        // nueva puede mejorarla, sin pedir nada al usuario.
        imu.accelTap = { t, a -> if (autoCal.feed(t, a)) viewModelScope.launch(Dispatchers.Default) { onNewPose() } }
        log("Conjuntos concurrentes: " + concurrentSets.joinToString { s -> s.joinToString("+", "{", "}") })
        cameras.lockFocus = prefs.getBoolean("lock_focus_ok", true)
        com.tisma.scanner.diag.DiagLog.extra = {
            "escaneo=${if (session != null) "sí" else "no"} · capturando=${if (sequenceRunning) "sí" else "no"} · " +
                "escrituras=${pendingWrites.get()} · análisis=${pendingAnalysis.get()} · giro %.2f rad/s".format(imu.gyroRate)
        }
        com.tisma.scanner.diag.DiagLog.event("app", "foco fijo en escaneo: ${cameras.lockFocus} · vista previa estabilizada: ${_state.value.stabilizePreview}")
        loop = viewModelScope.launch(Dispatchers.Default) { insLoop() }
    }

    // ------------------------------------------------------------------ vigilancia de la vista previa

    /** Último fotograma que llegó a la pantalla (lo marca cada actualización de la textura). */
    @Volatile private var lastPreviewFrameMs = 0L
    private var lastRecoveryMs = 0L
    private var recoveryStage = 0

    /**
     * Si durante el escaneo la vista previa deja de recibir fotogramas (pasaba en el Honor al
     * pulsar Escanear: el bloqueo de foco/exposición paraba la cámara), se recupera sola por pasos:
     * 1) sin foco manual, 2) sin bloqueo 3A, 3) reabrir la cámara. Lo que funcione se recuerda.
     */
    private fun previewWatchdog(now: Long) {
        if (session == null || sequenceRunning || _state.value.busy || previewTexture == null) return
        if (cameras.openedStreams.isEmpty() || lastPreviewFrameMs == 0L) return
        if (now - lastPreviewFrameMs < PREVIEW_STALL_MS || now - lastRecoveryMs < PREVIEW_STALL_MS) return
        lastRecoveryMs = now
        recoveryStage++
        when {
            recoveryStage == 1 && cameras.lock3A && cameras.lockFocus -> {
                cameras.lockFocus = false
                prefs.edit().putBoolean("lock_focus_ok", false).apply()
                log("Vista previa parada con el foco bloqueado: se bloquea sólo exposición y balance")
                cameras.refreshRepeating()
            }
            recoveryStage <= 2 && cameras.lock3A -> {
                recoveryStage = 2
                cameras.lock3A = false
                log("Vista previa parada con el bloqueo 3A: escaneo sin bloqueo (foco y exposición automáticos)")
                cameras.refreshRepeating()
            }
            else -> {
                log("Vista previa parada: se reabre la cámara")
                viewModelScope.launch { openRig(rigPlan.groups); lastPreviewFrameMs = SystemClock.uptimeMillis() }
            }
        }
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
        // Sin la opción de macro, Cerca usa la principal (con apilado de enfoque).
        val base = if (_state.value.useMacro) roles else roles.copy(macro = null)
        val choice = _state.value.previewChoice[p] ?: return base
        return when (p) {
            ScanPass.FAR -> base.copy(main = choice)
            ScanPass.CLOSE -> base.copy(macro = choice)
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
            // Sin macro es lo esperado que ambas pasadas usen la principal.
            plan.previewId == other.previewId && _state.value.useMacro ->
                "Lejos y Cerca usan la misma cámara (${roleName(plan.previewId)}): no se detectó " +
                    (if (activePass == ScanPass.CLOSE) "la macro" else "otra cámara") +
                    ". Elige una en la pestaña Cámaras o comparte el diagnóstico."
            else -> ""
        }
        _state.update { it.copy(rigText = text, rigWarning = warning) }
        if (warning.isNotEmpty()) log(warning)
        return plan
    }

    override fun setCloudUrl(v: String) {
        _state.update { it.copy(cloudUrl = v.trim()) }
        prefs.edit().putString("cloud_url", v.trim()).apply()
    }

    override fun setCloudKey(v: String) {
        _state.update { it.copy(cloudKey = v.trim()) }
        prefs.edit().putString("cloud_key", v.trim()).apply()
    }

    /**
     * Envía el último escaneo a Tisma Server, sigue el proceso y descarga la pieza.
     * La subida es reanudable: el id del trabajo se guarda por escaneo, así que si se corta o se
     * cierra la app, el siguiente envío continúa donde quedó en vez de empezar de cero.
     */
    override fun sendToCloud() {
        val zip = _state.value.lastZip ?: return
        if (cloudJob?.isActive == true) return
        val ui = _state.value
        cloudJob = viewModelScope.launch(Dispatchers.IO) {
            fun st(text: String, progress: Float = -1f, busy: Boolean = true) =
                _state.update { it.copy(cloudStatus = text, cloudProgress = progress, cloudBusy = busy) }
            try {
                val client = com.tisma.scanner.cloud.CloudClient(ui.cloudUrl, ui.cloudKey)
                st("Conectando con ${ui.cloudUrl}…")
                val jobKey = "job_" + zip.name + "_" + zip.length()
                var id = prefs.getString(jobKey, null)
                var chunk = 16 * 1024 * 1024
                val existing = id?.let { runCatching { client.status(it) }.getOrNull() }
                if (existing == null) {
                    val (newId, c) = client.create(zip.length(), zip.nameWithoutExtension)
                    id = newId; chunk = c
                    prefs.edit().putString(jobKey, id).apply()
                }
                val jobId = id!!
                if (existing == null || existing.optString("state") == "uploading") {
                    client.upload(jobId, zip, chunk, onProgress = { done, total ->
                        st("Subiendo %.0f / %.0f MB".format(done / 1e6, total / 1e6), done.toFloat() / total)
                    }, isCancelled = { !isActive })
                }
                // Seguimiento del proceso en el servidor.
                while (true) {
                    val s = client.status(jobId)
                    when (s.optString("state")) {
                        "queued" -> st("En cola en el servidor…")
                        "processing" -> st("Procesando: ${s.optString("stage", "…")}")
                        "done" -> break
                        "error" -> throw java.io.IOException(s.optString("error", "error en el servidor"))
                    }
                    delay(5000)
                }
                st("Descargando la pieza…")
                val dir = zip.parentFile!!
                val stl = File(dir, zip.nameWithoutExtension + "_pieza.stl")
                client.download(jobId, "pieza.stl", stl)
                val reportFile = File(dir, zip.nameWithoutExtension + "_informe.json")
                runCatching { client.download(jobId, "pieza_informe.json", reportFile) }
                val summary = runCatching {
                    val r = JSONObject(reportFile.readText())
                    val d = r.getJSONArray("dimensions_mm_xyz")
                    "%.2f × %.2f × %.2f mm".format(d.getDouble(0), d.getDouble(1), d.getDouble(2)) +
                        (if (r.optBoolean("watertight")) " · cerrada" else "")
                }.getOrDefault("")
                prefs.edit().remove(jobKey).apply()
                _state.update { it.copy(cloudStatus = "✓ Pieza lista $summary", cloudProgress = -1f, cloudBusy = false, cloudStl = stl) }
                log("Pieza descargada: ${stl.name} $summary")
            } catch (e: Exception) {
                st("✗ ${e.message ?: e.javaClass.simpleName}", busy = false)
                log("Nube: ${e.message}")
            }
        }
    }

    override fun setCameraVelocity(v: Boolean) {
        _state.update { it.copy(cameraVelocity = v, flowText = "") }
        prevFlow = null
    }

    /**
     * Velocímetro de cámara: flujo óptico entre fotogramas de la vista previa, menos el giro
     * (INS/giróscopo), por la distancia a la escena (enfoque) -> velocidad en m/s que corrige la
     * deriva del Kalman entre paradas. Corre en el hilo propio del flujo (camera-flow).
     */
    override fun onFlowFrame(cameraId: String, timestampNs: Long, w: Int, h: Int, gray: FloatArray) {
        if (!_state.value.cameraVelocity) return
        val t0 = System.nanoTime()
        try { flowFrame(cameraId, timestampNs, w, h, gray) }
        finally { com.tisma.scanner.diag.DiagLog.max("flujo_ms", (System.nanoTime() - t0) / 1_000_000) }
    }

    private fun flowFrame(cameraId: String, timestampNs: Long, w: Int, h: Int, gray: FloatArray) {
        val t = timestampNs + (clockOffset[cameraId] ?: 0L)
        val prev = prevFlow
        prevFlow = Triple(t, gray, cameraId)
        if (prev == null || prev.third != cameraId) return
        val dt = (t - prev.first) * 1e-9
        if (dt <= 0 || dt > 0.2) return
        val depth = flowDepthM(cameraId) ?: return
        val info = inventory[cameraId] ?: return
        val focal = info.focalLengthsMm.firstOrNull()?.toDouble() ?: return
        val sw = info.sensorSizeMm?.first?.toDouble() ?: return
        val s0 = ins.stateAt(prev.first) ?: return
        val s1 = ins.stateAt(t) ?: return
        val flow = com.tisma.scanner.ins.OpticalFlow.track(
            com.tisma.scanner.ins.OpticalFlow.Image(w, h, prev.second), com.tisma.scanner.ins.OpticalFlow.Image(w, h, gray))
        val orient = sensorOrientationCache.getOrPut(cameraId) { cameras.sensorOrientation(cameraId) }
        val m = com.tisma.scanner.ins.OpticalVelocity.estimate(
            flow, Optics.fxPixels(focal, sw, w), depth, dt, s0.orientation, s1.orientation, orient) ?: return
        ins.onVelocity(m.dirWorld1, m.v1, m.sigma)
        ins.onVelocity(m.dirWorld2, m.v2, m.sigma)
        flowAccepted.incrementAndGet()
        lastFlowSpeed = kotlin.math.sqrt(m.v1 * m.v1 + m.v2 * m.v2)
    }

    /** Distancia a la escena para el velocímetro: enfoque (autofoco) o la de la macro de foco fijo. */
    private fun flowDepthM(cameraId: String): Double? {
        val info = inventory[cameraId] ?: return null
        return if ((info.minFocusDiopters ?: 0f) > 0f) Optics.distanceM(cameras.primaryFocusDiopters())
        else if (cameraId == roles.macro) MACRO_FOCUS_M else null
    }

    override fun setFocusDepth(v: Boolean) = _state.update { it.copy(focusDepth = v) }

    override fun setUseMacro(v: Boolean) {
        _state.update { it.copy(useMacro = v) }
        // Sin macro no hay pasada "Cerca": volver a Lejos.
        if (!v && activePass == ScanPass.CLOSE && !sequenceRunning) { setPass(ScanPass.FAR); return }
        if (!sequenceRunning) {
            val plan = planCurrentPass()
            if (activePass == ScanPass.CLOSE) viewModelScope.launch { openRig(plan.groups) }
        }
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
            val (accRaw, gyr) = imu.stopStillCapture()
            val acc = calibrated(accRaw)
            _state.update { it.copy(imuTestRemaining = 0, imuReport = listOf("Analizando…")) }
            val report = kotlinx.coroutines.withContext(Dispatchers.Default) {
                com.tisma.scanner.ins.ImuStillTest.analyze(acc, gyr)
            }
            if (report == null) {
                val why = "No hay datos suficientes del IMU: acelerómetro ${acc.size}, giróscopo ${gyr.size} muestras en $IMU_TEST_SECONDS s"
                _state.update { it.copy(imuReport = listOf(why) + imu.status()) }
                log(why)
                return@launch
            }
            if (!report.moved) applyCalibration(report)
            _state.update { it.copy(imuReport = report.lines()) }
            report.lines().forEach { log(it) }
            runCatching {
                val dir = File(getApplication<Application>().getExternalFilesDir(null), "scans").apply { mkdirs() }
                File(dir, "imu_test_${System.currentTimeMillis()}.txt").writeText(report.lines().joinToString("\n"))
            }
        }
    }

    // ------------------------------------------------------------------ calibración base

    private fun calDir() = File(getApplication<Application>().getExternalFilesDir(null), "scans/calibraciones").apply { mkdirs() }

    private fun refreshDriftFiles() {
        val files = calDir().listFiles { f -> f.name.startsWith("deriva_") && (f.extension == "csv" || f.extension == "txt") }
            ?.sortedByDescending { it.lastModified() }?.take(10) ?: emptyList()
        _state.update { it.copy(driftFiles = files) }
    }

    /** Aplica los sesgos medidos, los guarda (se recuperan al abrir la app) y reinicia la INS. */
    private fun applyCalibration(report: com.tisma.scanner.ins.StillReport) {
        val gb = report.gyroBiasDegS * Math.toRadians(1.0)
        // Con la calibración de 6 caras, el sesgo del acelerómetro ya está corregido en todos los
        // ejes: lo que quede lo afina el Kalman (no se apila otra corrección encima).
        val ab = if (ins.accelCalibration != null) com.tisma.scanner.ins.Vec3.ZERO else report.accelBias
        ins.gyroBiasPrior = gb
        ins.accelBiasPrior = ab
        ins.reset()
        prefs.edit()
            .putString("cal", listOf(gb.x, gb.y, gb.z, ab.x, ab.y, ab.z).joinToString(","))
            .putLong("cal_time", System.currentTimeMillis()).apply()
        loadCalibration()
    }

    private fun loadCalibration() {
        val v = prefs.getString("cal", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() } ?: return
        if (v.size != 6) return
        ins.gyroBiasPrior = com.tisma.scanner.ins.Vec3(v[0], v[1], v[2])
        ins.accelBiasPrior = com.tisma.scanner.ins.Vec3(v[3], v[4], v[5])
        if (session == null) ins.reset()
        val date = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(prefs.getLong("cal_time", 0)))
        _state.update {
            it.copy(calibrationText = "$date · giróscopo %.3f °/s · acelerómetro %.3f m/s²".format(
                Math.toDegrees(ins.gyroBiasPrior.norm()), ins.accelBiasPrior.norm()))
        }
    }

    /** Muestras crudas del acelerómetro con la calibración de 6 caras aplicada. */
    private fun calibrated(raw: List<com.tisma.scanner.ins.ImuSample>): List<com.tisma.scanner.ins.ImuSample> {
        val c = ins.accelCalibration ?: return raw
        return raw.map { it.copy(v = c.apply(it.v)) }
    }

    // ------------------------------------------------------------------ calibración de 6 caras

    private fun loadAccelCalibration() {
        prefs.getString("acal_poses", null)?.split(";")?.mapNotNull { p ->
            p.split(",").mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 3 }?.let { com.tisma.scanner.ins.Vec3(it[0], it[1], it[2]) }
        }?.let { autoCal.restore(it) }
        val v = prefs.getString("acal", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }
        if (v != null && v.size >= 6) {
            val mode = if (prefs.getString("acal_mode", "BIAS_SCALE") == "BIAS") com.tisma.scanner.ins.AccelCalibration.Mode.BIAS
                else com.tisma.scanner.ins.AccelCalibration.Mode.BIAS_SCALE
            ins.accelCalibration = com.tisma.scanner.ins.AccelCalibration.Result(
                com.tisma.scanner.ins.Vec3(v[0], v[1], v[2]), com.tisma.scanner.ins.Vec3(v[3], v[4], v[5]),
                v.getOrElse(6) { 0.0 }, mode, autoCal.poseCount)
            _state.update { it.copy(accelCalibrated = true) }
        }
        updateAutoCalText()
    }

    private fun updateAutoCalText() {
        val c = ins.accelCalibration
        val n = autoCal.poseCount
        val text = when {
            c != null -> "✓ ${c.mode.label} · $n posturas · residuo %.1f mg".format(c.rmsResidual / 9.80665 * 1000) +
                if (c.mode == com.tisma.scanner.ins.AccelCalibration.Mode.BIAS) " (con el móvil boca abajo y cabeza abajo alguna vez, también la escala)" else ""
            n == 0 -> "Esperando: deja el móvil quieto unos segundos en distintas posturas (boca arriba, de pie, de costado)"
            else -> "$n postura(s) guardada(s): faltan direcciones distintas para calibrar"
        }
        _state.update { it.copy(autoCalText = text) }
    }

    /** Se guardó una postura nueva: recalcular y, si mejora, aplicar y avisar brevemente. */
    private fun onNewPose() {
        prefs.edit().putString("acal_poses", autoCal.poses().joinToString(";") { "${it.x},${it.y},${it.z}" }).apply()
        val r = autoCal.solve()
        val cur = ins.accelCalibration
        val better = r != null && r.plausible && (cur == null ||
            (r.mode == com.tisma.scanner.ins.AccelCalibration.Mode.BIAS_SCALE && cur.mode == com.tisma.scanner.ins.AccelCalibration.Mode.BIAS) ||
            r.mode == cur.mode)
        if (better && r != null) {
            if (session != null) pendingCal = r else applyAutoCalibration(r)
            showBadge("✓ Precisión mejorada · ${r.poses} posturas · ${r.mode.label}")
        } else {
            showBadge("◐ Postura nueva guardada (${autoCal.poseCount})")
        }
        updateAutoCalText()
    }

    private fun applyAutoCalibration(r: com.tisma.scanner.ins.AccelCalibration.Result) {
        ins.accelCalibration = r
        ins.accelBiasPrior = com.tisma.scanner.ins.Vec3.ZERO      // el resto lo afina el Kalman
        prefs.edit()
            .putString("acal", listOf(r.bias.x, r.bias.y, r.bias.z, r.scale.x, r.scale.y, r.scale.z, r.rmsResidual).joinToString(","))
            .putString("acal_mode", r.mode.name).apply()
        log("Calibración automática (${r.mode.label}, ${r.poses} posturas): sesgo %.1f mg · escala %+.2f %%".format(
            r.bias.norm() / 9.80665 * 1000, (listOf(r.scale.x, r.scale.y, r.scale.z).maxBy { kotlin.math.abs(it - 1) } - 1) * 100))
        _state.update { it.copy(accelCalibrated = true) }
        updateAutoCalText()
    }

    /** Muestra el aviso unos segundos y lo oculta. */
    private fun showBadge(text: String) {
        badgeJob?.cancel()
        badgeJob = viewModelScope.launch {
            _state.update { it.copy(precisionBadge = text) }
            delay(5000)
            _state.update { it.copy(precisionBadge = "") }
        }
    }

    override fun resetAutoCalibration() {
        autoCal.reset()
        ins.accelCalibration = null
        prefs.edit().remove("acal").remove("acal_poses").remove("acal_mode").apply()
        _state.update { it.copy(accelCalibrated = false) }
        if (session == null) ins.reset()
        updateAutoCalText()
    }

    override fun setDriftMinutes(m: Int) = _state.update { it.copy(driftMinutes = m.coerceIn(1, 30)) }

    /**
     * Calibración base a tiempo: la INS real (Kalman + paradas + GPS) corre N minutos desde el
     * origen y cada segundo se registra en un CSV su posición, velocidad, error estimado, rumbo,
     * sesgos y la posición GPS. Al final: resumen (deriva a 30 s, 1, 2, 5 min…), análisis del IMU
     * con todo el periodo (mejores sesgos que en 60 s) y, si el móvil estuvo quieto, se guarda
     * como calibración. Quieto = deriva de base; caminando = comparación con el GPS.
     */
    override fun runDriftTest() {
        if (driftJob?.isActive == true || session != null) return
        val minutes = _state.value.driftMinutes
        driftJob = viewModelScope.launch {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val csvFile = File(calDir(), "deriva_$stamp.csv")
            val rows = ArrayList<com.tisma.scanner.ins.DriftRow>()
            ins.reset()
            ins.filter.resetMagStats()
            imu.startStillCapture()
            val csv = csvFile.bufferedWriter()
            csv.write(com.tisma.scanner.ins.DriftRow.CSV_HEADER + "\n")
            _state.update { it.copy(driftRunning = true, driftReport = emptyList()) }
            log("Calibración base de $minutes min: deja el móvil quieto (o camina para compararlo con el GPS)")
            var gpsRef: com.tisma.scanner.ins.GeoReference? = null
            val start = SystemClock.elapsedRealtime()
            val total = minutes * 60_000L
            var finished = false
            try {
                while (SystemClock.elapsedRealtime() - start < total) {
                    delay(1000)
                    val st = ins.latest() ?: continue
                    val q = st.orientation
                    val yaw = Math.toDegrees(atan2(2 * (q.w * q.z + q.x * q.y), 1 - 2 * (q.y * q.y + q.z * q.z)))
                    val loc = gps.last?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 3_000_000_000L }
                    val gpsPos = loc?.let { l ->
                        val ref = gpsRef ?: com.tisma.scanner.ins.GeoReference(l.latitude, l.longitude, l.altitude).also { gpsRef = it }
                        val e = ref.toEnu(l.latitude, l.longitude, l.altitude)
                        ins.enuToIns(e.x, e.y, e.z)
                    }
                    val row = com.tisma.scanner.ins.DriftRow(
                        (SystemClock.elapsedRealtime() - start) / 1000.0, st.position, st.velocity,
                        kotlin.math.sqrt(ins.positionVariance), st.stationary, yaw,
                        ins.filter.gyroBias, ins.accelBias(), gpsPos, loc?.accuracy?.toDouble(),
                    )
                    // La referencia GPS empieza con la primera fila con GPS: la INS se compara desde ahí.
                    rows += row
                    csv.write(row.csv() + "\n")
                    val el = (SystemClock.elapsedRealtime() - start) / 1000
                    val d = st.position - rows.first().p
                    _state.update {
                        it.copy(driftProgress = "%d:%02d / %d:00 · deriva %.1f cm · ±%.1f cm %s".format(
                            el / 60, el % 60, minutes, kotlin.math.hypot(d.x, d.y) * 100, row.std * 100,
                            if (st.stationary) "· quieto" else "· en movimiento"))
                    }
                }
                finished = true
            } finally {
                runCatching { csv.close() }
                val (accRaw, gyr) = imu.stopStillCapture()
                val acc = calibrated(accRaw)
                if (!finished) {
                    _state.update { it.copy(driftRunning = false, driftProgress = "Cancelada (CSV parcial guardado)") }
                    refreshDriftFiles()
                } else {
                    _state.update { it.copy(driftProgress = "Analizando…") }
                    val report = kotlinx.coroutines.withContext(Dispatchers.Default + kotlinx.coroutines.NonCancellable) {
                        com.tisma.scanner.ins.ImuStillTest.analyze(acc, gyr, listOf(1.0, 2.0, 5.0, 10.0, 30.0, 60.0))
                    }
                    val acc = ins.filter.magAccepted; val rej = ins.filter.magRejected
                    val compass = if (acc + rej == 0L) "Brújula: sin lecturas" else
                        "Brújula: %d %% de lecturas válidas%s".format(100 * acc / (acc + rej),
                            if (ins.filter.expectedFieldUt != null) " (comparadas con el campo esperado del lugar)" else " (aún sin GPS: sin modelo de campo)") +
                            if (acc * 2 < acc + rej) " → el rumbo lo lleva el giróscopo" else ""
                    val lines = com.tisma.scanner.ins.DriftLog.lines(rows) + compass + (report?.lines() ?: emptyList())
                    val applied = report != null && !report.moved
                    if (applied) applyCalibration(report!!)
                    val summary = lines + (if (applied) "✓ Calibración guardada y aplicada" else "Calibración NO guardada (el móvil se movió)")
                    runCatching {
                        File(calDir(), "deriva_$stamp.txt").writeText(summary.joinToString("\n") + "\n")
                        File(calDir(), "deriva_$stamp.json").writeText(
                            com.tisma.scanner.ins.DriftLog.json(rows).put("calibration_applied", applied).toString(2))
                    }
                    summary.forEach { log(it) }
                    _state.update { it.copy(driftRunning = false, driftProgress = "Terminada", driftReport = summary) }
                    refreshDriftFiles()
                }
            }
        }
    }

    override fun cancelDriftTest() { driftJob?.cancel() }

    override fun setStabilizePreview(v: Boolean) = _state.update { it.copy(stabilizePreview = v) }
    override fun setAntiShakeBurst(v: Boolean) = _state.update { it.copy(antiShakeBurst = v) }

    /**
     * Transformación de estabilización para el fotograma de vista previa con ese timestamp.
     * null = sin estabilizar (desactivada o sin datos de la INS).
     */
    override fun stabilizePreview(frameTimestampNs: Long, viewW: Int, viewH: Int): com.tisma.scanner.camera.PreviewStabilizer.Transform? {
        lastPreviewFrameMs = SystemClock.uptimeMillis()
        com.tisma.scanner.diag.DiagLog.tick("pantalla")
        val t0 = System.nanoTime()
        try { return stabilizeInner(frameTimestampNs, viewW, viewH) }
        finally { com.tisma.scanner.diag.DiagLog.max("estab_us", (System.nanoTime() - t0) / 1000) }
    }

    private fun stabilizeInner(frameTimestampNs: Long, viewW: Int, viewH: Int): com.tisma.scanner.camera.PreviewStabilizer.Transform? {
        if (!_state.value.stabilizePreview || viewW <= 0 || viewH <= 0) return null
        val cam = cameras.previewCameraId ?: return null
        val info = inventory[cam] ?: return null
        if (info.facing != CameraMetadata.LENS_FACING_BACK) return null
        val t = frameTimestampNs + (clockOffset[cam] ?: 0L)
        val st = ins.stateAt(t) ?: return null
        // Fotograma fuera del historial de la INS (base de tiempos distinta): no estabilizar.
        val latest = ins.latest() ?: return null
        if (kotlin.math.abs(latest.timestampNs - t) > 1_000_000_000L) return null
        return stabilizer.update(st.orientation, viewW, viewH, info.hfovDeg ?: 70.0)
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
        appendLine("Sensores inerciales:")
        imu.status().forEach { appendLine("  $it") }
        imu.inventory().forEach { appendLine("  $it") }
        appendLine("INS: " + (ins.latest()?.let { "activa, %d estados".format(imu.sampleCount) } ?: "SIN ESTADO (no recibe acelerómetro+giróscopo)"))
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
            stabilizer.reset()
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
                put("use_macro", _state.value.useMacro)
                put("camera_velocity", _state.value.cameraVelocity)
                put("focus_depth_every", if (_state.value.focusDepth) AUTO_SWEEP_EVERY else 0)
                put("anti_shake_burst", if (_state.value.antiShakeBurst) ANTI_SHAKE_FRAMES else 0)
                put("max_motion_blur_px", MAX_MOTION_BLUR_PX)
                put("motion_blur_ref_width_px", REF_WIDTH_PX)
                put("min_angle_deg", selector.minAngleDeg)
                put("min_translation_m", selector.minTranslationM)
                put("max_gyro_rad_s", selector.maxGyroRadS)
                put("imu_sensors", org.json.JSONArray(imu.availableSensors().map { "${it.name} (${it.vendor}) ${it.minDelay}us" }))
            },
        )
        ins.reset(); selector.reset(); trigger = 0
        // El primer disparo es una foto normal (rápida); el barrido de enfoque va cada AUTO_SWEEP_EVERY.
        shotsSinceSweep = 0; focusSweepCount = 0
        manualTriggers.clear(); multiTriggers.clear()
        coverageMaps.values.forEach { it.reset() }; blurJudge.reset()
        stackTriggers.clear(); coveredShots.clear(); exposures.clear()
        synchronized(recentTexture) { recentTexture.clear() }
        analyzedCount = 0; blurryCount = 0; skippedAnalysis = 0
        discardedCount = 0; discardedBytes = 0; storedBytes.set(0)
        cameras.lock3A = _state.value.lock3A; cameras.refreshRepeating()
        recoveryStage = 0; lastPreviewFrameMs = SystemClock.uptimeMillis(); lastRecoveryMs = lastPreviewFrameMs
        imu.startLogging(s.imuWriter())
        gps.startLogging(java.io.File(s.dir, "gps.csv").bufferedWriter())
        session = s
        _state.update {
            it.copy(scanning = true, images = 0, triggers = 0, lastZip = null, analyzed = 0, blurry = 0,
                coverage = coverage.snapshot(), coveragePct = 0.0, hints = coverage.hints(), ready = false)
        }
        log("Escaneo iniciado: ${s.dir.name} · bloqueo 3A=${cameras.lock3A} · foco fijo=${cameras.lockFocus} · auto=${_state.value.autoTrigger}")
    }

    override fun stopScan() {
        val s = session ?: return
        com.tisma.scanner.diag.DiagLog.event("app", "detener escaneo")
        // La sesión pasa a "cerrándose": las fotos de la última ráfaga aún llegan y se guardan.
        stopping = s
        session = null
        imu.stopLogging()
        gps.stopLogging()
        cameras.lock3A = false; cameras.refreshRepeating()
        pendingCal?.let { applyAutoCalibration(it); pendingCal = null }
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

    private var steadyJob: kotlinx.coroutines.Job? = null

    /**
     * Foto a mano: si el móvil se está moviendo, espera un momento (máx. ~1,2 s) a que se quede
     * quieto, así la foto no sale movida y la toma dura lo mínimo.
     */
    override fun captureNow() {
        if (session == null || sequenceRunning || steadyJob?.isActive == true) return
        steadyJob = viewModelScope.launch {
            val deadline = SystemClock.uptimeMillis() + STEADY_WAIT_MS
            if (imu.gyroRateSmooth > STEADY_GYRO) {
                _state.update { it.copy(waitingSteady = true) }
                while (imu.gyroRateSmooth > STEADY_GYRO && SystemClock.uptimeMillis() < deadline) delay(20)
                _state.update { it.copy(waitingSteady = false) }
            }
            if (session != null) fire(ins.latest(), manual = true)
        }
    }

    private fun fire(st: InsState?, manual: Boolean = false) {
        if (sequenceRunning) return
        st?.let { selector.markTriggered(it) }
        val ui = _state.value
        val pass = activePass
        // HDR sólo si el móvil está quieto: la ráfaga dura 3 exposiciones y no debe haber movimiento.
        val bracket = if (ui.hdr && pass.hdr && (st?.stationary == true || imu.gyroRateSmooth < 0.05)) HDR_EV else emptyList()
        var plan = focusPlanFor(pass)
        var bracketUsed = bracket
        // Medición de distancia por enfoque: cada AUTO_SWEEP_EVERY disparos, si el móvil está
        // quieto, este disparo es un barrido de enfoque (mapa de profundidad en mm + escala).
        shotsSinceSweep++
        val still = st?.stationary == true || imu.gyroRateSmooth < 0.08
        if (ui.focusDepth && shotsSinceSweep >= AUTO_SWEEP_EVERY && still) {
            shotsSinceSweep = 0
            focusSweepCount++
            bracketUsed = emptyList()
            plan = MultiCameraController.FocusPlan { _, minFocus, current ->
                Optics.depthSweep(current.toDouble(), minFocus.toDouble())
            }
            _state.update { it.copy(focusSweeps = focusSweepCount) }
        }
        // Si toca HDR, gana el HDR (ya son 3 fotos); si no, ráfaga anti-movimiento si está activada.
        val burst = if (ui.antiShakeBurst && bracketUsed.isEmpty() && plan == null) ANTI_SHAKE_FRAMES else 0
        sequenceRunning = true
        viewModelScope.launch {
            try {
                val rig = rigPlan
                shoot(bracketUsed, plan, burst, manual)
                // Las cámaras por turnos cierran y reabren la vista previa: en disparo automático
                // sólo con el móvil quieto (si no, al moverlo la vista quedaba congelada).
                if (ui.rotateCameras && rig.separate.isNotEmpty() && (manual || st?.stationary == true)) {
                    // La compañera no se puede abrir a la vez: se dispara justo después, desde
                    // la misma pose (quieto), y se vuelve a la vista de la pasada.
                    for (g in rig.separate) {
                        openRig(listOf(g))
                        delay(700) // convergencia de AE/AF
                        shoot(bracketUsed, plan, burst, manual)
                    }
                    openRig(rig.groups)
                }
            } finally {
                sequenceRunning = false
                ins.latest()?.let { selector.markTriggered(it) }
            }
        }
    }

    private suspend fun shoot(bracket: List<Int>, plan: MultiCameraController.FocusPlan?, burst: Int = 0, manual: Boolean = false) {
        val s = session ?: return
        trigger++
        if (plan != null) stackTriggers += trigger
        if (manual) manualTriggers += trigger
        if (plan != null || bracket.isNotEmpty() || burst > 1) multiTriggers += trigger
        val t0 = SystemClock.uptimeMillis()
        val before = s.imageCount.get()
        val expected = cameras.capture(bracket, plan, burst)
        _state.update { it.copy(triggers = trigger) }
        // Espera a que lleguen los JPEG antes de otro disparo; si no, una ráfaga larga
        // (HDR, apilado) quedaría etiquetada con el disparo siguiente.
        val deadline = SystemClock.uptimeMillis() + 1500 + 400L * expected
        while (s.imageCount.get() - before < expected && SystemClock.uptimeMillis() < deadline) delay(20)
        com.tisma.scanner.diag.DiagLog.event("foto", "disparo $trigger listo: ${s.imageCount.get() - before}/$expected imágenes en " +
            "${SystemClock.uptimeMillis() - t0} ms" + (if (plan != null) " · barrido de foco" else if (bracket.isNotEmpty()) " · HDR" else if (burst > 1) " · ráfaga" else ""))
    }

    /** Bucle a ~100 Hz: refresca la UI, guarda trayectoria y decide disparos automáticos. */
    private suspend fun insLoop() {
        var lastUi = 0L
        var lastSensorUi = 0L
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
            previewWatchdog(now)
            if (now - lastSensorUi > 1000) {
                lastSensorUi = now
                val status = imu.status()
                val n = flowAccepted.getAndSet(0)
                val flowText = if (!_state.value.cameraVelocity) "" else if (n == 0) "velocímetro: sin medida (poca textura o sin enfoque)"
                    else "velocímetro: $n/s · %.2f m/s".format(lastFlowSpeed)
                _state.update { it.copy(sensorStatus = status, imuSamples = imu.sampleCount, flowText = flowText) }
            }
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
                        gyroRate = imu.gyroRateSmooth,
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
        com.tisma.scanner.diag.DiagLog.count("jpeg")
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
        // El arrastre se mide a la resolución con la que trabaja la reconstrucción (REF_WIDTH_PX):
        // a 4000 px el temblor normal de la mano ya da 3-8 px y se borraba todo.
        val blurRef = blurPx * minOf(1.0, REF_WIDTH_PX / width.toDouble())
        val sharpLow = trig !in stackTriggers && blurJudge.isBlurry(cameraId, q.sharpness)
        val blurry = blurRef > MAX_MOTION_BLUR_PX || sharpLow
        val manual = trig in manualTriggers
        var deleted = false
        com.tisma.scanner.diag.DiagLog.event("calidad", "#$cameraId disparo $trig: arrastre %.1f px (%.1f a %d px) · nitidez %.1f%s%s".format(
            blurPx, blurRef, REF_WIDTH_PX.toInt(), q.sharpness, if (sharpLow) " (baja)" else "", if (blurry) " → MOVIDA" else " → ok"))
        if (blurry && manual) warnShot("Foto movida (%.0f px): repítela más quieto o apoyado".format(blurRef))
        // Sólo se borran las automáticas sueltas: las tuyas y las de ráfagas/HDR se guardan marcadas.
        if (blurry && _state.value.discardBlurry && !manual && trig !in multiTriggers) {
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
    private fun warnShot(msg: String) {
        warnJob?.cancel()
        _state.update { it.copy(shotWarning = msg) }
        warnJob = viewModelScope.launch { delay(4000); _state.update { it.copy(shotWarning = "") } }
    }

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
        com.tisma.scanner.diag.DiagLog.event("app", msg)
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
        /** Arrastre máximo a REF_WIDTH_PX: más de ~2,5 px ya borra la microtextura que usa SIFT. */
        const val MAX_MOTION_BLUR_PX = 2.5
        /** Ancho de imagen con el que trabaja la reconstrucción (COLMAP: 3200 px). */
        const val REF_WIDTH_PX = 3200.0
        /** Distancia típica de enfoque de las macro de foco fijo de 2 MP. */
        const val MACRO_FOCUS_M = 0.04
        const val IMU_TEST_SECONDS = 60
        const val ANTI_SHAKE_FRAMES = 4
        /** Giro por debajo del cual se considera el móvil firme para una foto a mano (rad/s). */
        const val STEADY_GYRO = 0.25
        const val STEADY_WAIT_MS = 600L
        /** Sin fotogramas en pantalla durante este tiempo = vista previa parada (ms). */
        const val PREVIEW_STALL_MS = 1500L
        /** Cada cuántos disparos se hace un barrido de enfoque automático para medir distancia. */
        const val AUTO_SWEEP_EVERY = 6
        const val DEFAULT_CLOUD_URL = "https://tismaservidores.net"
    }
}
