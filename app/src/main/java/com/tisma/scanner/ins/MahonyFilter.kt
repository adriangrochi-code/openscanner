package com.tisma.scanner.ins

/**
 * Filtro de orientación de Mahony (complementario no lineal) para acelerómetro + giróscopo
 * (+ magnetómetro opcional). Estima la orientación body->mundo y el sesgo del giróscopo.
 *
 * Marco mundo: Z hacia arriba (opuesto a la gravedad), igual que el de Android.
 */
class MahonyFilter(
    var kp: Double = 1.0,
    var ki: Double = 0.02,
) {
    var q: Quat = Quat.IDENTITY
        private set
    var gyroBias: Vec3 = Vec3.ZERO
        private set
    private var initialized = false

    fun reset(bias: Vec3 = Vec3.ZERO) { q = Quat.IDENTITY; gyroBias = bias; initialized = false; magNormAvg = 0.0; yawAligned = false }

    /**
     * ZARU (Zero Angular Rate Update): en reposo el giróscopo debería marcar 0, así que lo que
     * marca es sesgo. Converge mucho más rápido que el término integral de Mahony.
     */
    fun zaru(gyroMeasured: Vec3, alpha: Double) {
        gyroBias = gyroBias * (1 - alpha) + gyroMeasured * alpha
    }

    /** Alinea la orientación inicial con la gravedad medida (pitch/roll), yaw = 0. */
    fun initialize(accel: Vec3) {
        q = Quat.fromTwoVectors(accel, Vec3(0.0, 0.0, 1.0))
        initialized = true
    }

    val isInitialized get() = initialized

    /**
     * @param gyro velocidad angular rad/s (marco body)
     * @param accel fuerza específica m/s² (marco body), o null si no hay muestra
     * @param mag campo magnético (marco body), opcional
     * @param dt segundos
     * @param accelWeight 0..1, confianza externa en que `accel` ≈ gravedad (ganancia adaptativa)
     */
    fun update(gyro: Vec3, accel: Vec3?, mag: Vec3?, dt: Double, accelWeight: Double = 1.0) {
        if (!initialized) { if (accel != null) initialize(accel); return }
        var error = Vec3.ZERO
        if (accel != null && accel.norm() > 1e-3) {
            val aNorm = accel.normalized()
            // Dirección de "arriba" predicha en marco body.
            val upBody = q.conj().rotate(Vec3(0.0, 0.0, 1.0))
            // Confiar menos en el acelerómetro cuando hay aceleración lineal fuerte.
            val g = accel.norm()
            val trust = (1.0 - kotlin.math.abs(g - GRAVITY) / 3.0).coerceIn(0.0, 1.0) * accelWeight
            error = error + (aNorm cross upBody) * trust
        }
        // El sesgo del giróscopo sólo se aprende de la gravedad (referencia fiable).
        gyroBias = gyroBias - error * (ki * dt)
        val magOk = mag != null && magUsable(mag)
        if (mag != null) { if (magOk) magAccepted++ else magRejected++ }
        if (magOk) {
            mag!!
            // Rumbo con la brújula, pero SÓLO alrededor de la vertical: dentro de casa el campo
            // está distorsionado (mesa, PC, hierro) y, sin esta restricción, la corrección inclinaba
            // la actitud, la gravedad "se filtraba" como aceleración y la INS se iba decenas de
            // metros con el móvil quieto. La inclinación la decide sólo la gravedad.
            val mWorld = q.rotate(mag.normalized())
            val bRef = Vec3(kotlin.math.sqrt(mWorld.x * mWorld.x + mWorld.y * mWorld.y), 0.0, mWorld.z)
            val errBody = (mag.normalized() cross q.conj().rotate(bRef)) * MAG_WEIGHT
            val vertical = q.rotate(errBody).z                    // componente de rumbo (eje Z mundo)
            error = error + q.conj().rotate(Vec3(0.0, 0.0, vertical))
        }
        val corrected = gyro - gyroBias + error * kp
        q = (q * Quat.fromRotationVector(corrected * dt)).normalized()
    }

    /**
     * Descarta lecturas del magnetómetro con un módulo anómalo (imán, metal muy cerca): se
     * compara con una media lenta del propio campo medido.
     */
    private var magNormAvg = 0.0

    /**
     * Campo magnético terrestre esperado en el lugar (modelo magnético mundial, a partir del GPS):
     * intensidad (µT) e inclinación (grados, positiva hacia abajo; en Uruguay ≈ −35°, apunta
     * hacia arriba). Con él se reconoce el campo distorsionado de interiores, que antes pasaba
     * como válido porque sólo se comparaba con la media de las propias lecturas.
     */
    @Volatile var expectedFieldUt: Double? = null
    @Volatile var expectedInclinationDeg: Double? = null

    fun magUsable(m: Vec3): Boolean {
        val n = m.norm()
        val exp = expectedFieldUt
        if (exp != null) {
            if (kotlin.math.abs(n - exp) > MAG_FIELD_TOL * exp) return false
            val incl = expectedInclinationDeg
            if (incl != null && initialized) {
                val up = q.conj().rotate(Vec3(0.0, 0.0, 1.0))
                val measured = Math.toDegrees(kotlin.math.asin((-(m dot up) / n).coerceIn(-1.0, 1.0)))
                if (kotlin.math.abs(measured - incl) > MAG_INCLINATION_TOL) return false
            }
            return true
        }
        // Sin modelo (aún sin GPS): sólo descartar saltos respecto a la media de las lecturas.
        if (magNormAvg == 0.0) { magNormAvg = n; return true }
        val ok = kotlin.math.abs(n - magNormAvg) < 0.15 * magNormAvg
        magNormAvg += (n - magNormAvg) * (if (ok) 0.01 else 0.001)
        return ok
    }

    /** Lecturas de la brújula aceptadas / descartadas (campo distorsionado). */
    @Volatile var magAccepted = 0L; private set
    @Volatile var magRejected = 0L; private set
    fun resetMagStats() { magAccepted = 0; magRejected = 0 }

    /** ¿Ya se tomó el rumbo inicial de la brújula? */
    var yawAligned = false
        private set

    /**
     * Rumbo inicial directamente de la brújula (X del mundo hacia el norte magnético), en vez de
     * converger despacio desde un rumbo arbitrario: eso era el "+30° en 5 min" con el móvil quieto.
     */
    fun alignYaw(mag: Vec3) {
        if (!initialized || !magUsable(mag)) return
        val m = q.rotate(mag)
        val heading = kotlin.math.atan2(m.y, m.x)
        q = (Quat.fromRotationVector(Vec3(0.0, 0.0, -heading)) * q).normalized()
        yawAligned = true
    }

    companion object {
        const val GRAVITY = 9.80665
        /** Peso de la brújula frente a la gravedad (sólo rumbo). */
        const val MAG_WEIGHT = 0.3
        /** Tolerancia frente al campo esperado: intensidad (fracción) e inclinación (grados). */
        const val MAG_FIELD_TOL = 0.15
        const val MAG_INCLINATION_TOL = 8.0
    }
}
