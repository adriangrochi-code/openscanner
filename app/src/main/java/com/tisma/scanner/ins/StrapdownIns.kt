package com.tisma.scanner.ins

import kotlin.math.abs

/** Estado de navegación en un instante. Posición/velocidad en marco mundo (m, m/s). */
data class InsState(
    val timestampNs: Long,
    val orientation: Quat,
    val position: Vec3,
    val velocity: Vec3,
    val stationary: Boolean,
)

/**
 * Sistema de navegación inercial "strapdown" con:
 *  - Orientación por filtro de Mahony.
 *  - Doble integración de la aceleración lineal (sin gravedad).
 *  - ZUPT (Zero-velocity UPdaTe): cuando se detecta reposo, la velocidad se fuerza a 0 y
 *    se estima el sesgo del acelerómetro. Esto acota la deriva, que de otro modo crece ~t³.
 *
 * Importante: con sensores MEMS de móvil la posición sólo es fiable unos segundos entre
 * paradas. Por eso aquí se usa como *prior* (pose inicial y escala métrica) para la
 * fotogrametría, no como sustituto de ella.
 */
class StrapdownIns(
    private val zuptAccelThreshold: Double = 0.25,   // |‖a‖ - g| (m/s²)
    private val zuptGyroThreshold: Double = 0.06,    // ‖ω‖ (rad/s)
    private val zuptMinSamples: Int = 40,
    historyCapacity: Int = 20_000,
    /** false = navegación inercial "libre" (sin ZUPT/ZARU): sólo para medir la deriva bruta. */
    private val zuptEnabled: Boolean = true,
) {
    /** Sesgo del giróscopo medido en la prueba de quietud: se aplica en cada reset. */
    @Volatile var gyroBiasPrior: Vec3 = Vec3.ZERO
    val filter = MahonyFilter()
    private var lastGyroT = -1L
    private var lastAccel: Vec3? = null
    private var lastMag: Vec3? = null
    private var accelBiasWorld = Vec3.ZERO
    private var position = Vec3.ZERO
    private var velocity = Vec3.ZERO
    private var stillCount = 0
    private var stationary = false

    /** Varianza (m²) de la posición INS; crece con el tiempo desde el último ZUPT/GNSS. */
    var positionVariance = 0.0
        private set
    var geo: GeoReference? = null
        private set
    /** Nº de fixes GNSS recibidos (el primero sólo fija el origen ENU). */
    private var gnssUpdates = 0

    private val history = ArrayDeque<InsState>()
    private val capacity = historyCapacity

    @Synchronized fun reset() {
        filter.reset(gyroBiasPrior); lastGyroT = -1; lastAccel = null; lastMag = null
        accelBiasWorld = Vec3.ZERO; position = Vec3.ZERO; velocity = Vec3.ZERO
        stillCount = 0; stationary = false; history.clear()
        positionVariance = 0.0; geo = null; gnssUpdates = 0
    }

    /**
     * Corrección GNSS débilmente acoplada (actualización de Kalman escalar por eje).
     * El marco INS tiene X≈norte magnético, Y≈oeste, Z arriba (ver MahonyFilter), así que
     * ENU -> INS: (x, y, z) = (norte, -este, arriba).
     *
     * La ganancia K = P / (P + R) hace que, en un escaneo corto de una pieza (P pequeño,
     * pocos mm), un GNSS de ±3 m prácticamente no mueva la solución; en recorridos largos
     * (P grande) el GNSS domina y frena la deriva.
     */
    @Synchronized fun onGnss(lat: Double, lon: Double, alt: Double, horizAccM: Double, vertAccM: Double?) {
        val g = geo ?: GeoReference(lat, lon, alt).also {
            geo = it
            // Anclar el origen ENU a la posición INS actual.
            originOffset = position
        }
        val enu = g.toEnu(lat, lon, alt)
        val meas = Vec3(enu.y, -enu.x, enu.z) + originOffset
        val rH = horizAccM * horizAccM
        val rV = (vertAccM ?: (horizAccM * 1.5)).let { it * it }
        if (gnssUpdates++ == 0) return
        val kH = positionVariance / (positionVariance + rH)
        val kV = positionVariance / (positionVariance + rV)
        val innov = meas - position
        position = position + Vec3(innov.x * kH, innov.y * kH, innov.z * kV)
        positionVariance *= (1 - kH)
    }

    private var originOffset = Vec3.ZERO

    @Synchronized fun onAccel(tNs: Long, a: Vec3) {
        lastAccel = a
        if (!filter.isInitialized) filter.initialize(a)
    }

    @Synchronized fun onMag(tNs: Long, m: Vec3) { lastMag = m }

    /** El giróscopo marca el ritmo de propagación. */
    @Synchronized fun onGyro(tNs: Long, w: Vec3) {
        val accel = lastAccel ?: return
        if (lastGyroT < 0) { lastGyroT = tNs; return }
        val dt = (tNs - lastGyroT) * 1e-9
        lastGyroT = tNs
        if (dt <= 0 || dt > 0.2) return

        // Ganancia adaptativa: si con la actitud predicha aparece aceleración lineal, no usar el
        // acelerómetro para corregir inclinación (se "comería" el movimiento real).
        val linPred = (filter.q.rotate(accel) - Vec3(0.0, 0.0, MahonyFilter.GRAVITY) - accelBiasWorld).norm()
        val accelWeight = (1.0 - linPred / 0.4).coerceIn(0.0, 1.0)
        filter.update(w, accel, lastMag, dt, accelWeight)

        val linWorld = filter.q.rotate(accel) - Vec3(0.0, 0.0, MahonyFilter.GRAVITY)
        // Reposo = módulo ≈ g, sin giro Y sin aceleración lineal en el marco mundo
        // (el módulo solo no detecta empujes horizontales suaves: √(1²+g²) ≈ g).
        val still = abs(accel.norm() - MahonyFilter.GRAVITY) < zuptAccelThreshold &&
            (w - filter.gyroBias).norm() < zuptGyroThreshold &&
            (linWorld - accelBiasWorld).norm() < zuptAccelThreshold
        stillCount = if (still) stillCount + 1 else 0
        stationary = zuptEnabled && stillCount >= zuptMinSamples
        if (stationary) {
            // En reposo toda aceleración residual es sesgo: filtro paso bajo.
            accelBiasWorld = accelBiasWorld * 0.98 + linWorld * 0.02
            velocity = Vec3.ZERO
            filter.zaru(w, 0.01)
        } else {
            // Modelo de ruido: random walk de aceleración ~0.05 m/s² -> var crece ~ t³.
            positionVariance += (0.05 * 0.05) * dt * (1.0 + velocity.norm() * 10)
            val a = linWorld - accelBiasWorld
            position = position + velocity * dt + a * (0.5 * dt * dt)
            velocity = velocity + a * dt
        }
        push(InsState(tNs, filter.q, position, velocity, stationary))
    }

    private fun push(s: InsState) {
        history.addLast(s)
        while (history.size > capacity) history.removeFirst()
    }

    @Synchronized fun latest(): InsState? = history.lastOrNull()

    /** Estado interpolado en el instante t (p.ej. el timestamp de un fotograma). */
    @Synchronized fun stateAt(tNs: Long): InsState? {
        if (history.isEmpty()) return null
        if (tNs <= history.first().timestampNs) return history.first()
        if (tNs >= history.last().timestampNs) return history.last()
        var lo = 0; var hi = history.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (history[mid].timestampNs <= tNs) lo = mid else hi = mid
        }
        val a = history[lo]; val b = history[hi]
        val f = (tNs - a.timestampNs).toDouble() / (b.timestampNs - a.timestampNs).toDouble()
        return InsState(
            tNs,
            a.orientation.slerp(b.orientation, f),
            a.position + (b.position - a.position) * f,
            a.velocity + (b.velocity - a.velocity) * f,
            a.stationary && b.stationary,
        )
    }

    /** Velocidad angular actual (sin sesgo) para descartar fotos movidas. */
    @Synchronized fun isStationary() = stationary
}
