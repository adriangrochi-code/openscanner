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

    /** Captura de muestras crudas para la prueba de quietud (null = inactiva). */
    private var stillAccel: MutableList<com.tisma.scanner.ins.ImuSample>? = null
    private var stillGyro: MutableList<com.tisma.scanner.ins.ImuSample>? = null

    @Synchronized fun startStillCapture() { stillAccel = ArrayList(40_000); stillGyro = ArrayList(40_000) }

    /** Termina la captura y devuelve (acelerómetro, giróscopo). */
    @Synchronized fun stopStillCapture(): Pair<List<com.tisma.scanner.ins.ImuSample>, List<com.tisma.scanner.ins.ImuSample>> {
        val r = (stillAccel ?: emptyList<com.tisma.scanner.ins.ImuSample>()) to (stillGyro ?: emptyList())
        stillAccel = null; stillGyro = null
        return r
    }

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

    /** Muestras recibidas por tipo de sensor (para ver qué llega de verdad). */
    private val counts = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicLong>()
    /** Cómo quedó registrado cada sensor: tipo -> "rápido"/"400 Hz"/…/"RECHAZADO". */
    private val registration = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private var lastRateT = 0L
    private val lastRateCounts = HashMap<Int, Long>()
    private val rates = java.util.concurrent.ConcurrentHashMap<Int, Double>()

    /** Los que necesita la INS: a la máxima frecuencia. El resto, a frecuencia moderada. */
    private val essential = setOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD)

    fun start() {
        if (thread != null) return
        val t = HandlerThread("imu", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        thread = t
        val h = Handler(t.looper)
        for (s in availableSensors()) {
            // registerListener puede devolver false (algunas capas del fabricante rechazan la
            // máxima frecuencia o limitan sensores rápidos por app): se reintenta más lento.
            val attempts = if (s.type in essential)
                listOf(SensorManager.SENSOR_DELAY_FASTEST to "máxima", 2_500 to "400 Hz", 5_000 to "200 Hz", SensorManager.SENSOR_DELAY_GAME to "juego")
            else listOf(SensorManager.SENSOR_DELAY_GAME to "juego", SensorManager.SENSOR_DELAY_UI to "UI")
            val ok = attempts.firstOrNull { (delay, _) ->
                runCatching { sm.registerListener(this, s, delay, h) }.getOrDefault(false)
            }
            registration[s.type] = ok?.second ?: "RECHAZADO"
        }
        for (type in essential) if (sm.getDefaultSensor(type) == null) registration[type] = "NO EXISTE"
    }

    /** Estado legible de cada sensor: registro, muestras y frecuencia medida. */
    fun status(): List<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRateT >= 1000) {
            val dt = (now - lastRateT) / 1000.0
            for ((type, c) in counts) {
                val v = c.get()
                if (lastRateT > 0) rates[type] = (v - (lastRateCounts[type] ?: 0L)) / dt
                lastRateCounts[type] = v
            }
            lastRateT = now
        }
        return (registration.keys + counts.keys).toSortedSet().map { type ->
            "%s: %s · %d muestras · %.0f Hz".format(name(type), registration[type] ?: "?",
                counts[type]?.get() ?: 0L, rates[type] ?: 0.0)
        }
    }

    fun count(type: Int): Long = counts[type]?.get() ?: 0L

    /** Lista de sensores del móvil (nombre, fabricante, retardo mínimo), para el diagnóstico. */
    fun inventory(): List<String> = sm.getSensorList(Sensor.TYPE_ALL)
        .filter { it.type in types }
        .map { "${name(it.type)}: ${it.name} (${it.vendor}) minDelay=${it.minDelay}µs maxDelay=${it.maxDelay}µs" }

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
        counts.getOrPut(e.sensor.type) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
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
        if (stillAccel != null) synchronized(this) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> stillAccel?.add(com.tisma.scanner.ins.ImuSample(t, Vec3.of(e.values)))
                Sensor.TYPE_GYROSCOPE -> stillGyro?.add(com.tisma.scanner.ins.ImuSample(t, Vec3.of(e.values)))
            }
        }
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
