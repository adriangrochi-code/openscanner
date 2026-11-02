package com.tisma.scanner.ui

import android.graphics.SurfaceTexture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.captureRoboImage
import com.tisma.scanner.camera.ScanPass
import com.tisma.scanner.quality.CoverageMap
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Genera capturas de la interfaz en app/build/screenshots/ (sin dispositivo). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScanScreenshotTest {

    private object NoActions : ScanActions {
        override fun onPreviewAvailable(texture: SurfaceTexture) {}
        override fun onPreviewDestroyed() {}
        override fun startScan() {}
        override fun stopScan() {}
        override fun captureNow() {}
        override fun focusSweep() {}
        override fun setPass(p: ScanPass) {}
        override fun setPartDepth(cm: Int) {}
        override fun setAutoTrigger(v: Boolean) {}
        override fun setHdr(v: Boolean) {}
        override fun setTorch(v: Boolean) {}
        override fun setLock3A(v: Boolean) {}
        override fun setDiscardBlurry(v: Boolean) {}
        override fun setJpegQuality(q: Int) {}
        override fun setUseConcurrent(v: Boolean) {}
        override fun setRotateCameras(v: Boolean) {}
        override fun setPreviewCamera(pass: ScanPass, id: String?) {}
        override fun diagnostics() = ""
        override fun runImuStillTest() {}
        override fun setStabilizePreview(v: Boolean) {}
        override fun setUseMacro(v: Boolean) {}
        override fun setFocusDepth(v: Boolean) {}
        override fun setCloudUrl(v: String) {}
        override fun setCloudKey(v: String) {}
        override fun sendToCloud() {}
        override fun setAntiShakeBurst(v: Boolean) {}
        override fun stabilizePreview(frameTimestampNs: Long, viewW: Int, viewH: Int) = null
    }

    /** Escena falsa en lugar de la cámara: mesa gris con una pieza violeta. */
    private val fakeCamera: @Composable () -> Unit = {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF4A4A50), Color(0xFF2A2A2E))))
            drawRect(Color(0xFF8B2FE0), Offset(size.width * 0.35f, size.height * 0.42f), Size(size.width * 0.3f, size.width * 0.3f))
        }
    }

    private fun coverage(): List<IntArray> {
        val m = CoverageMap()
        for (az in 0 until 250 step 10) m.add(dir(az.toDouble(), 40.0))
        for (az in 0 until 120 step 15) m.add(dir(az.toDouble(), 40.0))
        for (az in 0 until 160 step 15) m.add(dir(az.toDouble(), 60.0))
        return m.snapshot()
    }

    private fun dir(az: Double, el: Double): com.tisma.scanner.ins.Vec3 {
        val a = Math.toRadians(az); val e = Math.toRadians(el)
        return com.tisma.scanner.ins.Vec3(kotlin.math.cos(e) * kotlin.math.cos(a), kotlin.math.cos(e) * kotlin.math.sin(a), kotlin.math.sin(e))
    }

    private val scanning = UiState(
        scanning = true, images = 214, triggers = 71, analyzed = 210, blurry = 9, discarded = 9,
        savedMb = 31.0, storedMb = 742.0, textureRecent = 0.22, stationary = true, gyroRate = 0.04,
        coverage = coverage(), coveragePct = 0.46, liveAzDeg = 265.0, liveElDeg = 38.0,
        passCoverage = mapOf(ScanPass.FAR to 0.46, ScanPass.CLOSE to 0.0),
        hints = listOf("A 30–50° de altura: falta el sector 255°–360°"),
        readinessText = "Falta (pasada lejos): cobertura 46 % (mín. 75 %)",
        opticsText = "a 24 cm · 0.079 mm/px · nítido 33 mm",
        rigText = "Vista: principal #2 + ultra gran angular #3 — simultáneas (misma cámara lógica, sincronizadas por hardware)",
        rolesText = "principal #2 · ultra gran angular #3 · macro #4",
        openedStreams = listOf("#0 back 80° → 4000x3000"),
        cameras = listOf("#0 back 80°", "#1 front 78°", "#2 back 110°", "#3 back 70°"),
    )

    @Test fun scanning() = shot("scan_tab", scanning, 0)

    @Test fun captureSettings() = shot("settings_capture", scanning, 0, settings = true)

    @Test fun closePassQualityTab() = shot("quality_tab", scanning.copy(
        pass = ScanPass.CLOSE, rigText = "Vista: macro #4 + principal #2 — por turnos (el móvil no las abre a la vez)", opticsText = "a 12 cm · 0.040 mm/px · nítido 6 mm · apilado 6 planos",
        passCoverage = mapOf(ScanPass.FAR to 0.81, ScanPass.CLOSE to 0.34), ready = true,
        readinessText = "✓ Listo para enviar · detalle cerca 34 %", textureRecent = 0.6,
    ), 1, settings = true)

    @Test fun camerasTab() = shot("cameras_tab", scanning.copy(
        scanning = false,
        cameraChoices = listOf("0" to "principal #0 · 108 MP · 72°", "2" to "ultra gran angular #2 · 5 MP · 108°", "3" to "macro #3 · 2 MP · 80°"),
        previewChoice = mapOf(ScanPass.CLOSE to "3"),
        rigWarning = "Lejos y Cerca usan la misma cámara (principal #0): no se detectó la macro. Elige una en la pestaña Cámaras o comparte el diagnóstico.",
    ), 2, settings = true)

    @Test fun closePassCamera() = shot("close_camera", scanning.copy(
        pass = ScanPass.CLOSE, rigText = "Vista: macro #3 + principal #0 — por turnos (el móvil no las abre a la vez)",
        opticsText = "macro foco fijo · a ~4 cm · ≈0.041 mm/px · acércate hasta verla nítida",
        passCoverage = mapOf(ScanPass.FAR to 0.81, ScanPass.CLOSE to 0.34), textureRecent = 0.6,
    ), 0)

    @Test fun cloudTab() = shot("cloud_tab", UiState(cloudKey = "clave-secreta", lastZip = File("scan_20261102_181500.zip"),
        cloudStatus = "Procesando: fotogrametría (COLMAP)", cloudBusy = true), 3, settings = true)

    @Test fun cloudDone() = shot("cloud_done", UiState(lastZip = File("scan.zip"), cloudStl = File("scan_pieza.stl"),
        cloudStatus = "✓ Pieza lista 40.23 × 30.23 × 20.31 mm · cerrada", analyzed = 120, images = 214, ready = true), 0)

    @Test fun idle() = shot("idle", UiState(cameras = scanning.cameras, openedStreams = scanning.openedStreams), 0)

    private fun shot(name: String, state: UiState, tab: Int, settings: Boolean = false) {
        captureRoboImage("build/screenshots/$name.png") {
            TismaTheme { ScanContent(state, NoActions, onShare = {}, initialTab = tab, openSettings = settings, preview = fakeCamera) }
        }
    }
}
