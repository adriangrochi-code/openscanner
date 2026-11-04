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
    /** El token de la sesión de Tisma Cloud (vacío: sin sesión) y el correo de la cuenta. */
    val cloudKey: String = "",
    val cloudEmail: String = "",
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
    /** Vídeo 360: una sola cámara con flujo continuo; los fotogramas clave salen solos. */
    val videoMode: Boolean = true,
    /** Fotogramas clave guardados en este escaneo (modo vídeo). */
    val videoFrames: Int = 0,
    /** En el último segundo se saltaron fotogramas por ir demasiado rápido. */
    val videoTooFast: Boolean = false,
    /** La pieza en vivo como superficie (trozos con textura) y nº de puntos triangulados. */
    val surface: List<com.tisma.scanner.recon.LivePointCloud.Fragment> = emptyList(),
    val cloudPoints: Int = 0,
    /** Vista previa del último escaneo: superficie (.obj) y nube (.ply). */
    val cloudPly: File? = null,
    val surfaceObj: File? = null,
    /** Guía AR: puntos con su calidad (0 rojo .. 1 verde) y fotos flotando donde se tomaron. */
    val arXyz: FloatArray = FloatArray(0),
    val arQuality: FloatArray = FloatArray(0),
    val arCards: List<ArCard> = emptyList(),
    /** Modelo calculado en el móvil (malla cerrada con color) y su STL en mm. */
    val localModel: com.tisma.scanner.recon.LivePointCloud.Fragment? = null,
    val localStl: File? = null,
    val localBusy: Boolean = false,
    /** Malla cerrada que se va formando mientras se graba (preproceso). */
    val liveMesh: com.tisma.scanner.recon.LivePointCloud.Fragment? = null,
    /** Qué se está adelantando mientras se graba ("adelantando: IA 5/12 · nitidez 3/8"). */
    val prepText: String = "",
    /** El modelo del móvil ya incluye la IA de profundidad (si no, es la malla rápida). */
    val localAi: Boolean = false,
    /** IA de profundidad (Depth Anything 3 Small) para el modelo del móvil. */
    val useDepthAi: Boolean = true,
    val depthModelReady: Boolean = false,
    val depthModelText: String = "No descargado",
    val depthModelUrl: String = "",
    /** Quitar el desenfoque de movimiento de las fotos en el móvil (con el giro medido por la INS). */
    val deblurMotion: Boolean = true,
    /** Trabajo largo en curso (p.ej. "Mejorando nitidez 12/40"). */
    val progressText: String = "",
    /** Indicación grande de la grabación (estable ~1 s para que no parpadee). */
    val instruction: Instruction? = null,
    /** Guía en vivo de la cobertura (anillo actual, hueco y sentido de giro). */
    val guide: CoverageMap.Guidance? = null,
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
        // ¿La app se cerró por un error la última vez? Se avisa en el registro (y el detalle queda en el diagnóstico).
        runCatching {
            val crash = File(app.getExternalFilesDir(null), "scans/diagnostico/ultimo_cierre.txt")
            if (crash.exists()) {
                com.tisma.scanner.diag.DiagLog.event("app", "la vez anterior la app se cerró por un error:\n" + crash.readText().take(4000))
                crash.renameTo(File(crash.parentFile, "cierre_" + crash.lastModified() + ".txt"))
                _state.update { it.copy(log = (it.log + "⚠ La app se cerró por un error la última vez (detalle en el registro de diagnóstico)").takeLast(8)) }
            }
        }
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
            cloudKey = prefs.getString("cloud_token", "") ?: "",
            cloudEmail = prefs.getString("cloud_email", "") ?: "") }
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
        _state.update { it.copy(depthModelUrl = prefs.getString("depth_model_url", "") ?: "") }
        refreshDepthModelState()
        cameras.videoMode = _state.value.videoMode
        com.tisma.scanner.diag.DiagLog.extra = {
            "escaneo=${if (session != null) "sí" else "no"} · capturando=${if (sequenceRunning) "sí" else "no"} · " +
                "escrituras=${pendingWrites.get()} · análisis=${pendingAnalysis.get()} · giro %.2f rad/s".format(imu.gyroRate)
        }
        com.tisma.scanner.diag.DiagLog.event("app", "foco fijo en escaneo: ${cameras.lockFocus} · vista previa estabilizada: ${_state.value.stabilizePreview}")
    }

    // ------------------------------------------------------------------ segundo plano

    @Volatile private var backgrounded = false

    /** La app deja de verse: sensores parados (y el escaneo, si lo había, se guarda). */
    fun onAppBackground() {
        if (backgrounded) return
        backgrounded = true
        com.tisma.scanner.diag.DiagLog.event("app", "segundo plano: sensores parados")
        if (session != null) {
            log("Escaneo guardado al pasar a segundo plano (sin cámara ni sensores no puede seguir)")
            stopScan()
        }
        if (_state.value.driftProgress.isNotEmpty() && driftJob?.isActive == true) cancelDriftTest()
        imu.stop()
    }

    /** Vuelve a verse: sensores en marcha y la INS desde cero (calibraciones conservadas). */
    fun onAppForeground() {
        if (!backgrounded) return
        backgrounded = false
        imu.start()
        ins.reset()
        stabilizer.reset()
        com.tisma.scanner.diag.DiagLog.event("app", "primer plano: sensores en marcha, INS reiniciada")
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

    /** Entra con la cuenta de Tisma Cloud; guarda el token de la sesión de este teléfono (no la contraseña). */
    override fun cloudLogin(email: String, password: String) {
        val url = _state.value.cloudUrl
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(cloudStatus = "Entrando…", cloudBusy = true) }
            try {
                val device = "Tisma Scanner · " + android.os.Build.MODEL
                val (token, mail) = com.tisma.scanner.cloud.CloudClient.login(url, email, password, device)
                prefs.edit().putString("cloud_token", token).putString("cloud_email", mail).apply()
                _state.update { it.copy(cloudKey = token, cloudEmail = mail, cloudStatus = "✓ Sesión iniciada", cloudBusy = false) }
            } catch (e: Exception) {
                _state.update { it.copy(cloudStatus = "✗ ${e.message ?: e.javaClass.simpleName}", cloudBusy = false) }
            }
        }
    }

    override fun cloudLogout() {
        val ui = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { com.tisma.scanner.cloud.CloudClient(ui.cloudUrl, ui.cloudKey).logout() }
        }
        prefs.edit().remove("cloud_token").remove("cloud_email").apply()
        _state.update { it.copy(cloudKey = "", cloudEmail = "", cloudStatus = "") }
    }

    /**
     * Envía el último escaneo a Tisma Cloud, sigue el trabajo y descarga la pieza.
     * La subida es reanudable: el id de la subida se guarda por escaneo, así que si se corta o se
     * cierra la app, el siguiente envío continúa donde quedó en vez de empezar de cero; una vez en la
     * cola, se guarda el id del trabajo y se sigue ése.
     */
    override fun sendToCloud() {
        val zip = _state.value.lastZip ?: return
        if (cloudJob?.isActive == true) return
        val ui = _state.value
        cloudJob = viewModelScope.launch(Dispatchers.IO) {
            fun st(text: String, progress: Float = -1f, busy: Boolean = true) =
                _state.update { it.copy(cloudStatus = text, cloudProgress = progress, cloudBusy = busy) }
            val uploadKey = "scan_" + zip.name + "_" + zip.length()
            val jobKey = "$uploadKey.job"
            // El trabajo terminó mal en el servidor: el próximo envío empieza de nuevo.
            var ended = false
            try {
                val client = com.tisma.scanner.cloud.CloudClient(ui.cloudUrl, ui.cloudKey)
                st("Conectando con ${ui.cloudUrl}…")
                var jobId = prefs.getString(jobKey, null)
                if (jobId == null) {
                    var id = prefs.getString(uploadKey, null)
                    var chunk = 16 * 1024 * 1024
                    val existing = id?.let { runCatching { client.scanStatus(it) }.getOrNull() }
                    jobId = existing?.optString("job_id")?.takeIf { it.isNotBlank() && it != "null" }
                    if (jobId == null) {
                        if (existing == null) {
                            val (newId, c) = client.create(zip.length(), zip.name)
                            id = newId; chunk = c
                            prefs.edit().putString(uploadKey, id).apply()
                        } else {
                            chunk = existing.optInt("chunk_size", chunk)
                        }
                        jobId = client.upload(id!!, zip, chunk, onProgress = { done, total ->
                            st("Subiendo %.0f / %.0f MB".format(done / 1e6, total / 1e6), done.toFloat() / total)
                        }, isCancelled = { !isActive })
                    }
                    prefs.edit().putString(jobKey, jobId).remove(uploadKey).apply()
                }
                // Seguimiento del trabajo en la cola de Tisma Cloud.
                var job = JSONObject()
                while (true) {
                    job = client.job(jobId!!)
                    when (job.optString("status")) {
                        "queued" -> st("En cola en Tisma Cloud…")
                        "running" -> job.optInt("progress").let { st("Procesando en la nube: $it %", it / 100f) }
                        "done" -> break
                        "failed" -> {
                            ended = true
                            val s = job.optJSONObject("summary")
                            throw java.io.IOException(s?.optString("error_es")?.takeIf { it.isNotBlank() }
                                ?: job.optString("error", "el escaneo falló en el servidor"))
                        }
                        "cancelled" -> { ended = true; throw java.io.IOException("el escaneo se canceló") }
                    }
                    delay(5000)
                }
                st("Descargando la pieza…")
                val dir = zip.parentFile!!
                val stl = File(dir, zip.nameWithoutExtension + "_pieza.stl")
                val reportFile = File(dir, zip.nameWithoutExtension + "_informe.json")
                client.downloadResult(jobId!!, mapOf("pieza.stl" to stl, "pieza_informe.json" to reportFile))
                if (!stl.exists()) throw java.io.IOException("el resultado no trae la pieza (pieza.stl)")
                val summary = runCatching {
                    val r = job.optJSONObject("summary") ?: JSONObject(reportFile.readText())
                    val d = r.getJSONArray("dimensions_mm_xyz")
                    "%.2f × %.2f × %.2f mm".format(d.getDouble(0), d.getDouble(1), d.getDouble(2)) +
                        (if (r.optBoolean("watertight")) " · cerrada" else "")
                }.getOrDefault("")
                prefs.edit().remove(jobKey).apply()
                _state.update { it.copy(cloudStatus = "✓ Pieza lista $summary", cloudProgress = -1f, cloudBusy = false, cloudStl = stl) }
                log("Pieza descargada: ${stl.name} $summary")
            } catch (e: Exception) {
                if (e is com.tisma.scanner.cloud.CloudClient.HttpError && e.code == 401) {
                    // La sesión venció o se cerró en otro lado: hay que entrar de nuevo.
                    prefs.edit().remove("cloud_token").apply()
                    _state.update { it.copy(cloudKey = "") }
                }
                if (ended) prefs.edit().remove(jobKey).apply()
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

    override fun setDeblurMotion(v: Boolean) = _state.update { it.copy(deblurMotion = v) }

    // ------------------------------------------------------------------ IA de profundidad (DA3 Small)

    private val depthModelFile get() = File(getApplication<Application>().filesDir, "models/da3_small.onnx")

    /**
     * Modelo incluido en el APK (assets/models): se copia una vez a la memoria de la app (ONNX
     * Runtime necesita un archivo). Si viene partido en trozos (.part00, .part01…) se une.
     * Se vuelve a copiar si cambia la versión incluida (otro da3_small.json).
     */
    private fun installBundledDepthModel() {
        val am = getApplication<Application>().assets
        val names = runCatching { am.list("models")?.toList() }.getOrNull().orEmpty()
        val parts = if ("da3_small.onnx" in names) listOf("da3_small.onnx")
            else names.filter { it.startsWith("da3_small.onnx.part") }.sorted()
        if (parts.isEmpty()) return
        val sig = runCatching { am.open("models/da3_small.json").use { it.readBytes().contentHashCode().toString() } }.getOrDefault(parts.joinToString())
        val dst = depthModelFile
        if (dst.exists() && prefs.getString("bundled_model_sig", "") == sig) return
        _state.update { it.copy(depthModelText = "Preparando el modelo incluido…") }
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".part")
        runCatching {
            tmp.outputStream().use { out -> for (p in parts) am.open("models/$p").use { it.copyTo(out, 1 shl 16) } }
            tmp.renameTo(dst)
            prefs.edit().putString("bundled_model_sig", sig).apply()
            log("IA de profundidad: modelo incluido en la app listo (%.0f MB)".format(dst.length() / 1e6))
        }.onFailure { tmp.delete(); log("IA de profundidad: no se pudo preparar el modelo incluido: ${it.message}") }
    }

    private fun refreshDepthModelState() {
        val f = depthModelFile
        _state.update { it.copy(depthModelReady = f.exists(), depthModelText =
            if (f.exists()) "Instalado · %.0f MB".format(f.length() / 1e6) else "No descargado") }
    }

    override fun setUseDepthAi(v: Boolean) = _state.update { it.copy(useDepthAi = v) }
    override fun setDepthModelUrl(v: String) {
        _state.update { it.copy(depthModelUrl = v.trim()) }
        prefs.edit().putString("depth_model_url", v.trim()).apply()
    }

    /** Descarga el modelo (una vez) desde la URL configurada (por defecto, el propio Tisma Server). */
    override fun downloadDepthModel() {
        val url = _state.value.depthModelUrl.ifBlank { _state.value.cloudUrl.trimEnd('/') + "/models/da3_small.onnx" }
        _state.update { it.copy(depthModelText = "Descargando…") }
        viewModelScope.launch(Dispatchers.IO) {
            val dst = depthModelFile; dst.parentFile?.mkdirs()
            val part = File(dst.parentFile, dst.name + ".part")
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                _state.value.cloudKey.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                c.connectTimeout = 15000; c.readTimeout = 60000
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { ins -> part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16); var n: Int; var got = 0L; var lastUi = 0L
                    while (ins.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n); got += n
                        val now = SystemClock.uptimeMillis()
                        if (now - lastUi > 300) { lastUi = now
                            _state.update { it.copy(depthModelText = if (total > 0) "Descargando %d %%".format(got * 100 / total) else "Descargando %.0f MB".format(got / 1e6)) } }
                    }
                } }
                // Comprobar que ONNX Runtime lo abre antes de darlo por bueno.
                val ok = com.tisma.scanner.recon.DepthModel.load(part)?.use { m -> log("IA de profundidad: entrada ${m.inW}×${m.inH}"); true } ?: false
                if (!ok) throw java.io.IOException("el archivo no es un modelo ONNX válido")
                part.renameTo(dst)
                refreshDepthModelState()
            } catch (e: Exception) {
                part.delete()
                _state.update { it.copy(depthModelText = "✗ No se pudo descargar: ${e.message}") }
            }
        }
    }

    /**
     * Profundidad densa con IA para cada vista guardada, ya en metros (alineada con los puntos
     * medidos). Si no hay modelo o una vista no se puede alinear, se salta.
     */
    /** Profundidad de la IA de una vista, ya en metros (alineada con los puntos medidos) o null. */
    private fun aiViewFor(m: com.tisma.scanner.recon.DepthModel, s: com.tisma.scanner.recon.DepthShot): com.tisma.scanner.recon.DepthPrior.DepthView? {
        val gw = MultiCameraController.KF_W; val gh = MultiCameraController.KF_H
        val bmp = android.graphics.BitmapFactory.decodeByteArray(s.jpeg, 0, s.jpeg.size) ?: return null
        val pred = runCatching { m.predict(bmp) }.getOrNull(); bmp.recycle()
        if (pred == null) return null
        // Puntos medidos (px de la imagen de seguimiento) a la rejilla de la predicción.
        val samples = s.samples.map { (u, v, d) -> Triple(u * pred.w / gw, v * pred.h / gh, d) }
        val fit = com.tisma.scanner.recon.DepthPrior.fit(pred.depth, pred.w, pred.h, samples) ?: return null
        if (fit.relRms > 0.05) return null          // la IA no cuadra con lo medido en esta vista
        val ds = s.samples.map { it.third }
        return com.tisma.scanner.recon.DepthPrior.DepthView(s.view, pred.w, pred.h,
            com.tisma.scanner.recon.DepthPrior.toMetric(pred.depth, fit, ds.min() * 0.7, ds.max() * 1.3, pred.w, pred.h), gw, gh)
    }

    /**
     * Profundidad con IA de todas las vistas: las que ya se calcularon mientras se grababa
     * (`aiCache`) no se repiten; sólo las que falten.
     */
    private fun aiDepthViews(shots: List<com.tisma.scanner.recon.DepthShot>): List<com.tisma.scanner.recon.DepthPrior.DepthView> {
        val f = depthModelFile
        if (!f.exists() || shots.isEmpty()) return emptyList()
        val out = ArrayList<com.tisma.scanner.recon.DepthPrior.DepthView>()
        val missing = ArrayList<Int>()
        for ((i, s) in shots.withIndex()) {
            if (aiCache.containsKey(i) && aiCacheScan == lastScanDir) aiCache[i]?.let { out += it } else missing += i
        }
        if (missing.isNotEmpty()) {
            val model = com.tisma.scanner.recon.DepthModel.load(f) ?: run { log("IA de profundidad: no se pudo cargar el modelo"); return out }
            model.use { m ->
                for ((n, i) in missing.withIndex()) {
                    progress("Profundidad con IA ${n + 1}/${missing.size}", (n + 1) * 100 / missing.size)
                    aiViewFor(m, shots[i])?.let { out += it }
                }
            }
        }
        log("IA de profundidad: ${out.size}/${shots.size} vistas alineadas con lo medido (${shots.size - missing.size} ya hechas mientras se grababa)")
        return out
    }

    // ------------------------------------------------------------------ preproceso mientras se graba

    /** Trabajo adelantado con la potencia que sobra al grabar (prioridad baja, pocos núcleos). */
    private val pre = com.tisma.scanner.session.IdleWorker()
    /** IA de profundidad ya calculada, por índice de vista (null = no se pudo alinear). */
    private val aiCache = java.util.concurrent.ConcurrentHashMap<Int, com.tisma.scanner.recon.DepthPrior.DepthView?>()
    @Volatile private var aiCacheScan: File? = null
    @Volatile private var captureModel: com.tisma.scanner.recon.DepthModel? = null
    private val preDeblurred = java.util.concurrent.atomic.AtomicInteger()
    private val preDeblurQueued = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var lastLiveMeshMs = 0L
    @Volatile private var lastLiveMeshFragments = 0

    private fun prepText() {
        val parts = buildList {
            if (aiCache.isNotEmpty() || depthShots.isNotEmpty()) if (_state.value.useDepthAi && _state.value.depthModelReady)
                add("IA ${aiCache.size}/${depthShots.size}")
            if (preDeblurQueued.get() > 0) add("nitidez ${preDeblurred.get()}/${preDeblurQueued.get()}")
        }
        _state.update { it.copy(prepText = if (parts.isEmpty()) "" else "adelantando: " + parts.joinToString(" · ")) }
    }

    /** Vista nueva para la IA: se calcula ya (2 núcleos), y la malla final no tendrá que esperarla. */
    private fun preAiDepth(index: Int, shot: com.tisma.scanner.recon.DepthShot) {
        if (!_state.value.useDepthAi || !_state.value.depthModelReady) return
        val gen = reconGen
        pre.submit(com.tisma.scanner.session.IdleWorker.Priority.HIGH) {
            if (gen != reconGen) return@submit
            val m = captureModel ?: com.tisma.scanner.recon.DepthModel.load(depthModelFile, threads = 2)?.also { captureModel = it } ?: return@submit
            val v = aiViewFor(m, shot)
            if (gen == reconGen) aiCache[index] = v
            prepText()
        }
    }

    /** Malla cerrada en vivo cada ~8 s (rápida, con la IA ya calculada): se ve la pieza formándose. */
    private fun preLiveMesh(now: Long) {
        if (now - lastLiveMeshMs < 8000) return
        val n = live.fragments().size
        if (n < 3 || n == lastLiveMeshFragments) return
        lastLiveMeshMs = now
        pre.submit(com.tisma.scanner.session.IdleWorker.Priority.NORMAL) {
            val frs = live.fragments(); lastLiveMeshFragments = frs.size
            val dense = aiCache.values.filterNotNull()
            val t0 = SystemClock.uptimeMillis()
            val m = com.tisma.scanner.recon.LocalModel.build(frs, resolution = 64, dense = dense) ?: return@submit
            _state.update { it.copy(liveMesh = m.asFragment(maxTris = 15_000)) }
            com.tisma.scanner.diag.DiagLog.max("malla_vivo_ms", SystemClock.uptimeMillis() - t0)
        }
    }

    /** Foto movida guardada: se corrige ya (2 núcleos) y se anota, para no repetirla al final. */
    private fun preDeblur(dir: File, f: File, dx: Double, dy: Double) {
        preDeblurQueued.incrementAndGet(); prepText()
        pre.submit(com.tisma.scanner.session.IdleWorker.Priority.LOW) {
            val r = runCatching { com.tisma.scanner.quality.JpegDeblur.apply(f, dx, dy, threads = 2) }.getOrDefault(false)
            val log = File(dir, "deblurred.csv")
            synchronized(this) {
                if (!log.exists()) log.writeText("file,blur_dx_px,blur_dy_px,applied\n")
                log.appendText("${f.relativeTo(dir).invariantSeparatorsPath},%.2f,%.2f,%d\n".format(java.util.Locale.US, dx, dy, if (r) 1 else 0))
            }
            preDeblurred.incrementAndGet(); prepText()
        }
    }

    /** Progreso de un trabajo largo: en la app y en la notificación del procesado en segundo plano. */
    private fun progress(text: String, percent: Int = -1) {
        _state.update { it.copy(progressText = text) }
        if (text.isNotEmpty()) com.tisma.scanner.ProcessingService.update(getApplication(), text, percent)
    }

    /**
     * Construye el modelo del móvil con lo reconstruido en vivo (y la IA de profundidad si se
     * pide) y lo deja en la revisión. Corre en el hilo de la reconstrucción.
     */
    private fun buildModel(frags: List<com.tisma.scanner.recon.LivePointCloud.Fragment>, shots: List<com.tisma.scanner.recon.DepthShot>,
                           withAi: Boolean, resolution: Int, label: String): Boolean {
        val t0 = SystemClock.uptimeMillis()
        val dense = if (withAi) aiDepthViews(shots) else emptyList()
        if (withAi && dense.isEmpty()) return false
        val model = com.tisma.scanner.recon.LocalModel.build(frags, resolution = resolution, dense = dense) { p ->
            progress("$label %d %%".format((p * 100).toInt()), (p * 100).toInt())
        } ?: return false
        val dir = lastScanDir ?: getApplication<Application>().getExternalFilesDir(null)!!
        val stl = File(dir, "pieza_movil.stl")
        runCatching { model.writeStl(stl); model.writePly(File(dir, "pieza_movil.ply")) }
        log("$label: ${model.triangleCount} triángulos en %.1f s".format((SystemClock.uptimeMillis() - t0) / 1000.0) +
            if (dense.isNotEmpty()) " · ${dense.size} vistas con IA" else "")
        _state.update { it.copy(localModel = model.asFragment(), localStl = stl, localAi = dense.isNotEmpty()) }
        return true
    }

    /** Carpeta del último escaneo (para guardar el modelo local junto a él). */
    @Volatile private var lastScanDir: File? = null

    override fun buildLocalModel() {
        if (_state.value.localBusy || _state.value.scanning) return
        _state.update { it.copy(localBusy = true) }
        reconExec.execute {
            try {
                val ai = _state.value.useDepthAi && _state.value.depthModelReady
                val frs = live.fragments(); val shots = depthShots.toList()
                val ok = (ai && buildModel(frs, shots, true, 128, "Malla con IA")) || buildModel(frs, shots, false, 128, "Malla en el móvil")
                _state.update { it.copy(cloudStatus = if (ok) "✓ Modelo creado en el móvil" else "✗ No hay suficiente superficie: rodea la pieza a dos alturas") }
            } catch (e: Throwable) {
                log("Modelo en el móvil: error $e")
            } finally {
                _state.update { it.copy(localBusy = false) }; progress("")
            }
        }
    }

    override fun setVideoMode(v: Boolean) {
        if (session != null || _state.value.videoMode == v) return
        _state.update { it.copy(videoMode = v) }
        cameras.videoMode = v
        com.tisma.scanner.diag.DiagLog.event("app", if (v) "modo vídeo 360" else "modo fotos")
        viewModelScope.launch { openRig(planCurrentPass().groups) }
    }

    // ------------------------------------------------------------------ vídeo 360

    /** Reconstrucción en vivo: forma primero (sin color), color después. Hilo propio. */
    /** La posición ya la corrige la odometría visual-inercial (MSCKF) de la INS: sin corrección propia. */
    private val live = com.tisma.scanner.recon.LivePointCloud(maxCorrectionM = 0.0)
    private val reconExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "recon").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val reconPending = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var orbitDist = 0.0
    private val _arPose = kotlinx.coroutines.flow.MutableStateFlow<ArPose?>(null)
    override val arPose: kotlinx.coroutines.flow.StateFlow<ArPose?> = _arPose
    private var lastArMs = 0L
    private var lastCardDir: com.tisma.scanner.ins.Vec3? = null
    private val cards = java.util.concurrent.CopyOnWriteArrayList<ArCard>()

    /** Pose actual para la AR: orientación de la INS (giróscopo) en la órbita a la distancia del enfoque. */
    private fun updateArPose(now: Long) {
        if (session == null || !_state.value.videoMode || orbitDist <= 0.0) { if (_arPose.value != null) _arPose.value = null; return }
        if (now - lastArMs < 33) return
        lastArMs = now
        val cam = cameras.previewCameraId ?: return
        val st = ins.latest() ?: return
        val info = inventory[cam] ?: return
        val focal = info.focalLengthsMm.firstOrNull()?.toDouble() ?: return
        val sw = info.sensorSizeMm?.first?.toDouble() ?: return
        val orient = sensorOrientationCache.getOrPut(cam) { cameras.sensorOrientation(cam) }
        val (ax, ay, az) = com.tisma.scanner.ins.OpticalVelocity.cameraAxesInDevice(orient)
        val q = st.orientation
        val z = q.rotate(az)
        val w = MultiCameraController.KF_W
        val center = if (poseSource.mode == com.tisma.scanner.recon.PoseSource.Mode.ORBIT) z * -orbitDist else st.position + live.insOffset
        _arPose.value = ArPose(q.rotate(ax), q.rotate(ay), z, center, Optics.fxPixels(focal, sw, w), w, MultiCameraController.KF_H, orient)
    }

    /** Miniatura vertical pequeña (para las fotos flotantes) desde la miniatura del sensor. */
    private fun portraitCard(thumb: IntArray, tw: Int, th: Int, orient: Int, step: Int = 4): Triple<IntArray, Int, Int> {
        val w = th / step; val h = tw / step
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val (u, v) = if (((orient % 360) + 360) % 360 == 270) (tw - 1 - y * step) to (x * step) else (y * step) to (th - 1 - x * step)
            out[y * w + x] = thumb[v.coerceIn(0, th - 1) * tw + u.coerceIn(0, tw - 1)]
        }
        return Triple(out, w, h)
    }

    /** Vista guardada para la IA de profundidad: pose corregida, foto pequeña (JPEG) y puntos medidos. */
    private val depthShots = java.util.concurrent.CopyOnWriteArrayList<com.tisma.scanner.recon.DepthShot>()
    @Volatile private var lastDepthDir: com.tisma.scanner.ins.Vec3? = null

    override fun onVideoKeyframe(cameraId: String, timestampNs: Long, gray: FloatArray, thumb: IntArray, color: IntArray) {
        if (session == null) return
        // Si el móvil no da abasto, se salta (es sólo la vista previa).
        if (reconPending.get() >= 2) { com.tisma.scanner.diag.DiagLog.count("recon_saltado"); return }
        val t = timestampNs + (clockOffset[cameraId] ?: 0L)
        val st = ins.stateAt(t) ?: return
        val info = inventory[cameraId] ?: return
        val focal = info.focalLengthsMm.firstOrNull()?.toDouble() ?: return
        val sw = info.sensorSizeMm?.first?.toDouble() ?: return
        val orient = sensorOrientationCache.getOrPut(cameraId) { cameras.sensorOrientation(cameraId) }
        // Distancia de la órbita: la del enfoque, suavizada (el AF continuo "respira").
        val d = Optics.distanceM(cameras.primaryFocusDiopters())?.coerceIn(0.05, 2.0) ?: 0.3
        orbitDist = if (orbitDist == 0.0) d else orbitDist * 0.8 + d * 0.2
        val (ax, ay, az) = com.tisma.scanner.ins.OpticalVelocity.cameraAxesInDevice(orient)
        val q = st.orientation
        val w = MultiCameraController.KF_W; val h = MultiCameraController.KF_H
        val xw = q.rotate(ax); val yw = q.rotate(ay); val zw = q.rotate(az)
        val fpx = Optics.fxPixels(focal, sw, w)
        val dist = orbitDist
        reconPending.incrementAndGet()
        reconExec.execute {
            try {
                val t0 = System.nanoTime()
                live.depthRange = dist * 0.4..dist * 2.0
                // 1) Seguir los puntos; 2) la INS (MSCKF) se corrige con ellos y da la posición de
                // la cámara en este fotograma; 3) con esa posición se triangula y se arma la superficie.
                val obs = live.track(com.tisma.scanner.ins.OpticalFlow.Image(w, h, gray))
                val cIns = ins.onVisualKeyframe(t, xw, yw, zw, obs, fpx, w / 2.0, h / 2.0) ?: st.position
                // Posición: la de la INS (MSCKF) o, si ésta no "ve" el desplazamiento, la órbita
                // (la cámara mira a la pieza a la distancia del enfoque: −distancia·eje óptico).
                val c = if (poseSource.mode == com.tisma.scanner.recon.PoseSource.Mode.ORBIT) zw * -dist else cIns
                val fd = firstDir ?: zw.also { firstDir = it }
                turnedDeg = maxOf(turnedDeg, Math.toDegrees(kotlin.math.acos((fd dot zw).coerceIn(-1.0, 1.0))))
                val view = com.tisma.scanner.recon.LivePointCloud.View(xw, yw, zw, c, fpx, w / 2.0, h / 2.0)
                live.commit(view, thumb, MultiCameraController.THUMB_W, MultiCameraController.THUMB_H)
                if (poseSource.update(live.keyframeCount, live.pointCount(), turnedDeg)) switchToOrbit()
                // Una vista para la IA de profundidad cada ~8° (como mucho 72): foto pequeña + puntos medidos.
                val pd = lastDepthDir
                val samples = live.lastObservedDepths
                if (samples.size >= 12 && depthShots.size < 72 &&
                    (pd == null || Math.toDegrees(kotlin.math.acos((pd dot view.zAxis).coerceIn(-1.0, 1.0))) >= 8.0)) {
                    lastDepthDir = view.zAxis
                    val bmp = android.graphics.Bitmap.createBitmap(color, MultiCameraController.DEPTH_W, MultiCameraController.DEPTH_H,
                        android.graphics.Bitmap.Config.ARGB_8888)
                    val bos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, bos); bmp.recycle()
                    val shot = com.tisma.scanner.recon.DepthShot(view, bos.toByteArray(), samples)
                    depthShots += shot
                    preAiDepth(depthShots.size - 1, shot)
                }
                // Pose corregida de este fotograma: va a la sesión para la reconstrucción final.
                session?.addRefinedPose(t, c, q)
                com.tisma.scanner.diag.DiagLog.max("vis_corr_mm", ((c - st.position).norm() * 1000).toLong())
                com.tisma.scanner.diag.DiagLog.count("vio_puntos", ins.visualFeaturesUsed.toLong())
                // Foto flotante cada ~7° de cambio de dirección (como mucho 60).
                val prevDir = lastCardDir
                if (prevDir == null || Math.toDegrees(kotlin.math.acos((prevDir dot view.zAxis).coerceIn(-1.0, 1.0))) >= 7.0) {
                    lastCardDir = view.zAxis
                    val (ct, cw, ch) = portraitCard(thumb, MultiCameraController.THUMB_W, MultiCameraController.THUMB_H, orient)
                    cards += ArCard(cards.size + 1, view.center, ct, cw, ch)
                    while (cards.size > 60) cards.removeAt(0)
                }
                // Etapa 2 (color) cada pocos fotogramas, para los puntos que ya no se ven.
                com.tisma.scanner.diag.DiagLog.max("recon_ms", (System.nanoTime() - t0) / 1_000_000)
                publishCloud()
            } catch (e: Exception) {
                com.tisma.scanner.diag.DiagLog.event("recon", "error: $e")
            } finally { reconPending.decrementAndGet() }
        }
    }

    /** Origen de la posición en la reconstrucción en vivo (INS o, si ésta no sirve, órbita). */
    private val poseSource = com.tisma.scanner.recon.PoseSource()
    @Volatile private var firstDir: com.tisma.scanner.ins.Vec3? = null
    @Volatile private var turnedDeg = 0.0
    /** Generación de la reconstrucción: lo calculado para una anterior (p.ej. la IA) se descarta. */
    @Volatile private var reconGen = 0

    /**
     * La INS no daba desplazamiento (todo se triangulaba en la cámara): se rehace la reconstrucción
     * en vivo con la órbita. Se tira lo que dependía de las posiciones anteriores.
     */
    private fun switchToOrbit() {
        reconGen++
        live.reset()
        depthShots.clear(); lastDepthDir = null; aiCache.clear()
        cards.clear(); lastCardDir = null
        lastLiveMeshFragments = 0
        _state.update { it.copy(liveMesh = null) }
        log("La posición de la INS no se movía al rodear la pieza: la vista previa pasa a modo órbita")
        com.tisma.scanner.diag.DiagLog.event("recon", "cambio a órbita tras $turnedDeg° de giro")
    }

    private fun publishCloud() {
        val frs = live.fragments(); val n = live.pointCount()
        val (xyz, q) = live.quality()
        val cs = cards.toList()
        _state.update { it.copy(surface = frs, cloudPoints = n, arXyz = xyz, arQuality = q, arCards = cs) }
    }

    @Volatile private var lastVideoState: InsState? = null
    @Volatile private var lastVideoSaveMs = 0L
    private val videoKept = java.util.concurrent.atomic.AtomicInteger()
    private val videoFast = java.util.concurrent.atomic.AtomicInteger()

    /**
     * ¿Guardar este fotograma del vídeo? (hilo de la cámara, debe ser rápido). Sólo si el móvil se
     * ha movido lo suficiente desde el último (vista nueva), no va demasiado rápido (arrastre) y
     * no se superan VIDEO_MAX_FPS. Lo mismo que haría el usuario, pero ~3 veces por segundo.
     */
    override fun wantVideoFrame(cameraId: String, timestampNs: Long, width: Int): Boolean {
        if (session == null || !_state.value.videoMode) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastVideoSaveMs < VIDEO_MIN_INTERVAL_MS) return false
        val t = timestampNs + (clockOffset[cameraId] ?: 0L)
        val st = ins.stateAt(t) ?: return false
        val last = lastVideoState
        val moved = last == null ||
            Math.toDegrees(last.orientation.angleTo(st.orientation)) >= VIDEO_MIN_ANGLE_DEG ||
            (st.position - last.position).norm() >= VIDEO_MIN_TRANSLATION_M
        if (!moved) return false
        val blurFull = motionBlurPx(cameraId, t, width)
        val blur = blurFull * minOf(1.0, REF_WIDTH_PX / width)
        // Con la corrección de nitidez, se aceptan arrastres que luego se pueden deshacer.
        val tooBlurred = if (_state.value.deblurMotion) blurFull > com.tisma.scanner.quality.MotionDeblur.MAX_PX * 0.8 else blur > MAX_MOTION_BLUR_PX
        if (tooBlurred) {
            videoFast.incrementAndGet(); com.tisma.scanner.diag.DiagLog.count("video_rapido")
            return false
        }
        lastVideoState = st; lastVideoSaveMs = now
        trigger++
        videoKept.incrementAndGet(); com.tisma.scanner.diag.DiagLog.count("video_kf")
        return true
    }

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
        // En vídeo 360 la imagen no se mueve ni se amplía: la guía AR se dibuja encima y debe coincidir.
        if (_state.value.videoMode) return null
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
        // Mientras se procesa el anterior (mallas, nitidez, .zip) no se empieza otro: competirían por la CPU.
        if (session != null || stopping != null || _state.value.busy) return
        updateClockOffsets()
        val base = File(getApplication<Application>().getExternalFilesDir(null), "scans").apply { mkdirs() }
        val s = ScanSession(base)
        s.writeSessionInfo(
            inventory.values,
            cameras.openedStreams.map { it.cameraId to "${it.size.width}x${it.size.height}" },
            JSONObject().apply {
                put("auto_trigger", _state.value.autoTrigger)
                put("video_mode", _state.value.videoMode)
                cameras.videoSize()?.let { v -> put("video_size", "${v.width}x${v.height}") }
                put("video_min_angle_deg", VIDEO_MIN_ANGLE_DEG)
                put("video_min_translation_m", VIDEO_MIN_TRANSLATION_M)
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
                put("deblur_on_phone", _state.value.deblurMotion)
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
        lastVideoState = null; lastVideoSaveMs = 0; videoKept.set(0); videoFast.set(0)
        orbitDist = 0.0
        cards.clear(); lastCardDir = null
        depthShots.clear(); lastDepthDir = null
        aiCache.clear(); aiCacheScan = s.dir; preDeblurred.set(0); preDeblurQueued.set(0)
        poseSource.reset(); firstDir = null; turnedDeg = 0.0; reconGen++
        lastLiveMeshMs = 0; lastLiveMeshFragments = 0
        pre.open()
        _state.update { it.copy(liveMesh = null, prepText = "") }
        reconExec.execute { live.reset(); publishCloud() }
        _state.update { it.copy(cloudPly = null, surfaceObj = null, instruction = null, localModel = null, localStl = null, localAi = false) }
        lastScanDir = s.dir
        lastInstruction = null
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
        // Lo que tarda va en segundo plano (processScan), protegido por un servicio con notificación.
        com.tisma.scanner.ProcessingService.start(getApplication(), "Guardando el escaneo…")
        // Lo reconstruido en vivo, a disco: si la app se cierra, las mallas se rehacen desde ahí.
        val reconSaved = kotlinx.coroutines.CompletableDeferred<Unit>()
        reconExec.execute {
            try {
                live.colorize(all = true); publishCloud()
                if (live.pointCount() > 0) {
                    val f = File(s.dir, "preview_cloud.ply")
                    val o = File(s.dir, "preview_surface.obj")
                    runCatching { live.writePly(f); live.writeObj(o) }.onSuccess {
                        _state.update { st -> st.copy(cloudPly = f, surfaceObj = o) }
                        log("Vista previa 3D: ${live.pointCount()} puntos, ${live.fragments().size} trozos de superficie")
                    }
                }
                runCatching { com.tisma.scanner.recon.ReconState.save(File(s.dir, "recon_state.bin"), live.fragments(), depthShots.toList()) }
                    .onFailure { log("No se pudo guardar el estado de la reconstrucción: ${it.message}") }
            } finally { reconSaved.complete(Unit) }
        }
        // La sesión pasa a "cerrándose": las fotos de la última ráfaga aún llegan y se guardan.
        stopping = s
        session = null
        imu.stopLogging()
        gps.stopLogging()
        cameras.lock3A = false; cameras.refreshRepeating()
        pendingCal?.let { applyAutoCalibration(it); pendingCal = null }
        _state.update { it.copy(scanning = false, busy = true) }
        viewModelScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { finish(s, reconSaved) }
    }

    /** Espera a lo que queda en vuelo y cierra. NonCancellable: salir de la app no corta el cierre. */
    private suspend fun finish(s: ScanSession, reconSaved: kotlinx.coroutines.Deferred<Unit>) {
        delay(800) // últimos JPEG del HAL
        val deadline = SystemClock.uptimeMillis() + 8000
        while ((pendingWrites.get() > 0 || pendingAnalysis.get() > 0) && SystemClock.uptimeMillis() < deadline) delay(50)
        // Los archivos del escaneo se cierran YA (fotos, poses, calidad): nada queda a medias en memoria.
        runCatching { s.writeQualitySummary(qualitySummary()) }
        s.close()
        stopping = null
        log("Escaneo guardado: ${s.imageCount.get()} imágenes en ${s.dir.absolutePath}")
        reconSaved.await()
        // Lo adelantado mientras se grababa: se espera a lo que esté en marcha y se cierra la cola.
        pre.closeAndDrain()
        captureModel?.close(); captureModel = null
        _state.update { it.copy(prepText = "") }
        // Lo que falta, en disco: así se puede retomar si la app se cierra.
        val jobs = if (_state.value.deblurMotion) generateSequence { deblurJobs.poll() }.toList() else { deblurJobs.clear(); emptyList() }
        deblurTimes.clear()
        if (jobs.isNotEmpty()) runCatching {
            File(s.dir, "deblur_pendiente.csv").writeText(jobs.joinToString("") { (f, dx, dy) ->
                "${f.relativeTo(s.dir).invariantSeparatorsPath},$dx,$dy\n" })
        }
        savePending(s.dir, listOf("malla_rapida", "malla_ia") + (if (jobs.isNotEmpty()) listOf("nitidez") else emptyList()) + "zip")
        processScan(s.dir, resumed = false)
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
    private var lastFastCheck = 0L
    @Volatile private var lastInstruction: Instruction? = null
    private var lastInstructionMs = 0L

    private suspend fun insLoop() {
        var lastUi = 0L
        var lastSensorUi = 0L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val st = ins.latest()
            val s = session
            if (st != null && s != null) {
                s.addTrajectory(st)
                if (_state.value.autoTrigger && !_state.value.videoMode && !sequenceRunning && selector.shouldTrigger(st, imu.gyroRate)) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) { fire(st) }
                }
            }
            val now = SystemClock.uptimeMillis()
            previewWatchdog(now)
            updateArPose(now)
            if (s != null && _state.value.videoMode) preLiveMesh(now)
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
                val relAz = coverage.relativeAz(liveAz)
                val guide = if (s != null) coverage.guidance(relAz, liveEl) else null
                val fast = if (now - lastFastCheck >= 1000) { lastFastCheck = now; videoFast.getAndSet(0) > 2 } else null
                val cand = if (s != null && _state.value.videoMode)
                    captureInstruction(coverage.rings, guide, videoKept.get(), fast ?: _state.value.videoTooFast) else null
                val prevIns = lastInstruction
                // Cambia como mucho cada 1,2 s, salvo avisos y "listo" (inmediatos).
                val instruction = if (cand == null || prevIns == null || cand == prevIns ||
                    cand.tone == Instruction.Tone.WARN || cand.tone == Instruction.Tone.DONE || now - lastInstructionMs > 1200) {
                    if (cand != prevIns) lastInstructionMs = now
                    lastInstruction = cand; cand
                } else prevIns
                _state.update {
                    it.copy(
                        guide = guide,
                        instruction = instruction,
                        videoFrames = videoKept.get(),
                        videoTooFast = fast ?: it.videoTooFast,
                        liveAzDeg = relAz,
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
                val vec = motionBlurVec(cameraId, t, width)
                val blurPx = vec?.let { kotlin.math.hypot(it.first, it.second) } ?: 0.0
                if (vec != null && _state.value.deblurMotion && blurPx >= com.tisma.scanner.quality.MotionDeblur.minPx(width) &&
                    blurPx <= com.tisma.scanner.quality.MotionDeblur.MAX_PX) {
                    deblurJobs += Triple(File(s.dir, "images/$cameraId/$t.jpg"), vec.first, vec.second)
                    preDeblur(s.dir, File(s.dir, "images/$cameraId/$t.jpg"), vec.first, vec.second)
                    deblurTimes += t
                }
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
        // El arrastre que se va a corregir en el móvil no es motivo para tirar la foto.
        val fixable = t in deblurTimes
        val sharpLow = trig !in stackTriggers && !fixable && blurJudge.isBlurry(cameraId, q.sharpness)
        val blurry = (!fixable && blurRef > MAX_MOTION_BLUR_PX) || sharpLow
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

    private fun motionBlurPx(cameraId: String, t: Long, width: Int): Double =
        motionBlurVec(cameraId, t, width, consume = false)?.let { kotlin.math.hypot(it.first, it.second) } ?: 0.0

    /**
     * Arrastre de la foto durante su exposición (px de la foto, ejes del sensor), con el giro que
     * midió la INS entre el inicio y el final de la exposición.
     */
    private fun motionBlurVec(cameraId: String, t: Long, width: Int, consume: Boolean = true): Pair<Double, Double>? {
        // Exposición real de esa foto (en HDR, +2 EV es 4× más larga que la previsualización).
        val key = "$cameraId/$t"
        val exp = (if (consume) exposures.remove(key) else exposures[key]) ?: cameras.exposureNsFor(cameraId)
        if (exp <= 0) return null
        val a = ins.stateAt(t) ?: return null
        val b = ins.stateAt(t + exp) ?: return null
        val info = inventory[cameraId] ?: return null
        val f = info.focalLengthsMm.firstOrNull()?.toDouble() ?: return null
        val sw = info.sensorSizeMm?.first?.toDouble() ?: return null
        val orient = sensorOrientationCache.getOrPut(cameraId) { cameras.sensorOrientation(cameraId) }
        return com.tisma.scanner.quality.MotionDeblur.blurVector(a.orientation, b.orientation,
            com.tisma.scanner.ins.OpticalVelocity.cameraAxesInDevice(orient), Optics.fxPixels(f, sw, width))
    }

    /** Fotos a enfocar en el móvil al terminar: (archivo, arrastre x, arrastre y). */
    private val deblurJobs = java.util.concurrent.ConcurrentLinkedQueue<Triple<File, Double, Double>>()
    private val deblurTimes = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Quita el desenfoque de movimiento de las fotos marcadas (en el móvil, 2 a la vez). */
    /**
     * Quita el desenfoque de movimiento de las fotos pendientes (en el móvil, de a una con todos
     * los núcleos). Cada foto hecha se anota al momento en deblurred.csv: si la app se cierra,
     * al retomar se sigue por la siguiente.
     */
    private suspend fun deblurAll(dir: File, jobs: List<Triple<File, Double, Double>>,
                                  threads: Int = Runtime.getRuntime().availableProcessors()) {
        val logFile = File(dir, "deblurred.csv")
        val done = runCatching { logFile.readLines().drop(1).map { it.substringBefore(',') }.toHashSet() }.getOrDefault(HashSet())
        val todo = jobs.filter { it.first.exists() && it.first.relativeTo(dir).invariantSeparatorsPath !in done }
        if (todo.isEmpty()) return
        if (!logFile.exists()) logFile.writeText("file,blur_dx_px,blur_dy_px,applied\n")
        var ok = 0
        val t0 = SystemClock.uptimeMillis()
        progress("Mejorando nitidez 0/${todo.size}", 0)
        for ((n, job) in todo.withIndex()) {
            val (f, dx, dy) = job
            val r = kotlinx.coroutines.withContext(Dispatchers.Default) {
                runCatching { com.tisma.scanner.quality.JpegDeblur.apply(f, dx, dy, threads = threads) }.getOrDefault(false)
            }
            if (r) ok++
            logFile.appendText("${f.relativeTo(dir).invariantSeparatorsPath},%.2f,%.2f,%d\n".format(java.util.Locale.US, dx, dy, if (r) 1 else 0))
            progress("Mejorando nitidez ${n + 1}/${todo.size}", (n + 1) * 100 / todo.size)
        }
        log("Nitidez: $ok/${todo.size} fotos corregidas en ${(SystemClock.uptimeMillis() - t0) / 1000} s " +
            "(%.1f s por foto)".format((SystemClock.uptimeMillis() - t0) / 1000.0 / todo.size))
    }

    // ------------------------------------------------------------------ procesado del escaneo (retomable)

    /** Pasos pendientes de un escaneo, en disco (pendiente.json): lo que queda si la app se cierra. */
    private fun pendingSteps(dir: File): MutableList<String> = runCatching {
        val a = JSONObject(File(dir, "pendiente.json").readText()).getJSONArray("pasos")
        MutableList(a.length()) { a.getString(it) }
    }.getOrDefault(mutableListOf())

    private fun savePending(dir: File, steps: List<String>) {
        val f = File(dir, "pendiente.json")
        if (steps.isEmpty()) { f.delete(); return }
        val tries = if (f.exists()) resumeAttempts(dir) else 0
        runCatching { f.writeText(JSONObject().put("pasos", org.json.JSONArray(steps)).put("intentos", tries).toString()) }
    }

    /**
     * Procesa (o retoma) un escaneo ya cerrado: malla rápida → malla con IA → nitidez → .zip.
     * Cada paso hecho se quita de pendiente.json. Protegido por el servicio en primer plano.
     */
    private suspend fun processScan(dir: File, resumed: Boolean) {
        com.tisma.scanner.ProcessingService.start(getApplication(), if (resumed) "Retomando el escaneo…" else "Procesando el escaneo…")
        _state.update { it.copy(busy = true) }
        lastScanDir = dir
        try {
            var steps = pendingSteps(dir)
            val needMeshes = steps.any { it.startsWith("malla") }
            val (frs, shots) = if (needMeshes)
                (com.tisma.scanner.recon.ReconState.load(File(dir, "recon_state.bin")) ?: (emptyList<com.tisma.scanner.recon.LivePointCloud.Fragment>() to emptyList()))
                else (emptyList<com.tisma.scanner.recon.LivePointCloud.Fragment>() to emptyList())
            if (resumed && frs.isNotEmpty()) _state.update { it.copy(surface = frs) }
            fun done(step: String) { steps.remove(step); savePending(dir, steps) }
            if ("malla_rapida" in steps) {
                // La malla en vivo (hecha mientras se grababa) se enseña YA; la rápida la sustituye en segundos.
                if (!resumed) _state.value.liveMesh?.let { lm -> _state.update { it.copy(localModel = lm, localAi = false) } }
                kotlinx.coroutines.withContext(Dispatchers.Default) { if (frs.size >= 2) buildModel(frs, shots, false, 80, "Malla rápida") }
                done("malla_rapida")
            }
            if ("malla_ia" in steps) {
                kotlinx.coroutines.withContext(Dispatchers.Default) {
                    if (_state.value.useDepthAi && _state.value.depthModelReady && shots.isNotEmpty() && frs.size >= 2)
                        buildModel(frs, shots, true, 128, "Malla con IA")
                }
                done("malla_ia")
            }
            if ("nitidez" in steps) {
                val jobs = runCatching { File(dir, "deblur_pendiente.csv").readLines().mapNotNull { l ->
                    val p = l.split(','); if (p.size < 3) null else Triple(File(dir, p[0]), p[1].toDouble(), p[2].toDouble()) } }.getOrDefault(emptyList())
                // Al retomar, la app acaba de abrir (cámara, interfaz): se dejan núcleos libres.
                deblurAll(dir, jobs, if (resumed) maxOf(1, Runtime.getRuntime().availableProcessors() - 3)
                    else Runtime.getRuntime().availableProcessors())
                done("nitidez")
            }
            if ("zip" in steps) {
                progress("Comprimiendo el escaneo…")
                val zip = runCatching { com.tisma.scanner.session.ScanSession.zipDir(dir) }.getOrNull()
                if (zip != null) { _state.update { it.copy(lastZip = zip) }; done("zip") }
            }
            if (steps.isEmpty()) {
                File(dir, "recon_state.bin").delete(); File(dir, "deblur_pendiente.csv").delete()
                log("Escaneo listo: ${dir.name}" + if (resumed) " (retomado tras cerrarse la app)" else "")
            }
        } catch (e: Throwable) {
            log("Procesado de ${dir.name}: error $e")
        } finally {
            _state.update { it.copy(busy = false, progressText = "") }
            com.tisma.scanner.ProcessingService.stop(getApplication())
        }
    }

    /** Al abrir la app: escaneos que quedaron a medias (la app se cerró) se terminan solos. */
    private fun resumeUnfinishedScans() {
        val base = File(getApplication<Application>().getExternalFilesDir(null), "scans")
        val pending = base.listFiles { f -> f.isDirectory && File(f, "pendiente.json").exists() }?.sortedBy { it.name }.orEmpty()
        if (pending.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            // Sin prisa: primero que la app y la cámara terminen de arrancar.
            delay(RESUME_DELAY_MS)
            for (dir in pending) {
                // Antibucle: si retomar este escaneo ya cerró la app dos veces, se deja lo pesado
                // (malla con IA, nitidez) y sólo se termina lo seguro (el .zip).
                val tries = resumeAttempts(dir) + 1
                setResumeAttempts(dir, tries)
                if (tries > MAX_RESUME_ATTEMPTS) {
                    val skipped = pendingSteps(dir).filter { it != "zip" }
                    if (skipped.isNotEmpty()) {
                        log("Retomar ${dir.name} cerró la app $MAX_RESUME_ATTEMPTS veces: se omite ${skipped.joinToString()}")
                        savePending(dir, pendingSteps(dir).filter { it == "zip" })
                    }
                }
                log("Retomando ${dir.name} (intento $tries): ${pendingSteps(dir).joinToString()}")
                processScan(dir, resumed = true)
            }
        }
    }

    private fun resumeAttempts(dir: File) = runCatching { JSONObject(File(dir, "pendiente.json").readText()).optInt("intentos", 0) }.getOrDefault(0)
    private fun setResumeAttempts(dir: File, n: Int) {
        val f = File(dir, "pendiente.json")
        runCatching { f.writeText(JSONObject(f.readText()).put("intentos", n).toString()) }
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
        reconExec.shutdown()
        pre.shutdown()
        writer.shutdown()
    }

    // Al FINAL de la construcción: los hilos de fondo arrancan cuando ya existen todas las piezas
    // (antes arrancaban en medio y, si llegaban primero a una que aún no existía, la app se cerraba
    // al abrirla: una carrera que pasaba más con la app "caliente", al volver a abrirla).
    init {
        // En el registro de diagnóstico, cada segundo: cómo va la reconstrucción en vivo (si la vista
        // previa sale vacía, aquí se ve si faltaron puntos, trozos o poses).
        val baseExtra = com.tisma.scanner.diag.DiagLog.extra
        com.tisma.scanner.diag.DiagLog.extra = {
            baseExtra() + if (session != null) " · en vivo: ${live.pointCount()} puntos, ${live.fragments().size} trozos, " +
                "${live.keyframeCount} vistas, ${depthShots.size} vistas IA, distancia %.2f m, giro %.0f°, posición ${poseSource.mode}".format(orbitDist, turnedDeg) else ""
        }
        loop = viewModelScope.launch(Dispatchers.Default) { insLoop() }
        viewModelScope.launch(Dispatchers.IO) {
            installBundledDepthModel(); refreshDepthModelState()
            // Con el modelo de IA ya listo, terminar lo que quedó a medias si la app se cerró.
            resumeUnfinishedScans()
        }
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
        /** Retomar un escaneo a medias: espera tras abrir la app y máximo de intentos (antibucle). */
        const val RESUME_DELAY_MS = 6000L
        const val MAX_RESUME_ATTEMPTS = 2
        /** Vídeo 360: un fotograma clave cada ~3° de giro o 1,5 cm, como mucho 4 por segundo. */
        const val VIDEO_MIN_ANGLE_DEG = 3.0
        const val VIDEO_MIN_TRANSLATION_M = 0.015
        const val VIDEO_MIN_INTERVAL_MS = 250L
        /** Sin fotogramas en pantalla durante este tiempo = vista previa parada (ms). */
        const val PREVIEW_STALL_MS = 1500L
        /** Cada cuántos disparos se hace un barrido de enfoque automático para medir distancia. */
        const val AUTO_SWEEP_EVERY = 6
        const val DEFAULT_CLOUD_URL = "https://tismaservidores.net"
    }
}
