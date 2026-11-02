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
        val loc = res[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (cameraGranted.value) vm.onLocationPermission(loc)
    }

    private val vm: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Barras del sistema oscuras, como la barra superior de TISMA.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF1E1E22.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF1E1E22.toInt()),
        )
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraGranted.value = hasCamera()
        if (!cameraGranted.value || !hasLocation()) {
            permissions.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
        }
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
}
