package com.tisma.scanner.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.HandlerThread
import java.io.BufferedWriter

/**
 * Registra posiciones GNSS (o fused si existe) y las entrega a la INS.
 * Timestamps en elapsedRealtimeNanos para alinearlos con IMU y cámaras.
 *
 * Precisión típica en móvil: 2–5 m al aire libre (algo mejor con L1+L5). Para piezas
 * pequeñas no aporta forma, pero sí georreferencia y limita la deriva de la INS en
 * escaneos grandes (fachadas, vehículos, terreno).
 */
class GpsRecorder(context: Context, private val onFix: (tNs: Long, loc: Location) -> Unit) : LocationListener {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var thread: HandlerThread? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile var last: Location? = null; private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (thread != null) return true
        val t = HandlerThread("gps").apply { start() }
        thread = t
        var any = false
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }
        for (p in providers) {
            if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)) {
                runCatching { lm.requestLocationUpdates(p, 200L, 0f, this, t.looper); any = true }
            }
        }
        if (!any) { t.quitSafely(); thread = null }
        return any
    }

    fun stop() {
        runCatching { lm.removeUpdates(this) }
        thread?.quitSafely(); thread = null
    }

    fun startLogging(w: BufferedWriter) {
        w.write("t_ns,provider,lat,lon,alt_m,h_acc_m,v_acc_m,speed_mps,bearing_deg\n")
        writer = w
    }

    fun stopLogging() {
        val w = writer; writer = null
        synchronized(this) { runCatching { w?.close() } }
    }

    override fun onLocationChanged(loc: Location) {
        last = loc
        val t = loc.elapsedRealtimeNanos
        onFix(t, loc)
        val w = writer ?: return
        synchronized(this) {
            runCatching {
                w.write("$t,${loc.provider},${loc.latitude},${loc.longitude},${loc.altitude},${loc.accuracy}," +
                    "${if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters else ""},${loc.speed},${loc.bearing}\n")
            }
        }
    }

    @Deprecated("API antigua") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
