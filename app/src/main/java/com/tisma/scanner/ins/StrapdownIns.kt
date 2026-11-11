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
 * Sistema de navegación inercial "strapdown":
 *  - Orientación: filtro de Mahony (brújula sólo para el rumbo) + ZARU en reposo.
 *  - Traslación: filtro de Kalman ([TranslationKalman]) con posición, velocidad y sesgo del
 *    acelerómetro en 3 ejes, corregido por ZUPT (reposo), velocidad de la cámara y GNSS.
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
    /** Posición, velocidad y sesgo del acelerómetro (Kalman). */
    val kf = TranslationKalman()
    /** Odometría visual-inercial (MSCKF) sobre el mismo Kalman: puntos de la cámara + IMU. */
    private val vo = VisualOdometry(kf)
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
    /** Las dos últimas muestras del acelerómetro: se interpola entre ellas al instante del giróscopo. */
    private var prevAccelT = -1L
    private var prevAccel: Vec3? = null
    private var lastAccelT = -1L
    private var lastAccel: Vec3? = null
    /** Muestras del giróscopo esperando a que llegue la del acelerómetro posterior (≈2,5 ms). */
    private val pendingGyro = ArrayDeque<Pair<Long, Vec3>>()
    private var lastMag: Vec3? = null
    private var stillCount = 0
    private var stationary = false

    /** Varianza (m²) de la posición INS, la que estima el Kalman. */
    val positionVariance get() = synchronized(this) { kf.positionVariance }
    var geo: GeoReference? = null
        private set
    /** Nº de fixes GNSS recibidos (el primero sólo fija el origen ENU). */
    private var gnssUpdates = 0

    private val history = ArrayDeque<InsState>()
    private val capacity = historyCapacity

    /**
     * Cambia en cada reset(): la INS empieza otra vez desde el origen, en otro marco. Una pose pedida para una foto
     * de antes del reset (la cola de escritura sigue con las últimas fotos del escaneo) no es de su marco.
     */
    @Volatile var epoch = 0L; private set

    @Synchronized fun reset() {
        epoch++
        filter.reset(gyroBiasPrior); lastGyroT = -1; lastMag = null
        prevAccelT = -1; prevAccel = null; lastAccelT = -1; lastAccel = null; pendingGyro.clear()
        kf.reset(accelBiasPrior); vo.reset(); gaps = 0
        winPos = 0; winFill = 0
        stillCount = 0; stationary = false; history.clear()
        geo = null; gnssUpdates = 0
    }

    /**
     * Corrección GNSS débilmente acoplada (actualización del Kalman por eje).
     * El marco INS tiene X≈norte magnético, Y≈oeste, Z arriba (ver MahonyFilter), así que
     * ENU -> INS: (x, y, z) = (norte, -este, arriba).
     *
     * La ganancia K = P / (P + R) hace que, en un escaneo corto de una pieza (P pequeño,
     * pocos mm), un GNSS de ±3 m prácticamente no mueva la solución; en recorridos largos
     * (P grande) el GNSS domina y frena la deriva.
     */
    @Synchronized fun onGnss(lat: Double, lon: Double, alt: Double, horizAccM: Double, vertAccM: Double?,
                             speedMps: Double? = null, bearingDeg: Double? = null, speedAccMps: Double? = null) {
        val g = geo ?: GeoReference(lat, lon, alt).also {
            geo = it
            // Anclar el origen ENU a la posición INS actual.
            originOffset = kf.p
        }
        val enu = g.toEnu(lat, lon, alt)
        val meas = enuToIns(enu.x, enu.y, enu.z) + originOffset
        if (gnssUpdates++ == 0) return
        kf.positionAxis(0, meas.x, horizAccM)
        kf.positionAxis(1, meas.y, horizAccM)
        kf.positionAxis(2, meas.z, vertAccM ?: (horizAccM * 1.5))

        // Velocidad Doppler del GNSS: mucho más precisa (~0,1-0,3 m/s) que la posición (±3-10 m).
        // Frena la deriva de velocidad y, con ella, la de posición entre fixes.
        if (speedMps == null || stationary || horizAccM > GNSS_MAX_ACC_FOR_VELOCITY) return
        val sigma = maxOf(speedAccMps ?: DEFAULT_SPEED_ACC, 0.05)
        val vIns = if (speedMps < 0.5 || bearingDeg == null) {
            // Casi parado: el rumbo GNSS no significa nada; sólo "velocidad horizontal ≈ 0".
            Vec3.ZERO
        } else {
            val b = Math.toRadians(bearingDeg)
            enuToIns(speedMps * kotlin.math.sin(b), speedMps * kotlin.math.cos(b), 0.0)
        }
        val s = if (speedMps < 0.5) maxOf(sigma, speedMps) else sigma
        kf.velocityAlong(Vec3(1.0, 0.0, 0.0), vIns.x, s)
        kf.velocityAlong(Vec3(0.0, 1.0, 0.0), vIns.y, s)
    }

    /**
     * Declinación magnética local (grados, positiva al este; GeomagneticField de Android).
     * El marco INS tiene X hacia el norte MAGNÉTICO (lo fija la brújula), y el GNSS da norte
     * VERDADERO: en Uruguay difieren ~10°, y sin corregirlo cada corrección GNSS empujaba la
     * INS 10° de lado.
     */
    @Volatile var declinationDeg = 0.0

    /**
     * Este/norte/arriba (norte verdadero) -> marco INS (X = norte magnético, Y = oeste, Z arriba).
     * El norte magnético está `declinationDeg` al este del verdadero.
     */
    fun enuToIns(e: Double, n: Double, u: Double): Vec3 {
        val d = Math.toRadians(declinationDeg)
        val nMag = n * kotlin.math.cos(d) + e * kotlin.math.sin(d)
        val eMag = e * kotlin.math.cos(d) - n * kotlin.math.sin(d)
        return Vec3(nMag, -eMag, u)
    }

    /**
     * Velocidad medida por la cámara (flujo óptico + distancia del enfoque): componente a lo
     * largo de `dirWorld` (unitario). Sólo se usa en movimiento: en reposo manda el ZUPT.
     */
    @Synchronized fun onVelocity(dirWorld: Vec3, value: Double, sigma: Double) {
        if (!stationary) kf.velocityAlong(dirWorld, value, sigma)
    }

    private var originOffset = Vec3.ZERO

    /**
     * Fotograma clave de la cámara para la odometría visual-inercial: puntos seguidos (id, u, v en
     * px de una imagen con focal `f` y centro cx, cy) y los ejes de la cámara en ese instante.
     * Devuelve la posición de la cámara en ese fotograma, corregida (null si aún no hay estado).
     */
    @Synchronized fun onVisualKeyframe(tNs: Long, xAxis: Vec3, yAxis: Vec3, zAxis: Vec3,
                                       obs: List<Triple<Int, Double, Double>>, f: Double, cx: Double, cy: Double): Vec3? {
        if (lastGyroT < 0) return null
        val dtBack = ((lastGyroT - tNs) * 1e-9).coerceIn(0.0, 1.0)
        return vo.onKeyframe(tNs, dtBack, xAxis, yAxis, zAxis, obs, f, cx, cy)
    }

    /** Puntos usados en la última actualización visual (para el registro). */
    val visualFeaturesUsed get() = vo.lastFeaturesUsed

    /**
     * Calibración de 6 caras del acelerómetro (sesgo + escala por eje). Se aplica a cada muestra
     * antes de todo lo demás; el Kalman sigue estimando el pequeño error que quede.
     */
    @Volatile var accelCalibration: AccelCalibration.Result? = null

    @Synchronized fun onAccel(tNs: Long, raw: Vec3) {
        val a = accelCalibration?.apply(raw) ?: raw
        if (lastAccel != null && tNs <= lastAccelT) return       // duplicada o desordenada
        prevAccelT = lastAccelT; prevAccel = lastAccel
        lastAccelT = tNs; lastAccel = a
        if (!filter.isInitialized) filter.initialize(a - kf.b)
        drainGyro()
    }

    /**
     * Acelerómetro en el instante exacto `t` (interpolación lineal entre las dos muestras que lo
     * rodean). Los dos sensores llegan en instantes distintos: usar "la última muestra" desfasaba
     * la aceleración hasta medio periodo, un error que en movimiento se integra dos veces.
     */
    private fun accelAt(t: Long): Vec3? {
        val a1 = lastAccel ?: return null
        val a0 = prevAccel ?: return a1
        if (t >= lastAccelT) return a1
        if (t <= prevAccelT) return a0
        val f = (t - prevAccelT).toDouble() / (lastAccelT - prevAccelT).toDouble()
        return a0 + (a1 - a0) * f
    }

    /** Procesa los giros cuyo instante ya está cubierto por el acelerómetro. */
    private fun drainGyro() {
        while (pendingGyro.isNotEmpty()) {
            val (t, w) = pendingGyro.first()
            // Si el acelerómetro se retrasa demasiado, no esperar más: se usa la última muestra.
            if (lastAccel == null || (t > lastAccelT && t - lastAccelT < MAX_ACCEL_WAIT_NS)) return
            pendingGyro.removeFirst()
            propagate(t, w, accelAt(t)!!)
        }
    }

    @Synchronized fun onMag(tNs: Long, m: Vec3) {
        lastMag = m
        // Primer campo válido: rumbo inicial de la brújula, sin convergencia lenta.
        if (filter.isInitialized && !filter.yawAligned) filter.alignYaw(m)
    }

    /** Campo magnético esperado en el lugar (µT, inclinación en grados), del modelo mundial. */
    fun setExpectedMagneticField(fieldUt: Double, inclinationDeg: Double) {
        filter.expectedFieldUt = fieldUt
        filter.expectedInclinationDeg = inclinationDeg
    }

    /** El giróscopo marca el ritmo de propagación (con un retraso de una muestra del acelerómetro). */
    @Synchronized fun onGyro(tNs: Long, w: Vec3) {
        if (lastAccel == null) return
        pendingGyro.addLast(tNs to w)
        while (pendingGyro.size > 64) pendingGyro.removeFirst()
        drainGyro()
    }

    private fun propagate(tNs: Long, wNow: Vec3, accel: Vec3) {
        if (lastGyroT < 0 || tNs <= lastGyroT) { lastGyroT = maxOf(lastGyroT, tNs); return }
        val dt = (tNs - lastGyroT) * 1e-9
        lastGyroT = tNs
        // El giróscopo del móvil ya entrega el giro MEDIO del intervalo anterior (filtro interno):
        // promediar dos muestras añadiría medio periodo de retraso.
        val w = wNow
        if (dt > MAX_GAP_S) {
            // Hueco en los sensores (segundo plano, sistema ocupado): no se integra a ciegas.
            // La velocidad pasa a ser desconocida hasta el próximo reposo (ZUPT) o medida.
            kf.velocityUnknown(GAP_SPEED_STD)
            stillCount = 0; stationary = false; winFill = 0
            gaps++
            return
        }
        val g0 = Vec3(0.0, 0.0, MahonyFilter.GRAVITY)

        // El error del acelerómetro es del SENSOR: se corrige en el marco del dispositivo, así
        // sigue siendo válido al girar el móvil (en el marco mundo dejaba de serlo al rotar).
        val aCorr = accel - kf.b

        // Ganancia adaptativa: si con la actitud predicha aparece aceleración lineal, no usar el
        // acelerómetro para corregir inclinación (se "comería" el movimiento real). En reposo se
        // usa poco: la actitud la lleva el giróscopo y el acelerómetro estima su propio sesgo.
        val linPred = (filter.q.rotate(aCorr) - g0).norm()
        val accelWeight = (1.0 - linPred / 0.4).coerceIn(0.0, 1.0) * (if (stationary) 0.2 else 1.0)
        filter.update(w, aCorr, lastMag, dt, accelWeight)

        val linWorld = filter.q.rotate(aCorr) - g0

        // Detector de reposo por ventana (~0,1 s): con el móvil en la mano nunca está
        // perfectamente quieto, pero el temblor tiene poca varianza y giro pequeño.
        aWin[winPos] = aCorr.norm(); wWin[winPos] = (wNow - filter.gyroBias).norm()
        gxWin[winPos] = wNow.x; gyWin[winPos] = wNow.y; gzWin[winPos] = wNow.z
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
            // ZUPT: velocidad nula como medida del Kalman (corrige también posición y sesgo).
            kf.predict(filter.q, accel, dt, MAX_SPEED)
            kf.zeroVelocity(ZUPT_SIGMA)
            // ZARU lento (τ ≈ 2 s): el pulso de la mano (7-10 Hz) se promedia casi del todo.
            // Uno rápido metía el temblor en el sesgo del giróscopo, eso inclinaba la actitud en
            // cada giro posterior y la gravedad "se filtraba" como aceleración (deriva sin fin).
            val wMean = Vec3(gxWin.average(), gyWin.average(), gzWin.average())
            if ((wMean - filter.gyroBias).norm() < ZARU_MAX_RATE) filter.zaru(wNow, ZARU_ALPHA)
        } else {
            // Una mano no mueve el móvil a más de ~2 m/s: el Kalman lo limita.
            kf.predict(filter.q, accel, dt, MAX_SPEED)
        }
        push(InsState(tNs, filter.q, kf.p, kf.v, stationary))
    }

    /** Sesgo del acelerómetro estimado (marco del dispositivo, m/s²). */
    @Synchronized fun accelBias(): Vec3 = kf.b

    /** Huecos en los sensores detectados desde el último reinicio (diagnóstico). */
    @Volatile var gaps = 0; private set

    private fun push(s: InsState) {
        history.addLast(s)
        while (history.size > capacity) history.removeFirst()
    }

    @Synchronized fun latest(): InsState? = history.lastOrNull()

    /** Estado interpolado en el instante t (p.ej. el timestamp de un fotograma). */
    /** El estado en tNs si la INS sigue en la misma época (sin reset desde entonces); si no, null. */
    @Synchronized fun stateAt(tNs: Long, epoch: Long): InsState? = if (epoch != this.epoch) null else stateAt(tNs)

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
        val h = (b.timestampNs - a.timestampNs) * 1e-9
        val f = (tNs - a.timestampNs).toDouble() / (b.timestampNs - a.timestampNs).toDouble()
        return InsState(
            tNs,
            a.orientation.slerp(b.orientation, f),
            hermite(a.position, a.velocity, b.position, b.velocity, h, f),
            a.velocity + (b.velocity - a.velocity) * f,
            a.stationary && b.stationary,
        )
    }

    /**
     * Curva cúbica de Hermite entre dos estados: pasa por ambas posiciones con la velocidad de
     * cada extremo, en vez de una recta. Si entre muestras hay un hueco (sensor que se salta
     * eventos, o el instante de una foto cae entre dos), la curva sigue la trayectoria real.
     */
    private fun hermite(p0: Vec3, v0: Vec3, p1: Vec3, v1: Vec3, h: Double, f: Double): Vec3 {
        val f2 = f * f; val f3 = f2 * f
        val h00 = 2 * f3 - 3 * f2 + 1; val h10 = f3 - 2 * f2 + f
        val h01 = -2 * f3 + 3 * f2; val h11 = f3 - f2
        return p0 * h00 + v0 * (h10 * h) + p1 * h01 + v1 * (h11 * h)
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
        /** Incertidumbre de "velocidad nula" en reposo (m/s): el pulso de la mano mueve algo. */
        const val ZUPT_SIGMA = 0.01
        /** Por encima de esta incertidumbre horizontal (interior, multitrayecto) no se usa la velocidad GNSS. */
        const val GNSS_MAX_ACC_FOR_VELOCITY = 25.0
        /** Incertidumbre de velocidad GNSS si el móvil no la informa (m/s). */
        const val DEFAULT_SPEED_ACC = 0.3
        /** Más tiempo que esto sin giróscopo = hueco: no se integra (s). */
        const val MAX_GAP_S = 0.15
        /** Velocidad tras un hueco: desconocida, con esta incertidumbre (m/s). */
        const val GAP_SPEED_STD = 0.3
        /** Espera máxima por la muestra del acelerómetro posterior a un giro (ns). */
        const val MAX_ACCEL_WAIT_NS = 20_000_000L
    }
}
