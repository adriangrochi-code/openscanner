package com.tisma.scanner.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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

private enum class Tab(val label: String) { SCAN("Escanear"), QUALITY("Calidad"), CAMERAS("Cámaras"), LOG("Registro") }

@Composable
fun ScanScreen(vm: ScanViewModel, onShare: (File) -> Unit, onShareText: (String) -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    ScanContent(s, vm, onShare, onShareText = onShareText)
}

/**
 * Pantalla sin estado propio (salvo la pestaña): recibe el estado y las acciones.
 * `preview` permite sustituir la cámara en los tests de captura de pantalla.
 */
@Composable
fun ScanContent(
    s: UiState,
    vm: ScanActions,
    onShare: (File) -> Unit,
    initialTab: Int = 0,
    onShareText: (String) -> Unit = {},
    preview: @Composable () -> Unit = { CameraPreview(vm) },
) {
    var tab by remember { mutableStateOf(Tab.entries[initialTab]) }

    Column(Modifier.fillMaxSize().background(Tisma.Bg).statusBarsPadding().navigationBarsPadding()) {
        TopBar(s, vm)
        TabRow(tab) { tab = it }
        Box(Modifier.fillMaxWidth().weight(1f).background(Tisma.Bg)) {
            Viewport(s, vm, onShare, preview)
        }
        Box(Modifier.fillMaxWidth().height(250.dp).background(Tisma.Panel)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp)) {
                when (tab) {
                    Tab.SCAN -> ScanSettings(s, vm, onShare)
                    Tab.QUALITY -> QualityTab(s)
                    Tab.CAMERAS -> CamerasTab(s, vm, onShareText)
                    Tab.LOG -> LogTab(s, vm)
                }
            }
        }
        StatusBar(s)
    }
}

// ------------------------------------------------------------------ barras

@Composable
private fun TopBar(s: UiState, vm: ScanActions) {
    Row(
        Modifier.fillMaxWidth().background(Tisma.Bar).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HexLogo(Modifier.size(30.dp))
        Spacer(Modifier.width(8.dp))
        Text("TISMA", color = Tisma.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.weight(1f))
        // Pasadas como el grupo "3D printing | Engineering".
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Tisma.Panel).padding(3.dp)) {
            for (p in ScanPass.entries) {
                val sel = s.pass == p
                val pct = s.passCoverage[p]?.let { " ${(it * 100).toInt()}%" } ?: ""
                Column(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (sel) Tisma.PurpleDim else Color.Transparent)
                        .clickable { vm.setPass(p) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        (if (p == ScanPass.FAR) "Lejos" else "Cerca") + pct,
                        color = if (sel) Color.White else Tisma.TextDim, fontSize = 13.sp,
                        fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                    )
                    if (sel) Box(Modifier.padding(top = 3.dp).width(28.dp).height(2.dp).background(Tisma.PurpleText))
                }
            }
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

@Composable
private fun StatusBar(s: UiState) {
    Row(
        Modifier.fillMaxWidth().background(Tisma.Bar).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val left = if (s.scanning || s.images > 0)
            "${s.images} fotos · %.0f MB · %d descartadas".format(s.storedMb, s.discarded)
        else "${s.openedStreams.size} " + if (s.openedStreams.size == 1) "flujo de cámara" else "flujos de cámara"
        Text(left, color = Tisma.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        val (txt, col) = when {
            s.busy -> "Procesando…" to Tisma.Warn
            s.scanning -> "Escaneando" to Tisma.PurpleText
            s.ready -> "Listo" to Tisma.Ok
            else -> "Preparado" to Tisma.TextDim
        }
        Text(txt, color = col, fontSize = 12.sp)
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

// ------------------------------------------------------------------ visor

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
                            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
                        }
                    }
                },
            )
}

@Composable
private fun Viewport(s: UiState, vm: ScanActions, onShare: (File) -> Unit, preview: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Vista previa 3:4 centrada (el sensor en vertical).
        Box(Modifier.fillMaxHeight().aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)) { preview() }
        if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Tisma.Purple)

        // Telemetría arriba a la izquierda.
        // Deja sitio a la cúpula (arriba a la derecha): los datos ópticos van en chips de 2 valores.
        Column(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp, end = 140.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Qué objetivo se ve y cuál dispara a la vez en esta pasada.
            if (s.rigText.isNotEmpty()) Chip(s.rigText.substringBefore(" — "), Tisma.PurpleText)
            s.opticsText.split(" · ").filter { it.isNotBlank() }.chunked(2).forEach { Chip(it.joinToString(" · ")) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip(if (s.stationary) "● quieto" else "○ en movimiento", if (s.stationary) Tisma.Ok else Tisma.Text)
                Chip("ω %.2f".format(s.gyroRate), if (s.gyroRate > 0.6) Tisma.Warn else Tisma.Text)
            }
        }

        // Cúpula de cobertura arriba a la derecha.
        if (s.scanning || s.analyzed > 0) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xB31E1E22)).padding(6.dp)) {
                CoverageDome(s.coverageRings, s.coverage, s.liveAzDeg, s.liveElDeg, size = 116.dp)
            }
        }

        // Avisos abajo a la izquierda, como los toasts de TISMA.
        // Como mucho 2 avisos para no tapar la vista: primero los problemas, luego qué falta.
        val toasts = buildList {
            if (s.rigWarning.isNotEmpty()) add(Triple("Esta pasada no puede usar su cámara · ver pestaña Cámaras", true, false))
            if (s.gyroRate > 0.6 && s.scanning) add(Triple("Demasiado rápido: las fotos saldrán movidas", true, false))
            if (s.scanning && s.analyzed > 0 && s.textureRecent < ScanViewModel.LOW_TEXTURE) {
                add(Triple("Poca textura (%d %%): usa el tapete o spray mate".format((s.textureRecent * 100).toInt()), true, false))
            }
            if (s.readinessText.isNotEmpty() && (s.scanning || s.analyzed > 0)) add(Triple(s.readinessText, false, s.ready))
            if (s.scanning) s.hints.firstOrNull()?.let { add(Triple(it, false, false)) }
        }.take(2)
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp, end = 150.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            toasts.forEach { (text, error, ok) -> Toast(text, error = error, ok = ok) }
        }

        // Botón principal, como "Slice".
        Box(Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
            when {
                !s.scanning && s.lastZip != null && !s.busy -> PrimaryButton("Compartir") { onShare(s.lastZip) }
                !s.scanning -> PrimaryButton("Escanear", enabled = !s.busy) { vm.startScan() }
                else -> PrimaryButton("Detener", color = Tisma.ErrorToast) { vm.stopScan() }
            }
        }
    }
}

// ------------------------------------------------------------------ pestañas

@Composable
private fun ScanSettings(s: UiState, vm: ScanActions, onShare: (File) -> Unit) {
    if (s.scanning) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Foto", Modifier.weight(1f)) { vm.captureNow() }
            SecondaryButton("Barrido foco", Modifier.weight(1f)) { vm.focusSweep() }
        }
    } else if (s.lastZip != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Compartir .zip", Modifier.weight(1f)) { onShare(s.lastZip) }
            SecondaryButton("Nuevo escaneo", Modifier.weight(1f), enabled = !s.busy) { vm.startScan() }
        }
    }
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
    SettingSwitch("HDR −2/0/+2 EV en reposo", s.hdr, enabled = s.pass.hdr, onChange = vm::setHdr)

    Section("Calidad de imagen")
    SettingSwitch("Linterna", s.torch, onChange = vm::setTorch)
    SettingSwitch("Bloquear foco/exposición", s.lock3A, enabled = !s.scanning, onChange = vm::setLock3A)
    SettingSwitch("Descartar fotos movidas", s.discardBlurry, onChange = vm::setDiscardBlurry)
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
private fun LogTab(s: UiState, vm: ScanActions) {
    Section("Precisión de la INS")
    Hint("Apoya el móvil en una mesa y no lo toques: mide el ruido y la deriva reales de tu IMU.")
    SecondaryButton(
        if (s.imuTestRemaining > 0) "Midiendo… ${s.imuTestRemaining} s" else "Prueba de quietud (60 s)",
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        enabled = s.imuTestRemaining == 0 && !s.scanning,
    ) { vm.runImuStillTest() }
    s.imuReport.forEach { Mono(it) }
    Section("Navegación inercial")
    val (x, y, z) = s.position
    val (r, p, yaw) = s.eulerDeg
    Mono("pos  %+.3f %+.3f %+.3f m (±%.2f)".format(x, y, z, s.posStdM))
    Mono("rpy  %+6.1f %+6.1f %+6.1f°".format(r, p, yaw))
    Mono("IMU ${s.imuSamples} muestras · disparos ${s.triggers}")
    Mono(s.gpsText)
    Section("Registro")
    s.log.forEach { Mono(it) }
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
private fun PrimaryButton(label: String, enabled: Boolean = true, color: Color = Tisma.Purple, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (enabled) color else Tisma.Field)
            .clickable(enabled = enabled) { onClick() }.padding(horizontal = 22.dp, vertical = 14.dp),
    ) { Text("◆  $label", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun SecondaryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(Tisma.Field)
            .clickable(enabled = enabled) { onClick() }.padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Tisma.Text else Tisma.TextOff, fontSize = 13.sp) }
}
