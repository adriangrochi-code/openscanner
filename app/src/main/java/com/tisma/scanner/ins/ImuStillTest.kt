package com.tisma.scanner.ins

import kotlin.math.sqrt

data class ImuSample(val tNs: Long, val v: Vec3)

/**
 * Resultado de la prueba de quietud.
 * @property freeDriftM deriva de posición medida integrando SIN paradas durante T segundos
 *   (mediana de muchas ventanas): lo que se aleja la INS sola, sin ZUPT, en tu móvil.
 */
data class StillReport(
    val durationS: Double,
    val accelHz: Double,
    val gyroHz: Double,
    val gyroBiasDegS: Vec3,
    val gyroNoiseDegS: Double,
    val accelNoise: Double,
    val gravityError: Double,
    val headingDriftDegPerMin: Double,
    val freeDriftM: List<Pair<Double, Double>>,
    val moved: Boolean,
    /** Sesgo del acelerómetro en la dirección de la gravedad (marco del dispositivo). */
    val accelBias: Vec3 = Vec3.ZERO,
    /** Deriva sin paradas SIN compensar ese sesgo (para comparar). */
    val freeDriftRawM: List<Pair<Double, Double>> = emptyList(),
) {
    fun lines(): List<String> = buildList {
        add("IMU: acelerómetro %.0f Hz · giróscopo %.0f Hz · %.0f s".format(accelHz, gyroHz, durationS))
        add("Giróscopo: ruido %.3f °/s · sesgo %.2f °/s (ya compensado)".format(gyroNoiseDegS, gyroBiasDegS.norm()))
        add("Acelerómetro: ruido %.4f m/s² · error de g %.3f m/s² (ya compensado)".format(accelNoise, gravityError))
        add("Rumbo: deriva %.2f °/min con el sesgo compensado".format(headingDriftDegPerMin))
        // Quieto, un sesgo del acelerómetro es indistinguible de una inclinación y el filtro lo
        // absorbe: esto es la deriva MÍNIMA (ruido). La real en movimiento se mide contra la
        // fotogrametría (tools/ins_accuracy.py).
        if (freeDriftRawM.isNotEmpty()) {
            add("Sin paradas, sin compensar: " + freeDriftRawM.joinToString(" · ") { (t, m) -> "%.0f s → %s".format(t, fmtM(m)) })
        }
        add("Sin paradas, compensado (mínimo): " + freeDriftM.joinToString(" · ") { (t, m) -> "%.0f s → %s".format(t, fmtM(m)) })
        if (moved) add("⚠ El móvil se movió durante la prueba: repítela apoyado en una mesa")
    }

    private fun fmtM(m: Double) = when {
        m < 0.01 -> "%.1f mm".format(m * 1000)
        m < 1.0 -> "%.1f cm".format(m * 100)
        else -> "%.2f m".format(m)
    }
}

/** Analiza muestras con el móvil quieto: ruido, sesgo y deriva real de la INS en este móvil. */
object ImuStillTest {

    fun analyze(accel: List<ImuSample>, gyro: List<ImuSample>, horizons: List<Double> = listOf(1.0, 2.0, 5.0, 10.0)): StillReport? {
        if (accel.size < 50 || gyro.size < 50) return null
        val t0 = maxOf(accel.first().tNs, gyro.first().tNs)
        val t1 = minOf(accel.last().tNs, gyro.last().tNs)
        val dur = (t1 - t0) * 1e-9
        if (dur < 5) return null

        val gMean = mean(gyro.map { it.v })
        val aMean = mean(accel.map { it.v })
        val gStd = rmsStd(gyro.map { it.v }, gMean)
        val aStd = rmsStd(accel.map { it.v }, aMean)
        val moved = aStd > 0.15 || gStd > Math.toRadians(1.0)

        // Deriva libre: StrapdownIns sin ZUPT, con el sesgo de giróscopo calibrado
        // (como quedaría la app tras esta prueba), en ventanas de T segundos.
        val events = (accel.map { Triple(it.tNs, true, it.v) } + gyro.map { Triple(it.tNs, false, it.v) })
            .sortedBy { it.first }
        // Sesgo del acelerómetro observable quieto: la diferencia de módulo con g, en la dirección
        // de la gravedad medida (lo perpendicular es indistinguible de una inclinación).
        val accelBias = aMean.normalized() * (aMean.norm() - MahonyFilter.GRAVITY)
        fun driftCurve(aBias: Vec3) = horizons.filter { it <= dur }.map { T ->
            val step = maxOf(1.0, T / 2)
            val results = mutableListOf<Double>()
            var start = 0.0
            while (start + T <= dur) {
                results += freeRun(events, t0 + (start * 1e9).toLong(), (T * 1e9).toLong(), gMean, aBias).first
                start += step
            }
            T to results.sorted()[results.size / 2]
        }
        val drift = driftCurve(accelBias)
        val driftRaw = driftCurve(Vec3.ZERO)
        // Deriva de rumbo: ángulo girado en la ventana más larga posible / tiempo.
        val (_, angle) = freeRun(events, t0, ((dur - 0.5) * 1e9).toLong(), gMean, accelBias)
        return StillReport(
            durationS = dur,
            accelHz = accel.size / ((accel.last().tNs - accel.first().tNs) * 1e-9),
            gyroHz = gyro.size / ((gyro.last().tNs - gyro.first().tNs) * 1e-9),
            gyroBiasDegS = gMean * Math.toDegrees(1.0),
            gyroNoiseDegS = Math.toDegrees(gStd),
            accelNoise = aStd,
            gravityError = aMean.norm() - MahonyFilter.GRAVITY,
            headingDriftDegPerMin = Math.toDegrees(angle) / (dur - 0.5) * 60,
            freeDriftM = drift,
            moved = moved,
            accelBias = accelBias,
            freeDriftRawM = driftRaw,
        )
    }

    /** Integra sin ZUPT desde `from` durante `lenNs`. Devuelve (desplazamiento m, giro rad). */
    private fun freeRun(events: List<Triple<Long, Boolean, Vec3>>, from: Long, lenNs: Long, bias: Vec3, accelBias: Vec3): Pair<Double, Double> {
        val ins = StrapdownIns(zuptEnabled = false, historyCapacity = 4)
        ins.gyroBiasPrior = bias
        ins.accelBiasPrior = accelBias
        ins.reset()
        var first: InsState? = null
        var last: InsState? = null
        // Búsqueda binaria del inicio: con 5-10 min de datos (cientos de miles de muestras)
        // recorrer desde el principio en cada ventana sería demasiado lento en el móvil.
        var lo = 0; var hi = events.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (events[m].first < from) lo = m + 1 else hi = m }
        for (k in lo until events.size) {
            val (t, isAccel, v) = events[k]
            if (t > from + lenNs) break
            if (isAccel) ins.onAccel(t, v) else ins.onGyro(t, v)
            val s = ins.latest() ?: continue
            if (first == null) first = s
            last = s
        }
        if (first == null || last == null) return 0.0 to 0.0
        return (last.position - first.position).norm() to first.orientation.angleTo(last.orientation)
    }

    private fun mean(v: List<Vec3>) = v.fold(Vec3.ZERO) { a, b -> a + b } * (1.0 / v.size)

    private fun rmsStd(v: List<Vec3>, m: Vec3): Double {
        var s = 0.0
        for (x in v) { val d = x - m; s += d dot d }
        return sqrt(s / v.size / 3)
    }
}
