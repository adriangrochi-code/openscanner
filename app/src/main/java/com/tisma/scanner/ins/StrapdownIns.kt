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
    private val zuptAccelThreshold: Double = 0.25,   // |‖a‖ - g| y ‖a lineal‖ (m/s²)
    private val zuptGyroThreshold: Double = 0.10,    // ‖ω‖ medio en la ventana (rad/s): admite el pulso de la mano
    private val zuptAccelStd: Double = 0.06,         // desviación de ‖a‖ en la ventana (m/s²)
    private val zuptMinSamples: Int = 40,
    historyCapacity: Int = 20_000,
    /** false = navegación inercial "libre" (sin ZUPT/ZARU): sólo para medir la deriva bruta. */
    private val zuptEnabled: Boolean = true,
) {
    /** Sesgo del giróscopo medido en la prueba de quietud: se aplica en cada reset. */
    @Volatile var gyroBiasPrior: Vec3 = Vec3.ZERO
    /** Sesgo del acelerómetro (marco del dispositivo) medido en la prueba de quietud. */
    @Volatile var accelBiasPrior: Vec3 = Vec3.ZERO
    private var accelBiasBody = Vec3.ZERO
    private val aWin = DoubleArray(WINDOW)
    private val wWin = DoubleArray(WINDOW)
    /** Giro (vector) en la ventana: su media cancela el temblor de la mano para el ZARU. */
    private val gxWin = DoubleArray(WINDOW); private val gyWin = DoubleArray(WINDOW); private val gzWin = DoubleArray(WINDOW)
    private var winPos = 0
    private var winFill = 0

    // El giróscopo de un móvil actual es muy bueno (Honor: 0,006 °/s de ruido, 0,11 °/min de
    // deriva de rumbo): se confía más en él que en el acelerómetro para la actitud (kp bajo).
    // ki bajo: el ZARU ya estima el sesgo del giróscopo; un integral fuerte confundía el sesgo
    // del acelerómetro con sesgo del giróscopo.
    val filter = MahonyFilter(kp = 0.3, ki = 0.004)
    private var lastGyroT = -1L
    private var lastAccel: Vec3? = null
    private var lastMag: Vec3? = null
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
        accelBiasBody = accelBiasPrior; position = Vec3.ZERO; velocity = Vec3.ZERO
        winPos = 0; winFill = 0
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
        if (!filter.isInitialized) filter.initialize(a - accelBiasBody)
    }

    @Synchronized fun onMag(tNs: Long, m: Vec3) { lastMag = m }

    /** El giróscopo marca el ritmo de propagación. */
    @Synchronized fun onGyro(tNs: Long, w: Vec3) {
        val accel = lastAccel ?: return
        if (lastGyroT < 0) { lastGyroT = tNs; return }
        val dt = (tNs - lastGyroT) * 1e-9
        lastGyroT = tNs
        if (dt <= 0 || dt > 0.2) return
        val g0 = Vec3(0.0, 0.0, MahonyFilter.GRAVITY)

        // El error del acelerómetro es del SENSOR: se corrige en el marco del dispositivo, así
        // sigue siendo válido al girar el móvil (en el marco mundo dejaba de serlo al rotar).
        val aCorr = accel - accelBiasBody

        // Ganancia adaptativa: si con la actitud predicha aparece aceleración lineal, no usar el
        // acelerómetro para corregir inclinación (se "comería" el movimiento real). En reposo se
        // usa poco: la actitud la lleva el giróscopo y el acelerómetro estima su propio sesgo.
        val linPred = (filter.q.rotate(aCorr) - g0).norm()
        val accelWeight = (1.0 - linPred / 0.4).coerceIn(0.0, 1.0) * (if (stationary) 0.2 else 1.0)
        filter.update(w, aCorr, lastMag, dt, accelWeight)

        val linWorld = filter.q.rotate(aCorr) - g0

        // Detector de reposo por ventana (~0,1 s): con el móvil en la mano nunca está
        // perfectamente quieto, pero el temblor tiene poca varianza y giro pequeño.
        aWin[winPos] = aCorr.norm(); wWin[winPos] = (w - filter.gyroBias).norm()
        gxWin[winPos] = w.x; gyWin[winPos] = w.y; gzWin[winPos] = w.z
        winPos = (winPos + 1) % WINDOW; if (winFill < WINDOW) winFill++
        val still = winFill == WINDOW && run {
            var m = 0.0; var m2 = 0.0; var wm = 0.0
            for (i in 0 until WINDOW) { m += aWin[i]; m2 += aWin[i] * aWin[i]; wm += wWin[i] }
            m /= WINDOW; wm /= WINDOW
            val std = kotlin.math.sqrt((m2 / WINDOW - m * m).coerceAtLeast(0.0))
            std < zuptAccelStd && wm < zuptGyroThreshold &&
                abs(m - MahonyFilter.GRAVITY) < zuptAccelThreshold &&
                // El módulo solo no detecta empujes suaves y uniformes (√(1²+g²) ≈ g).
                linWorld.norm() < zuptAccelThreshold
        }
        stillCount = if (still) stillCount + 1 else 0
        stationary = zuptEnabled && stillCount >= zuptMinSamples
        if (stationary) {
            velocity = Vec3.ZERO
            // ZARU lento (τ ≈ 2 s): el pulso de la mano (7-10 Hz) se promedia casi del todo.
            // Uno rápido metía el temblor en el sesgo del giróscopo, eso inclinaba la actitud en
            // cada giro posterior y la gravedad "se filtraba" como aceleración (deriva sin fin).
            val wMean = Vec3(gxWin.average(), gyWin.average(), gzWin.average())
            if ((wMean - filter.gyroBias).norm() < ZARU_MAX_RATE) filter.zaru(w, ZARU_ALPHA)
            // En reposo, lo que el acelerómetro mide de más o de menos respecto a la gravedad
            // esperada (con la actitud del giróscopo) es su sesgo, en el marco del dispositivo.
            val expected = filter.q.conj().rotate(g0)
            accelBiasBody = accelBiasBody * 0.99 + (accel - expected) * 0.01
        } else {
            // Modelo de ruido: random walk de aceleración ~0.05 m/s² -> var crece ~ t³.
            positionVariance += (0.05 * 0.05) * dt * (1.0 + velocity.norm() * 10)
            position = position + velocity * dt + linWorld * (0.5 * dt * dt)
            velocity = velocity + linWorld * dt
            // Una mano no mueve el móvil a más de ~2 m/s: evita que un error se dispare.
            val v = velocity.norm()
            if (v > MAX_SPEED) velocity = velocity * (MAX_SPEED / v)
        }
        push(InsState(tNs, filter.q, position, velocity, stationary))
    }

    /** Sesgo del acelerómetro estimado (marco del dispositivo, m/s²). */
    @Synchronized fun accelBias(): Vec3 = accelBiasBody

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

    companion object {
        /** Ventana del detector de reposo (≈0,1 s a 400 Hz). */
        const val WINDOW = 40
        /** Velocidad máxima plausible de un móvil en la mano (m/s). */
        const val MAX_SPEED = 2.0
        /** Sólo se recalibra el giróscopo si el giro medio en reposo es muy pequeño (rad/s). */
        const val ZARU_MAX_RATE = 0.03
        /** Paso del ZARU por muestra a ~400 Hz (τ ≈ 1/(400·0,0012) ≈ 2 s). */
        const val ZARU_ALPHA = 0.0012
    }
}
