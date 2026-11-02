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

    fun reset() { q = Quat.IDENTITY; gyroBias = Vec3.ZERO; initialized = false }

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
        if (mag != null && mag.norm() > 1e-3) {
            // Referencia magnética proyectada al plano horizontal (sólo corrige yaw).
            val mWorld = q.rotate(mag.normalized())
            val bRef = Vec3(kotlin.math.sqrt(mWorld.x * mWorld.x + mWorld.y * mWorld.y), 0.0, mWorld.z)
            val bBody = q.conj().rotate(bRef)
            error = error + (mag.normalized() cross bBody) * 0.3
        }
        gyroBias = gyroBias - error * (ki * dt)
        val corrected = gyro - gyroBias + error * kp
        q = (q * Quat.fromRotationVector(corrected * dt)).normalized()
    }

    companion object { const val GRAVITY = 9.80665 }
}
