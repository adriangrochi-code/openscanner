package com.tisma.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (cameraGranted.value) ScanScreen(vm, ::share)
                else Text("Se necesita permiso de cámara")
            }
        }
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun share(zip: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Exportar escaneo"))
    }
}
