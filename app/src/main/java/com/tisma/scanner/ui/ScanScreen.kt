package com.tisma.scanner.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

@Composable
fun ScanScreen(vm: ScanViewModel, onShare: (File) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState())
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
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
            InsOverlay(s, Modifier.align(Alignment.TopStart))
            if (s.scanning || s.analyzed > 0) {
                CoverageDome(
                    s.coverageRings, s.coverage, s.liveAzDeg, s.liveElDeg,
                    Modifier.align(Alignment.BottomEnd).padding(8.dp),
                )
            }
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        }

        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!s.scanning) {
                    Button(onClick = vm::startScan, enabled = !s.busy) { Text("Iniciar escaneo") }
                } else {
                    Button(
                        onClick = vm::stopScan,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Detener") }
                    OutlinedButton(onClick = vm::captureNow) { Text("Foto") }
                    OutlinedButton(onClick = vm::focusSweep) { Text("Barrido foco") }
                }
                s.lastZip?.let { zip -> OutlinedButton(onClick = { onShare(zip) }) { Text("Compartir") } }
            }
            if (s.scanning || s.analyzed > 0) QualityPanel(s)
            Spacer(Modifier.padding(4.dp))
            Toggle("Disparo automático guiado por INS", s.autoTrigger, true, vm::setAutoTrigger)
            Toggle("HDR (horquillado −2/0/+2 EV en reposo)", s.hdr, true, vm::setHdr)
            Toggle("Linterna (luz uniforme)", s.torch, true, vm::setTorch)
            Toggle("Bloquear foco/exposición/balance al escanear", s.lock3A, !s.scanning, vm::setLock3A)
            Toggle(
                if (s.concurrentSupported) "Cámaras concurrentes (frontal + trasera)" else "Cámaras concurrentes (no soportado)",
                s.useConcurrent, s.concurrentSupported && !s.scanning, vm::setUseConcurrent,
            )
            Toggle("Rotar por todas las cámaras (${s.rotationIds.size}) en cada disparo", s.rotateCameras, !s.scanning, vm::setRotateCameras)

            Section("Flujos abiertos") { s.openedStreams.forEach { Mono(it) } }
            Section("Cámaras detectadas") { s.cameras.forEach { Mono(it) } }
            Section("Registro") { s.log.forEach { Mono(it) } }
        }
    }
}

@Composable
private fun QualityPanel(s: UiState) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            s.readinessText.ifEmpty { "Analizando fotos…" },
            color = if (s.ready) Color(0xFF7CFC00) else Color(0xFFFFB74D),
            fontSize = 15.sp,
        )
        Mono("Cobertura %d %% · fotos analizadas %d · movidas %d".format((s.coveragePct * 100).toInt(), s.analyzed, s.blurry))
        if (s.textureRecent < ScanViewModel.LOW_TEXTURE) {
            Mono("⚠ Poca textura (%d %%): usa el tapete o spray mate".format((s.textureRecent * 100).toInt()), Color(0xFFFF8A65))
        }
        s.hints.forEach { Mono("→ $it", Color(0xFFB0BEC5)) }
    }
}

@Composable
private fun InsOverlay(s: UiState, modifier: Modifier) {
    val (x, y, z) = s.position
    val (r, p, yaw) = s.eulerDeg
    Column(modifier.padding(8.dp).background(Color(0x99000000)).padding(8.dp)) {
        Mono("pos  %+.3f %+.3f %+.3f m  (±%.2f)".format(x, y, z, s.posStdM))
        Mono("rpy  %+6.1f %+6.1f %+6.1f°".format(r, p, yaw))
        Mono("ω    %.2f rad/s %s".format(s.gyroRate, if (s.gyroRate > 0.6) "¡DEMASIADO RÁPIDO!" else ""))
        Mono(if (s.stationary) "● QUIETO (ZUPT)" else "○ en movimiento", if (s.stationary) Color(0xFF7CFC00) else Color.White)
        Mono(s.gpsText)
        Mono("IMU ${s.imuSamples}  disparos ${s.triggers}  imágenes ${s.images}")
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, color = Color(0xFF80CBC4), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    content()
}

@Composable
private fun Mono(text: String, color: Color = Color.White) =
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
