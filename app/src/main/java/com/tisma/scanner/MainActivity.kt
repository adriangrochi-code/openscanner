package com.tisma.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tisma.scanner.ui.Tisma
import com.tisma.scanner.ui.TismaTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tisma.scanner.ui.ScanScreen
import com.tisma.scanner.ui.ScanViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private val cameraGranted = mutableStateOf(false)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        cameraGranted.value = res[Manifest.permission.CAMERA] == true || hasCamera()
        val loc = res[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasLocation()
        if (cameraGranted.value) vm.onLocationPermission(loc)
    }

    private val vm: ScanViewModel by viewModels()

    // En segundo plano Android corta o ralentiza los sensores de movimiento: la INS no puede
    // seguir integrando. Se paran al salir y se arrancan (con la INS reiniciada) al volver.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.onAppBackground()
    }

    override fun onStart() {
        super.onStart()
        vm.onAppForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashLog()
        // Barras del sistema oscuras, como la barra superior de TISMA.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF1E1E22.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF1E1E22.toInt()),
        )
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraGranted.value = hasCamera()
        // Se piden juntos los que falten (la notificación es para ver el progreso del procesado
        // en segundo plano, Android 13+).
        val wanted = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
            (if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        if (cameraGranted.value) vm.onLocationPermission(hasLocation())
        setContent {
            TismaTheme {
                if (cameraGranted.value) ScanScreen(vm, ::share, ::shareText)
                else Box(Modifier.fillMaxSize().background(Tisma.Bg).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Tisma Scanner necesita permiso de cámara para escanear.", color = Tisma.Text)
                }
            }
        }
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Tisma Scanner · diagnóstico de cámaras")
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Compartir diagnóstico"))
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = when (file.extension.lowercase()) {
                "stl" -> "model/stl"
                "zip" -> "application/zip"
                "csv" -> "text/csv"
                "txt" -> "text/plain"
                "json" -> "application/json"
                else -> "application/octet-stream"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Exportar escaneo"))
    }

    /**
     * Si la app se cierra por un error, el error completo queda en el registro de diagnóstico y en
     * scans/diagnostico/ultimo_cierre.txt (para poder compartirlo y saber qué pasó).
     */
    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(java.io.File(getExternalFilesDir(null), "scans/diagnostico"), prev))
    }

    private class CrashLogger(val dir: java.io.File, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val text = "Hilo ${t.name}\n" + android.util.Log.getStackTraceString(e)
                com.tisma.scanner.diag.DiagLog.event("cierre", text)
                com.tisma.scanner.diag.DiagLog.flush()
                dir.mkdirs()
                java.io.File(dir, "ultimo_cierre.txt").writeText(java.util.Date().toString() + "\n" + text)
            }
            prev?.uncaughtException(t, e)
        }
    }
}
