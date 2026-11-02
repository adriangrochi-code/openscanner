package com.tisma.scanner.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.tisma.scanner.ins.StrapdownIns
import com.tisma.scanner.ins.Vec3
import java.io.BufferedWriter
import kotlin.math.sqrt

/**
 * Lee todos los sensores inerciales disponibles a la máxima frecuencia, alimenta la INS
 * y (opcionalmente) guarda las muestras crudas en CSV para post-proceso.
 *
 * Los timestamps de SensorEvent están en la base de SystemClock.elapsedRealtimeNanos(),
 * la misma que usa la cámara cuando SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME, lo que
 * permite sincronizar fotos e IMU sin estimaciones.
 */
class ImuRecorder(context: Context, private val ins: StrapdownIns) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var thread: HandlerThread? = null
    @Volatile private var writer: BufferedWriter? = null

    @Volatile var gyroRate: Double = 0.0; private set
    @Volatile var sampleCount: Long = 0; private set

    /** Tipos de sensor registrados. Los "uncalibrated" incluyen el sesgo estimado por Android. */
    private val types = listOf(
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_PRESSURE,
    )

    fun availableSensors(): List<Sensor> = types.mapNotNull { sm.getDefaultSensor(it) }

    fun start() {
        if (thread != null) return
        val t = HandlerThread("imu", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        thread = t
        val h = Handler(t.looper)
        for (s in availableSensors()) {
            sm.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, h)
        }
    }

    fun stop() {
        sm.unregisterListener(this)
        thread?.quitSafely(); thread = null
    }

    /** Empieza a volcar muestras crudas a CSV (t_ns,sensor,v0,v1,v2,v3,v4,v5). */
    fun startLogging(w: BufferedWriter) {
        w.write("t_ns,sensor,v0,v1,v2,v3,v4,v5\n")
        writer = w
    }

    fun stopLogging() {
        val w = writer; writer = null
        synchronized(this) { w?.flush(); w?.close() }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val t = e.timestamp
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> ins.onAccel(t, Vec3.of(e.values))
            Sensor.TYPE_MAGNETIC_FIELD -> ins.onMag(t, Vec3.of(e.values))
            Sensor.TYPE_GYROSCOPE -> {
                val v = e.values
                gyroRate = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
                ins.onGyro(t, Vec3.of(v))
            }
        }
        sampleCount++
        val w = writer ?: return
        synchronized(this) {
            val sb = StringBuilder(96)
            sb.append(t).append(',').append(name(e.sensor.type))
            for (i in 0 until 6) {
                sb.append(',')
                if (i < e.values.size) sb.append(e.values[i])
            }
            sb.append('\n')
            try { w.write(sb.toString()) } catch (_: Exception) { }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        fun name(type: Int) = when (type) {
            Sensor.TYPE_ACCELEROMETER -> "accel"
            Sensor.TYPE_GYROSCOPE -> "gyro"
            Sensor.TYPE_MAGNETIC_FIELD -> "mag"
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "gyro_uncal"
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "accel_uncal"
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "game_rv"
            Sensor.TYPE_ROTATION_VECTOR -> "rv"
            Sensor.TYPE_PRESSURE -> "pressure"
            else -> "t$type"
        }
    }
}
