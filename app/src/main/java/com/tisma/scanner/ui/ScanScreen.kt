package com.tisma.scanner.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tisma.scanner.camera.ScanPass
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

private enum class Tab(val label: String) { SCAN("Captura"), QUALITY("Calidad"), CAMERAS("Cámaras"), CLOUD("Nube"), LOG("Registro") }

@Composable
fun ScanScreen(vm: ScanViewModel, onShare: (File) -> Unit, onShareText: (String) -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    ScanContent(s, vm, onShare, onShareText = onShareText)
}

/**
 * Pantalla principal = la cámara, lo más grande posible, con controles flotantes encima.
 * Todos los ajustes viven en una pantalla aparte (⚙), que se dibuja encima sin cerrar la cámara.
 * `preview` permite sustituir la cámara en los tests de captura de pantalla.
 */
@Composable
fun ScanContent(
    s: UiState,
    vm: ScanActions,
    onShare: (File) -> Unit,
    initialTab: Int = 0,
    onShareText: (String) -> Unit = {},
    openSettings: Boolean = false,
    preview: @Composable () -> Unit = { CameraPreview(vm) },
    openReview: Boolean = false,
) {
    var settings by remember { mutableStateOf(openSettings) }
    var tab by remember { mutableStateOf(Tab.entries[initialTab]) }
    BackHandler(enabled = settings) { settings = false }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (s.videoMode) VideoCaptureScreen(s, vm, onShare, preview, onOpenSettings = { settings = true }, openReview = openReview)
        else CameraScreen(s, vm, onShare, preview, onOpenSettings = { settings = true })
        if (settings) SettingsScreen(s, vm, onShare, onShareText, tab, { tab = it }, onClose = { settings = false })
    }
}

// ------------------------------------------------------------------ cámara (pantalla principal)

private val topShade = Brush.verticalGradient(listOf(Color(0xE61E1E22), Color(0x991E1E22), Color.Transparent))
private val bottomShade = Brush.verticalGradient(listOf(Color.Transparent, Color(0x991E1E22), Color(0xF21E1E22)))

@Composable
private fun CameraScreen(
    s: UiState, vm: ScanActions, onShare: (File) -> Unit,
    preview: @Composable () -> Unit, onOpenSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Vista previa a todo el ancho (3:4, el formato del sensor), sin recortar lo que se fotografía.
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).align(Alignment.Center)) { preview() }
        // ---- arriba: marca, pasada, ajustes; debajo, telemetría y cúpula
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(topShade).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                HexLogo(Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text("TISMA", color = Tisma.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                PassSwitch(s, vm)
                Spacer(Modifier.width(8.dp))
                RoundIcon("⚙", "Ajustes", onOpenSettings)
            }
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Tisma.Purple)
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Zona de mejora de precisión: sólo aparece de tanto en tanto, unos segundos.
                    if (s.precisionBadge.isNotEmpty()) Chip(s.precisionBadge, if (s.precisionBadge.startsWith("✓")) Tisma.Ok else Tisma.PurpleText)
                    if (s.rigText.isNotEmpty()) Chip(s.rigText.substringBefore(" — "), Tisma.PurpleText)
                    s.opticsText.split(" · ").filter { it.isNotBlank() }.chunked(2).forEach { Chip(it.joinToString(" · ")) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (s.sensorStatus.isNotEmpty() && s.imuSamples == 0L) Chip("⚠ IMU sin datos", Tisma.Warn)
                        else Chip(if (s.stationary) "● quieto" else "○ en movimiento", if (s.stationary) Tisma.Ok else Tisma.Text)
                        if (s.gyroRate > 0.6) Chip("ω %.2f".format(s.gyroRate), Tisma.Warn)
                    }
                }
                if (s.scanning || s.analyzed > 0) {
                    Box(Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x991E1E22)).padding(5.dp)) {
                        CoverageDome(s.coverageRings, s.coverage, s.liveAzDeg, s.liveElDeg, size = 104.dp)
                    }
                }
            }
        }

        // ---- abajo: avisos, disparador y estado
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(bottomShade).navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val toasts = buildList {
                if (s.rigWarning.isNotEmpty()) add(Triple("Esta pasada no puede usar su cámara · ver ⚙ Cámaras", true, false))
                if (s.shotWarning.isNotEmpty()) add(Triple(s.shotWarning, true, false))
                if (s.scanning && s.capturing) add(Triple("Capturando… mantén el móvil quieto", false, false))
                else if (s.scanning && s.waitingSteady) add(Triple("Quieto un momento…", false, false))
                if (s.gyroRate > 0.6 && s.scanning) add(Triple("Demasiado rápido: las fotos saldrán movidas", true, false))
                if (s.scanning && s.analyzed > 0 && s.textureRecent < ScanViewModel.LOW_TEXTURE) {
                    add(Triple("Poca textura (%d %%): usa el tapete o spray mate".format((s.textureRecent * 100).toInt()), true, false))
                }
                if (s.cloudStatus.isNotEmpty()) add(Triple(s.cloudStatus, s.cloudStatus.startsWith("✗"), s.cloudStatus.startsWith("✓")))
                if (s.readinessText.isNotEmpty() && (s.scanning || s.analyzed > 0)) add(Triple(s.readinessText, false, s.ready))
                if (s.scanning) s.hints.firstOrNull()?.let { add(Triple(it, false, false)) }
            }.take(2)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                toasts.forEach { (text, error, ok) -> Toast(text, error = error, ok = ok) }
                if (s.cloudProgress >= 0f) {
                    LinearProgressIndicator(progress = { s.cloudProgress }, modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Tisma.Purple, trackColor = Tisma.Field)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!s.scanning && !s.busy) {
                ModeSwitch(s.videoMode, vm::setVideoMode)
                Spacer(Modifier.height(10.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                when {
                    s.scanning -> RoundIcon("■", "Terminar") { vm.stopScan() }
                    s.lastZip != null && !s.busy -> RoundIcon("⇪", "Compartir") { onShare(s.lastZip) }
                    else -> Spacer(Modifier.size(52.dp))
                }
                Shutter(s, vm)
                when {
                    s.scanning -> RoundIcon("◎", "Barrido foco") { vm.focusSweep() }
                    s.cloudStl != null && !s.cloudBusy -> RoundIcon("⬢", "Pieza STL") { onShare(s.cloudStl) }
                    s.lastZip != null && !s.busy && !s.cloudBusy -> RoundIcon("☁", "Enviar") { vm.sendToCloud() }
                    else -> Spacer(Modifier.size(52.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            StatusLine(s)
        }
    }
}

@Composable
private fun ModeChips(color: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCC2C2C31)).padding(3.dp)) {
        for ((v, label) in listOf(false to "Forma", true to "Color")) {
            val sel = v == color
            Text(label, color = if (sel) Color.White else Tisma.TextDim, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (sel) Tisma.PurpleDim else Color.Transparent)
                    .clickable { onChange(v) }.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

/** Vídeo 360 (por defecto) o fotos sueltas. */
@Composable
private fun ModeSwitch(video: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCC2C2C31)).padding(3.dp)) {
        for ((v, label) in listOf(true to "Vídeo 360", false to "Fotos")) {
            val sel = v == video
            Text(label, color = if (sel) Color.White else Tisma.TextDim, fontSize = 13.sp,
                fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (sel) Tisma.PurpleDim else Color.Transparent)
                    .clickable { onChange(v) }.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

/** Lejos / Cerca, como el grupo "3D printing | Engineering" de TISMA. */
@Composable
private fun PassSwitch(s: UiState, vm: ScanActions) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCC2C2C31)).padding(3.dp)) {
        // Sin la macro activada no hay pasada "Cerca": sólo se muestra "Lejos".
        for (p in ScanPass.entries.filter { it == ScanPass.FAR || s.useMacro }) {
            val sel = s.pass == p
            val pct = s.passCoverage[p]?.let { " ${(it * 100).toInt()}%" } ?: ""
            Column(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (sel) Tisma.PurpleDim else Color.Transparent)
                    .clickable { vm.setPass(p) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text((if (p == ScanPass.FAR) "Lejos" else "Cerca") + pct,
                    color = if (sel) Color.White else Tisma.TextDim, fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
                if (sel) Box(Modifier.padding(top = 3.dp).width(24.dp).height(2.dp).background(Tisma.PurpleText))
            }
        }
    }
}

/** Disparador grande central: Escanear y, durante el escaneo, sacar foto. */
@Composable
private fun Shutter(s: UiState, vm: ScanActions) {
    // Escaneando, el botón grande saca la foto (Terminar va a la izquierda).
    val scanning = s.scanning
    val enabled = if (scanning) !s.capturing && !s.waitingSteady else !s.busy
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(78.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)).padding(4.dp)
                .clip(CircleShape).background(Tisma.Bar).padding(4.dp).clip(CircleShape)
                .background(if (!enabled) Tisma.Field else Tisma.Purple)
                .clickable(enabled = enabled) { if (scanning) vm.captureNow() else vm.startScan() },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(if (scanning) 30.dp else 22.dp).clip(CircleShape).background(Color.White))
        }
        Text(if (scanning) (if (s.capturing) "Capturando…" else "Foto") else "Escanear", color = Tisma.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RoundIcon(icon: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color(0xCC3A3A40)).clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) { Text(icon, color = Tisma.Text, fontSize = 20.sp) }
        if (label != "Ajustes") Text(label, color = Tisma.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun StatusLine(s: UiState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val left = if (s.scanning || s.images > 0)
            count(s.images, "foto", "fotos") + " · %.0f MB · ".format(s.storedMb) + count(s.discarded, "descartada", "descartadas")
        else "${s.openedStreams.size} " + if (s.openedStreams.size == 1) "flujo de cámara" else "flujos de cámara"
        Text(left, color = Tisma.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        val (txt, col) = when {
            s.progressText.isNotEmpty() -> s.progressText to Tisma.Warn
            s.busy -> "Procesando…" to Tisma.Warn
            s.scanning -> "Escaneando" to Tisma.PurpleText
            s.ready -> "Listo" to Tisma.Ok
            else -> "Preparado" to Tisma.TextDim
        }
        Text(txt, color = col, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------ ajustes (pantalla aparte)

@Composable
private fun SettingsScreen(
    s: UiState, vm: ScanActions, onShare: (File) -> Unit, onShareText: (String) -> Unit,
    tab: Tab, onTab: (Tab) -> Unit, onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Tisma.Panel).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().background(Tisma.Bar).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onClose() }, contentAlignment = Alignment.Center) {
                Text("←", color = Tisma.Text, fontSize = 22.sp)
            }
            HexLogo(Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Ajustes", color = Tisma.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.weight(1f))
            PassSwitch(s, vm)
        }
        TabRow(tab, onTab)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp)) {
            when (tab) {
                Tab.SCAN -> ScanSettings(s, vm)
                Tab.QUALITY -> QualityTab(s)
                Tab.CAMERAS -> CamerasTab(s, vm, onShareText)
                Tab.CLOUD -> CloudTab(s, vm)
                Tab.LOG -> LogTab(s, vm, onShare)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TabRow(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Tisma.Bar).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (t in Tab.entries) {
            val sel = t == current
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .background(if (sel) Tisma.PurpleDim else Color.Transparent)
                    .clickable { onSelect(t) }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.label, color = if (sel) Color.White else Tisma.TextDim, fontSize = 13.sp)
                Box(Modifier.padding(top = 4.dp).width(36.dp).height(2.dp)
                    .background(if (sel) Tisma.PurpleText else Color.Transparent))
            }
        }
    }
}

/** Logo hexagonal violeta. */
@Composable
private fun HexLogo(modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        fun hex(radius: Float) = Path().apply {
            for (i in 0 until 6) {
                val a = Math.toRadians(60.0 * i - 90)
                val p = Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat())
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(hex(r), Color.White)
        drawPath(hex(r * 0.82f), Tisma.Bar)
        drawPath(hex(r * 0.62f), Tisma.Purple)
        drawPath(hex(r * 0.62f), Color(0xFFD9B8FF), style = Stroke(width = r * 0.08f))
    }
}

@Composable
private fun CameraPreview(vm: ScanActions) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) = vm.onPreviewAvailable(t)
                            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean { vm.onPreviewDestroyed(); return true }
                            // Estabilización por software: cada fotograma se desplaza/gira según el
                            // giróscopo en el instante en que se capturó.
                            private var stabilized = false
                            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {
                                val tr = vm.stabilizePreview(t.timestamp, width, height)
                                if (tr == null) {
                                    if (stabilized) { setTransform(null); stabilized = false }
                                    return
                                }
                                val cx = width / 2f; val cy = height / 2f
                                setTransform(android.graphics.Matrix().apply {
                                    postScale(tr.scale, tr.scale, cx, cy)
                                    postRotate(tr.rotDeg, cx, cy)
                                    postTranslate(tr.dxPx, tr.dyPx)
                                })
                                stabilized = true
                            }
                        }
                    }
                },
            )
}

@Composable
private fun ScanSettings(s: UiState, vm: ScanActions) {
    Section(if (s.pass == ScanPass.FAR) "Pasada lejos" else "Pasada cerca")
    Hint(
        if (s.pass == ScanPass.FAR) "Pieza entera en el encuadre, a 2-3× su tamaño. Rodéala en 3 alturas."
        else "Acércate 2-3× más. Cada disparo apila enfoques: quédate quieto un momento."
    )
    if (s.pass == ScanPass.CLOSE) {
        SettingRow("Profundidad de la pieza") {
            Stepper("${s.partDepthCm} cm", { vm.setPartDepth(s.partDepthCm - 1) }, { vm.setPartDepth(s.partDepthCm + 1) })
        }
    }
    SettingSwitch("Disparo automático (INS)", s.autoTrigger, onChange = vm::setAutoTrigger)
    Hint("Apagado (recomendado): sacas cada foto con el botón grande y la vista previa no se corta. " +
        "Encendido: dispara solo al girar o desplazarte.")
    SettingSwitch("Medir distancia con el enfoque", s.focusDepth, onChange = vm::setFocusDepth)
    SettingSwitch("Velocímetro de cámara (frena la deriva)", s.cameraVelocity, onChange = vm::setCameraVelocity)
    Hint("Cada ${ScanViewModel.AUTO_SWEEP_EVERY} disparos, quieto, barre el enfoque: mapa de profundidad en mm y escala real sin tapete." +
        if (s.focusSweeps > 0) " Barridos hechos: ${s.focusSweeps}." else "")
    SettingSwitch("HDR −2/0/+2 EV en reposo", s.hdr, enabled = s.pass.hdr, onChange = vm::setHdr)

    Section("Calidad de imagen")
    SettingSwitch("Linterna", s.torch, onChange = vm::setTorch)
    SettingSwitch("Bloquear foco/exposición", s.lock3A, enabled = !s.scanning, onChange = vm::setLock3A)
    SettingSwitch("Descartar fotos movidas", s.discardBlurry, onChange = vm::setDiscardBlurry)
    SettingSwitch("Estabilizar vista previa (giróscopo)", s.stabilizePreview, onChange = vm::setStabilizePreview)
    SettingSwitch("Ráfaga anti-movimiento (4 fotos cortas)", s.antiShakeBurst, onChange = vm::setAntiShakeBurst)
    SettingSwitch("Corregir fotos movidas en el móvil", s.deblurMotion, onChange = vm::setDeblurMotion)
    Hint("Con el giro medido durante cada foto se deshace su arrastre al terminar el escaneo (sin servidor).")
    SettingRow("Calidad JPEG") {
        val opts = listOf(85, 90, 92, 95)
        ValueField("${s.jpegQuality}" + if (s.jpegQuality == 92) " · recomendada" else "") {
            vm.setJpegQuality(opts[(opts.indexOf(s.jpegQuality) + 1) % opts.size])
        }
    }
}

@Composable
private fun QualityTab(s: UiState) {
    Section("Cobertura")
    for (p in ScanPass.entries) {
        SettingRow(p.label) { ValueField("%d %%".format(((s.passCoverage[p] ?: 0.0) * 100).toInt())) }
    }
    Section("Fotos")
    SettingRow("Analizadas") { ValueField("${s.analyzed}") }
    SettingRow("Movidas") { ValueField("${s.blurry}") }
    SettingRow("Descartadas (ahorro)") { ValueField("${s.discarded} · %.0f MB".format(s.savedMb)) }
    SettingRow("Guardado") { ValueField("%.0f MB".format(s.storedMb)) }
    SettingRow("Textura reciente") { ValueField("%d %%".format((s.textureRecent * 100).toInt())) }
    if (s.hints.isNotEmpty()) {
        Section("Qué falta")
        s.hints.forEach { Hint("→ $it") }
    }
}

@Composable
private fun CamerasTab(s: UiState, vm: ScanActions, onShareText: (String) -> Unit) {
    if (s.rigWarning.isNotEmpty()) Toast(s.rigWarning, error = true)
    Section("Macro")
    SettingSwitch("Usar la macro en la pasada Cerca", s.useMacro, enabled = !s.scanning, onChange = vm::setUseMacro)
    Hint(if (s.useMacro) "Cerca: vista de la macro (foco fijo ~4 cm) + la principal disparando a la vez."
        else "Cerca usa la cámara principal con apilado de enfoque. Activa la macro para detalle a ~4 cm.")
    Section("Cámara en la vista previa")
    for (p in ScanPass.entries) {
        SettingRow(if (p == ScanPass.FAR) "Lejos" else "Cerca") {
            // Pulsar recorre: Automática -> cada cámara trasera -> Automática.
            val choices = listOf<Pair<String?, String>>(null to "Automática") + s.cameraChoices
            val current = s.previewChoice[p]
            val label = choices.firstOrNull { it.first == current }?.second ?: "Automática"
            ValueField(label) {
                val i = choices.indexOfFirst { it.first == current }
                vm.setPreviewCamera(p, choices[(i + 1) % choices.size].first)
            }
        }
    }
    Hint("Si la automática no acierta (p.ej. no encuentra la macro), elige aquí la cámara.")
    SecondaryButton("Compartir diagnóstico de cámaras", Modifier.fillMaxWidth().padding(top = 6.dp)) {
        onShareText(vm.diagnostics())
    }
    Section("Modo multicámara")
    SettingSwitch(
        if (s.concurrentSupported) "Permitir cámaras concurrentes" else "Concurrentes (no soportado)",
        s.useConcurrent, enabled = s.concurrentSupported && !s.scanning, onChange = vm::setUseConcurrent,
    )
    SettingSwitch("Si no se abren a la vez, por turnos", s.rotateCameras,
        enabled = !s.scanning, onChange = vm::setRotateCameras)
    Section("Objetivos detectados")
    Hint(s.rolesText.ifEmpty { "—" })
    Section("Pasada actual")
    Hint(s.rigText.ifEmpty { "—" })
    Section("Flujos abiertos")
    if (s.openedStreams.isEmpty()) Hint("Ninguno todavía")
    s.openedStreams.forEach { Mono(it) }
    Section("Cámaras detectadas")
    s.cameras.forEach { Mono(it) }
}

@Composable
private fun CloudTab(s: UiState, vm: ScanActions) {
    Section("Tisma Cloud")
    Hint("Los escaneos se procesan en Tisma Cloud (fotogrametría, malla y STL en mm) con tu cuenta de Tisma. " +
        "Tu plan tiene que incluir los escaneos 3D.")
    TextSetting("Servidor", s.cloudUrl, "https://tismaservidores.net", secret = false, onChange = vm::setCloudUrl)
    if (s.cloudKey.isBlank()) {
        var email by remember { mutableStateOf(s.cloudEmail) }
        var password by remember { mutableStateOf("") }
        TextSetting("Correo", email, "tu@correo.com", secret = false) { email = it }
        TextSetting("Contraseña", password, "la de tu cuenta de Tisma", secret = true) { password = it }
        SecondaryButton(if (s.cloudBusy) "Entrando…" else "Entrar", Modifier.fillMaxWidth().padding(top = 6.dp),
            enabled = !s.cloudBusy && email.isNotBlank() && password.isNotEmpty()) {
            vm.cloudLogin(email, password); password = ""
        }
    } else {
        Mono("Sesión iniciada: ${s.cloudEmail}", Tisma.Ok)
        SecondaryButton("Salir", Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !s.cloudBusy) { vm.cloudLogout() }
    }
    Section("Último escaneo")
    when {
        s.lastZip == null -> Hint(sendHint(s.videoMode))
        else -> {
            Mono(s.lastZip.name + " · %.0f MB".format(s.lastZip.length() / 1e6))
            if (s.cloudStatus.isNotEmpty()) Mono(s.cloudStatus, if (s.cloudStatus.startsWith("✗")) Tisma.Warn else Tisma.Text)
            SecondaryButton(if (s.cloudBusy) "Enviando…" else "Enviar a la nube",
                Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !s.cloudBusy && s.cloudKey.isNotBlank()) { vm.sendToCloud() }
        }
    }
    Hint("La subida se reanuda sola si se corta la conexión. Mejor por Wi-Fi: un escaneo ocupa 0,5-2 GB.")
    Section("IA de profundidad en el móvil")
    Hint("Depth Anything 3 Small (Apache-2.0, uso comercial permitido), incluido en la app. Rellena con " +
        "profundidad densa las zonas lisas sin textura del modelo hecho en el móvil; la escala la ponen los " +
        "puntos medidos, así que no inventa medidas. La descarga sólo hace falta para actualizarlo.")
    Mono(s.depthModelText, if (s.depthModelReady) Tisma.Ok else Tisma.TextDim)
    SettingSwitch("Usarla al crear el modelo en el móvil", s.useDepthAi, enabled = s.depthModelReady, onChange = vm::setUseDepthAi)
    TextSetting("Descargar de", s.depthModelUrl, "${s.cloudUrl.trimEnd('/')}/models/da3_small.onnx", secret = false, onChange = vm::setDepthModelUrl)
    SecondaryButton(if (s.depthModelReady) "Actualizar desde el servidor" else "Descargar modelo",
        Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !s.depthModelText.startsWith("Descargando")) { vm.downloadDepthModel() }
}

@Composable
private fun TextSetting(label: String, value: String, placeholder: String, secret: Boolean, onChange: (String) -> Unit) {
    var shown by remember { mutableStateOf(!secret) }
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) }, placeholder = { Text(placeholder, color = Tisma.TextOff) },
        visualTransformation = if (shown) androidx.compose.ui.text.input.VisualTransformation.None
            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
        trailingIcon = if (secret) ({ Text(if (shown) "ocultar" else "ver", color = Tisma.PurpleText, fontSize = 12.sp,
            modifier = Modifier.clickable { shown = !shown }.padding(8.dp)) }) else null,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Tisma.Purple, unfocusedBorderColor = Tisma.Divider,
            focusedTextColor = Tisma.Text, unfocusedTextColor = Tisma.Text,
            focusedLabelColor = Tisma.PurpleText, unfocusedLabelColor = Tisma.TextDim, cursorColor = Tisma.PurpleText,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun LogTab(s: UiState, vm: ScanActions, onShare: (File) -> Unit) {
    AboutSection()
    Section("Diagnóstico")
    Hint("Registro siempre activo: cada segundo guarda los fotogramas que llegan a la pantalla y los que " +
        "entrega la cámara, fallos, bloqueos de la interfaz y memoria, más cada evento (escanear, fotos, " +
        "cambios de la cámara). Si la imagen se congela, compártelo justo después.")
    SecondaryButton("Compartir registro de diagnóstico", Modifier.fillMaxWidth().padding(vertical = 4.dp),
        enabled = s.diagFile != null) {
        com.tisma.scanner.diag.DiagLog.flush(); s.diagFile?.let(onShare)
    }
    Section("Calibración automática")
    Hint("Se hace sola: cada vez que dejas el móvil quieto unos segundos en una postura distinta " +
        "(boca arriba, de pie, de costado…) mejora la corrección del acelerómetro.")
    Mono(s.autoCalText, if (s.accelCalibrated) Tisma.Ok else Tisma.TextDim)
    SecondaryButton("Reiniciar calibración", Modifier.fillMaxWidth().padding(vertical = 4.dp), enabled = !s.scanning) {
        vm.resetAutoCalibration()
    }
    Section("Calibración base")
    Hint("Mide la deriva real de la INS durante un tiempo y deja un registro (CSV + resumen). " +
        "Quieto sobre una mesa: deriva de base y calibración. Caminando: comparación con el GPS.")
    if (s.calibrationText.isNotEmpty()) Mono("En uso: ${s.calibrationText}", Tisma.Ok)
    SettingRow("Duración") {
        val opts = listOf(1, 5, 10)
        ValueField("${s.driftMinutes} min") { if (!s.driftRunning) vm.setDriftMinutes(opts[(opts.indexOf(s.driftMinutes) + 1) % opts.size]) }
    }
    SecondaryButton(
        if (s.driftRunning) "Cancelar" else "Empezar calibración (${s.driftMinutes} min)",
        Modifier.fillMaxWidth().padding(vertical = 4.dp), enabled = !s.scanning,
    ) { if (s.driftRunning) vm.cancelDriftTest() else vm.runDriftTest() }
    if (s.driftProgress.isNotEmpty()) Mono(s.driftProgress, Tisma.PurpleText)
    s.driftReport.forEach { Mono(it) }
    if (s.driftFiles.isNotEmpty()) {
        Hint("Registros guardados (pulsa para compartir):")
        s.driftFiles.take(6).forEach { f ->
            Text("⇪ ${f.name}", color = Tisma.PurpleText, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().clickable { onShare(f) }.padding(vertical = 4.dp))
        }
    }
    Section("Precisión de la INS")
    Hint("Apoya el móvil en una mesa y no lo toques: mide el ruido y la deriva reales de tu IMU.")
    SecondaryButton(
        if (s.imuTestRemaining > 0) "Midiendo… ${s.imuTestRemaining} s" else "Prueba de quietud (60 s)",
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        enabled = s.imuTestRemaining == 0 && !s.scanning,
    ) { vm.runImuStillTest() }
    s.imuReport.forEach { Mono(it) }
    Section("Sensores")
    if (s.sensorStatus.isEmpty()) Hint("Leyendo…")
    s.sensorStatus.forEach { Mono(it, if ("RECHAZADO" in it || "NO EXISTE" in it || " 0 muestras" in it) Tisma.Warn else Tisma.Text) }
    Section("Navegación inercial")
    if (s.flowText.isNotEmpty()) Mono(s.flowText)
    val (x, y, z) = s.position
    val (r, p, yaw) = s.eulerDeg
    Mono("pos  %+.3f %+.3f %+.3f m (±%.2f)".format(x, y, z, s.posStdM))
    Mono("rpy  %+6.1f %+6.1f %+6.1f°".format(r, p, yaw))
    Mono("IMU ${s.imuSamples} muestras · disparos ${s.triggers}")
    Mono(s.gpsText)
    Section("Registro")
    s.log.forEach { Mono(it) }
}

/** Acerca de: versión, licencia (AGPL-3.0), sin garantía, código fuente y licencias de terceros. */
@Composable
private fun AboutSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    var showing by remember { mutableStateOf<String?>(null) }
    Section("Acerca de")
    Text(com.tisma.scanner.About.notice(version), color = Tisma.TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Licencia (AGPL-3.0)", Modifier.weight(1f)) { showing = com.tisma.scanner.About.LICENSE_FILE }
        SecondaryButton("Licencias de terceros", Modifier.weight(1f)) { showing = com.tisma.scanner.About.THIRD_PARTY_FILE }
    }
    showing?.let { file ->
        val text = remember(file) { com.tisma.scanner.About.read(ctx, file) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showing = null },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { showing = null }) { Text("Cerrar") } },
            text = {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()))
            },
        )
    }
}

// ------------------------------------------------------------------ componentes

@Composable
private fun Section(title: String) =
    Text(title, color = Tisma.PurpleText, fontSize = 15.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

@Composable
private fun Hint(text: String) = Text(text, color = Tisma.TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))

@Composable
private fun Mono(text: String, color: Color = Tisma.Text) =
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)

@Composable
private fun SettingRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tisma.Text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
        value()
    }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingRow(label) {
        Switch(
            checked = value, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Tisma.Purple, checkedThumbColor = Color.White,
                uncheckedTrackColor = Tisma.Field, uncheckedThumbColor = Tisma.TextDim,
                uncheckedBorderColor = Tisma.Divider,
            ),
        )
    }
}

/** Campo de valor gris, como "0.20 mm" en TISMA. Pulsable si hay acción. */
@Composable
private fun ValueField(text: String, onClick: (() -> Unit)? = null) {
    var m = Modifier.widthIn(min = 110.dp).clip(RoundedCornerShape(6.dp)).background(Tisma.Field)
    if (onClick != null) m = m.clickable { onClick() }
    Box(m.padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(text + if (onClick != null) "  ▾" else "", color = Tisma.Text, fontSize = 13.sp)
    }
}

@Composable
private fun Stepper(text: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SmallSquare("−", onMinus)
        ValueField(text)
        SmallSquare("+", onPlus)
    }
}

@Composable
private fun SmallSquare(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(Tisma.Field).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Tisma.Text, fontSize = 16.sp) }
}

@Composable
private fun Chip(text: String, color: Color = Tisma.Text) {
    Text(
        text, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xCC3A3A40))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Toast(text: String, error: Boolean = false, ok: Boolean = false) {
    Text(
        text, color = Color.White, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(when { error -> Tisma.ErrorToast; ok -> Color(0xFF2E6B43); else -> Tisma.InfoToast })
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun SecondaryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(Tisma.Field)
            .clickable(enabled = enabled) { onClick() }.padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Tisma.Text else Tisma.TextOff, fontSize = 13.sp) }
}

// ------------------------------------------------------------------ vídeo 360 (pantalla de captura)

/**
 * Captura por vídeo pensada para que se entienda sin leer nada técnico:
 *  - recuadro para encuadrar la pieza; borde verde mientras graba bien, ámbar si va rápido;
 *  - UNA indicación grande arriba ("Sigue hacia la derecha →");
 *  - el progreso alrededor del botón de grabar (la altura actual) y por alturas debajo;
 *  - la pieza en vivo en una tarjeta pequeña; al detener se abre la revisión en grande.
 */
@Composable
private fun VideoCaptureScreen(
    s: UiState, vm: ScanActions, onShare: (File) -> Unit,
    preview: @Composable () -> Unit, onOpenSettings: () -> Unit, openReview: Boolean = false,
) {
    var review by remember { mutableStateOf(openReview) }
    var showCards by remember { mutableStateOf(true) }
    var showPoints by remember { mutableStateOf(true) }
    var showPiece by remember { mutableStateOf(false) }
    var wasScanning by remember { mutableStateOf(s.scanning) }
    LaunchedEffect(s.scanning) {
        if (wasScanning && !s.scanning) review = true
        wasScanning = s.scanning
    }
    // Vibración suave por cada tramo nuevo cubierto.
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val covered = s.coverage.sumOf { r -> r.count { it > 0 } }
    var lastCovered by remember { mutableStateOf(covered) }
    LaunchedEffect(covered) {
        if (s.scanning && covered > lastCovered) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        lastCovered = covered
    }
    val pose by vm.arPose.collectAsState()
    val toneColor = when {
        !s.scanning -> Color.White.copy(alpha = 0.75f)
        s.videoTooFast -> Tisma.Warn
        else -> Tisma.Ok
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).align(Alignment.Center)) {
            preview()
            if (s.scanning || s.arCards.isNotEmpty()) {
                ArOverlay(pose, s.arXyz, s.arQuality, s.arCards, showPoints, showCards, Modifier.fillMaxSize().clipToBounds())
            }
            if (!s.scanning) FramingGuide(toneColor, Modifier.fillMaxSize())
            if (s.scanning) Box(Modifier.fillMaxSize().border(3.dp, toneColor.copy(alpha = 0.85f)))
            // La pieza en vivo, en pequeño, si se pide.
            // La pieza que se va formando: malla cerrada si ya hay (preproceso), si no los trozos en vivo.
            val piece = s.liveMesh?.let { listOf(it) } ?: s.surface
            if (showPiece && piece.isNotEmpty()) {
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(110.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xCC1E1E22))) {
                    PieceView(piece, textured = !s.scanning, modifier = Modifier.fillMaxSize())
                }
            }
        }

        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(topShade).statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                HexLogo(Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text("TISMA", color = Tisma.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                if (!s.scanning) RoundIcon("⚙", "Ajustes", onOpenSettings)
            }
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Tisma.Purple)
            when {
                s.scanning -> InstructionPill(s.instruction ?: Instruction("↻", "Rodea la pieza despacio", Instruction.Tone.INFO))
                !s.busy && s.arCards.isEmpty() -> InstructionPill(Instruction("▢", "Pon la pieza en el recuadro y toca grabar", Instruction.Tone.INFO))
            }
            if (s.precisionBadge.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Chip(s.precisionBadge, if (s.precisionBadge.startsWith("✓")) Tisma.Ok else Tisma.PurpleText)
            }
        }

        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            val toasts = buildList {
                if (s.rigWarning.isNotEmpty()) add(Triple("La cámara principal no está disponible · ver ⚙ Cámaras", true, false))
                if (s.scanning && s.analyzed > 3 && s.textureRecent < ScanViewModel.LOW_TEXTURE)
                    add(Triple("La pieza tiene poco dibujo: ponla sobre el tapete o usa spray mate", true, false))
                if (s.cloudStatus.isNotEmpty() && !s.scanning) add(Triple(s.cloudStatus, s.cloudStatus.startsWith("✗"), s.cloudStatus.startsWith("✓")))
            }.take(2)
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                toasts.forEach { (text, error, ok) -> Toast(text, error = error, ok = ok) }
            }
            if (toasts.isNotEmpty()) Spacer(Modifier.height(8.dp))
            // Barra de capas: fotos, puntos y pieza.
            if (s.scanning || s.arCards.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().background(Color(0xE62C2C31)).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    LayerToggle("▣", "Fotos", showCards) { showCards = !showCards }
                    LayerToggle("⁘", "Puntos", showPoints) { showPoints = !showPoints }
                    LayerToggle("◆", "Pieza", showPiece) { showPiece = !showPiece }
                }
            }
            Column(Modifier.fillMaxWidth().background(Tisma.Bar).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GalleryThumb(s)
                    Spacer(Modifier.width(12.dp))
                    VideoPhotoToggle(video = true, enabled = !s.scanning && !s.busy) { vm.setVideoMode(it) }
                    Spacer(Modifier.weight(1f))
                    RecordButton(s, vm)
                    Spacer(Modifier.weight(1f))
                    NextButton(enabled = !s.scanning && (s.lastZip != null || s.surface.isNotEmpty() || s.busy)) { review = true }
                }
                if (s.scanning) {
                    Spacer(Modifier.height(10.dp))
                    BandBar(s)
                }
                Spacer(Modifier.height(6.dp))
                if (s.scanning) Text(count(s.videoFrames, "fotograma", "fotogramas") + " · ${(s.coveragePct * 100).toInt()} % cubierto" +
                    (if (s.prepText.isNotEmpty()) " · ${s.prepText}" else ""), color = Tisma.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center)
                else StatusLine(s)
            }
        }
        if (review && !s.scanning && (s.surface.isNotEmpty() || s.busy || s.lastZip != null)) ReviewScreen(s, vm, onShare) { review = false }
    }
}

@Composable
private fun LayerToggle(icon: String, label: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(icon, color = if (on) Tisma.Warn else Tisma.TextOff, fontSize = 18.sp)
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (on) Tisma.Text else Tisma.TextOff, fontSize = 12.sp)
    }
}

/** Última foto con el número de fotogramas guardados. */
@Composable
private fun GalleryThumb(s: UiState) {
    val last = s.arCards.lastOrNull()
    val bmp = remember(last?.id) { last?.let { android.graphics.Bitmap.createBitmap(it.thumb, it.w, it.h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() } }
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Tisma.Field), contentAlignment = Alignment.Center) {
        if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text("${s.videoFrames}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Interruptor vídeo (encendido) / fotos (apagado). */
@Composable
private fun VideoPhotoToggle(video: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.width(58.dp).height(30.dp).clip(RoundedCornerShape(15.dp))
        .background(if (video) Tisma.Purple else Tisma.Field).clickable(enabled = enabled) { onChange(!video) }.padding(3.dp)) {
        Box(Modifier.size(24.dp).align(if (video) Alignment.CenterEnd else Alignment.CenterStart).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center) {
            Text(if (video) "▶" else "◉", color = Tisma.Bar, fontSize = 11.sp)
        }
    }
}

@Composable
private fun NextButton(enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(if (enabled) Tisma.Purple else Tisma.Field).clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center) {
        Text("→", color = if (enabled) Color.White else Tisma.TextOff, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

/** Esquinas de encuadre: la pieza debe quedar dentro (la reconstrucción en vivo lo supone). */
@Composable
private fun FramingGuide(color: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val side = size.minDimension * 0.62f
        val l = (size.width - side) / 2; val t = (size.height - side) / 2
        val len = side * 0.16f; val w = 3.dp.toPx()
        val corners = listOf(Offset(l, t) to Offset(1f, 1f), Offset(l + side, t) to Offset(-1f, 1f),
            Offset(l, t + side) to Offset(1f, -1f), Offset(l + side, t + side) to Offset(-1f, -1f))
        for ((p, d) in corners) {
            drawLine(color, p, Offset(p.x + d.x * len, p.y), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, p, Offset(p.x, p.y + d.y * len), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
private fun InstructionPill(ins: Instruction) {
    val bg = when (ins.tone) {
        Instruction.Tone.WARN -> Color(0xE6A86A12)
        Instruction.Tone.DONE -> Color(0xE62E7D45)
        Instruction.Tone.OK -> Color(0xD92C2C31)
        Instruction.Tone.INFO -> Color(0xD92C2C31)
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(28.dp)).background(bg)
        .padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text(ins.icon, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(ins.text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

/** Botón de grabar con el progreso de la altura actual alrededor (tramos como un reloj). */
@Composable
private fun RecordButton(s: UiState, vm: ScanActions) {
    val enabled = s.scanning || !s.busy
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            if (s.scanning) {
                val ringIdx = s.guide?.ring ?: 1
                val counts = s.coverage.getOrNull(ringIdx)
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val n = counts?.size ?: 24
                    val step = 360f / n
                    val stroke = 6.dp.toPx(); val r = size.minDimension / 2 - stroke
                    for (b in 0 until n) {
                        val c = counts?.getOrNull(b) ?: 0
                        val col = if (c >= 2) Tisma.Ok else if (c == 1) Tisma.Warn else Color(0x40FFFFFF)
                        drawArc(col, -90f + b * step + 1.5f, step - 3f, false,
                            Offset(size.width / 2 - r, size.height / 2 - r), androidx.compose.ui.geometry.Size(r * 2, r * 2),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    }
                }
            } else Box(Modifier.size(88.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)))
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Tisma.Bar).padding(4.dp).clip(CircleShape)
                    .background(if (!enabled) Tisma.Field else if (s.scanning) Tisma.ErrorToast else Tisma.Purple)
                    .clickable(enabled = enabled) { if (s.scanning) vm.stopScan() else vm.startScan() },
                contentAlignment = Alignment.Center,
            ) {
                if (s.scanning) Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
                else Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0xFFE53935)))
            }
        }
    }
}

/** Progreso por altura (baja, media, alta, arriba): la actual resaltada. */
@Composable
private fun BandBar(s: UiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((i, counts) in s.coverage.withIndex()) {
            val pct = if (counts.isEmpty()) 0f else counts.count { it > 0 }.toFloat() / counts.size
            val current = i == s.guide?.ring
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ringName(i, s.coverageRings), color = if (current) Color.White else Tisma.TextDim, fontSize = 11.sp,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                Box(Modifier.padding(top = 3.dp).width(56.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(pct).clip(RoundedCornerShape(3.dp))
                        .background(if (pct >= 0.9f) Tisma.Ok else if (current) Tisma.PurpleText else Tisma.Warn))
                }
            }
        }
    }
}

/** Revisión al terminar: la pieza en grande para girarla, y qué hacer con el escaneo. */
@Composable
private fun ReviewScreen(s: UiState, vm: ScanActions, onShare: (File) -> Unit, onClose: () -> Unit) {
    BackHandler { onClose() }
    var textured by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Tisma.Bg)) {
        val shown = (s.localModel ?: s.liveMesh)?.let { listOf(it) } ?: s.surface
        if (shown.isNotEmpty()) {
            PieceView(shown, textured = textured, modifier = Modifier.fillMaxSize().padding(top = 90.dp, bottom = 280.dp), interactive = true)
        } else Text(if (s.busy) "Guardando…" else "No se pudo armar la vista previa (poca textura o sin girar alrededor).",
            color = Tisma.TextDim, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tu pieza", color = Tisma.Text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text(when {
                        s.localModel != null && s.localAi -> "Malla con IA · arrastra para girarla"
                        s.localModel != null -> "Malla rápida · arrastra para girarla"
                        else -> "Vista previa · arrastra para girarla"
                    }, color = Tisma.TextDim, fontSize = 13.sp)
                    // Lo que sigue en segundo plano (puedes salir de la app: hay una notificación).
                    if (s.progressText.isNotEmpty()) Text(s.progressText + " · sigue aunque salgas de la app",
                        color = Tisma.Warn, fontSize = 12.sp)
                }
                RoundIcon("✕", "Cerrar", onClose)
            }
        }
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Tisma.Bar).navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.surface.isNotEmpty()) ModeChips(textured) { textured = it }
            if (s.cloudStatus.isNotEmpty()) Toast(s.cloudStatus, error = s.cloudStatus.startsWith("✗"), ok = s.cloudStatus.startsWith("✓"))
            if (s.cloudProgress >= 0f) LinearProgressIndicator(progress = { s.cloudProgress }, modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Tisma.Purple, trackColor = Tisma.Field)
            val zip = s.lastZip
            // Con la pieza de este escaneo ya bajada, no se vuelve a mandar (otro trabajo, otro escaneo del plan).
            val canSend = zip != null && !s.cloudBusy && !s.busy && s.cloudStl == null
            Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp))
                .background(if (canSend) Tisma.Purple else Tisma.Field)
                .clickable(enabled = canSend) { vm.sendToCloud() }, contentAlignment = Alignment.Center) {
                Text(when { s.progressText.isNotEmpty() -> s.progressText; s.busy -> "Guardando…"; s.cloudBusy -> "Enviando a Tisma Cloud…"
                    s.cloudStl != null -> "✓ Modelo final listo"; else -> "Crear modelo final en Tisma Cloud" },
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            // Sin servidor: el modelo se calcula en el propio móvil (calidad media, escala real de la INS).
            if (s.localModel == null) {
                SecondaryButton(if (s.localBusy) "Creando en el móvil…" else "Crear modelo en el móvil (sin servidor)",
                    Modifier.fillMaxWidth(), enabled = s.surface.isNotEmpty() && !s.localBusy && !s.busy) { vm.buildLocalModel() }
            } else s.localStl?.let { stl ->
                SecondaryButton("Compartir STL del móvil (mm)", Modifier.fillMaxWidth()) { onShare(stl) }
            }
            // La pieza de la nube: «Imprimir» la deja en la cuenta (Mi biblioteca, teléfono y PC); «Publicar», en la
            // biblioteca compartida de Tisma.
            if (s.cloudStl != null && s.cloudJobId != null) {
                var publishing by remember { mutableStateOf(false) }
                if (s.libraryStatus.isNotEmpty()) Toast(s.libraryStatus, error = s.libraryStatus.startsWith("✗"), ok = s.libraryStatus.startsWith("✓"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(if (s.libraryKept) "✓ En Mi biblioteca" else "Imprimir", Modifier.weight(1f),
                        enabled = !s.libraryBusy && !s.libraryKept) { vm.keepInLibrary() }
                    SecondaryButton(if (s.libraryPublished) "✓ Publicada" else "Publicar", Modifier.weight(1f),
                        enabled = !s.libraryBusy && !s.libraryPublished) { publishing = true }
                }
                if (publishing) PublishDialog(s, onCancel = { publishing = false }) { name, author, license, description ->
                    publishing = false
                    vm.publishToLibrary(name, author, license, description)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Compartir", Modifier.weight(1f), enabled = zip != null) { zip?.let(onShare) }
                s.cloudStl?.let { stl -> SecondaryButton("Pieza STL", Modifier.weight(1f)) { onShare(stl) } }
                SecondaryButton("Nuevo escaneo", Modifier.weight(1f)) { onClose() }
            }
        }
    }
}

/** «Publicar»: nombre, autor, licencia y descripción de la pieza para la biblioteca compartida de Tisma. */
@Composable
private fun PublishDialog(s: UiState, onCancel: () -> Unit, onPublish: (String, String, String, String) -> Unit) {
    val licenses = com.tisma.scanner.cloud.LibraryPublish.LICENSES
    var name by remember { mutableStateOf(s.cloudStl?.nameWithoutExtension?.removeSuffix("_pieza") ?: "") }
    var author by remember { mutableStateOf(s.libraryAuthor) }
    var license by remember { mutableStateOf(licenses.first().first) }
    var description by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    val ok = com.tisma.scanner.cloud.LibraryPublish.fieldsOk(name, author, description)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Tisma.Bar,
        title = { Text("Publicar en la biblioteca de Tisma", color = Tisma.Text, fontSize = 17.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Cualquiera podrá verla e imprimirla, con la licencia que elijas.", color = Tisma.TextDim, fontSize = 13.sp)
                TextSetting("Nombre", name, "Engranaje", false) { name = com.tisma.scanner.cloud.LibraryPublish.clip(it, com.tisma.scanner.cloud.LibraryPublish.MAX_NAME) }
                TextSetting("Autor", author, "Tu nombre", false) { author = com.tisma.scanner.cloud.LibraryPublish.clip(it, com.tisma.scanner.cloud.LibraryPublish.MAX_AUTHOR) }
                Box {
                    SecondaryButton("Licencia: " + licenses.first { it.first == license }.second, Modifier.fillMaxWidth()) { choosing = true }
                    androidx.compose.material3.DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        licenses.forEach { (id, label) ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { license = id; choosing = false })
                        }
                    }
                }
                TextSetting("Descripción (opcional)", description, "Medidas, material, para qué sirve…", false) { description = com.tisma.scanner.cloud.LibraryPublish.clip(it, com.tisma.scanner.cloud.LibraryPublish.MAX_DESCRIPTION) }
            }
        },
        confirmButton = {
            Text("Publicar", color = if (ok) Tisma.PurpleText else Tisma.TextOff, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(enabled = ok) { onPublish(name, author, license, description) }.padding(12.dp))
        },
        dismissButton = {
            Text("Cancelar", color = Tisma.TextDim, modifier = Modifier.clickable { onCancel() }.padding(12.dp))
        },
    )
}
