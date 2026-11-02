package com.tisma.scanner.ins

import kotlin.math.sqrt

/**
 * Filtro de Kalman (de estado de error) para la parte de TRASLACIÓN de la INS:
 * posición p, velocidad v (marco mundo) y sesgo del acelerómetro b (marco del dispositivo).
 * La actitud la da el filtro de Mahony (ya afinado: brújula sólo en rumbo, ZARU).
 *
 *   predicción:  a = R·(a_medida − b) − g ;  p += v·dt + a·dt²/2 ;  v += a·dt
 *   errores:     δp' = δv ;  δv' = −R·δb + ruido ;  δb' = paseo aleatorio
 *
 * Correcciones (cada una pesa según su incertidumbre):
 *   - ZUPT: v = 0 al detectar reposo. Por las correlaciones aprendidas corrige también la
 *     posición y el sesgo; al cambiar de orientación entre paradas, el sesgo se hace observable
 *     en los 3 ejes (quieto en una sola orientación, sólo el eje de la gravedad).
 *   - Velocidad por la cámara (flujo óptico + distancia del enfoque), por componentes.
 *   - Posición GNSS.
 *
 * Las actualizaciones son escalares (una componente cada vez): no hace falta invertir matrices.
 */
class TranslationKalman(
    /** Ruido de aceleración (m/s² por √s): ruido del sensor + error de actitud (gravedad filtrada). */
    private val accelNoise: Double = 0.05,
    /** Paseo aleatorio del sesgo del acelerómetro (m/s² por √s). */
    private val biasWalk: Double = 0.002,
    /** Incertidumbre inicial del sesgo (m/s²). */
    private val initialBiasStd: Double = 0.05,
) {
    var p = Vec3.ZERO; private set
    var v = Vec3.ZERO; private set
    var b = Vec3.ZERO; private set
    /** Covarianza 9×9 (fila mayor): índices 0-2 posición, 3-5 velocidad, 6-8 sesgo. */
    private val P = DoubleArray(N * N)
    private val tmp = DoubleArray(N * N)
    private val F = DoubleArray(N * N)

    init { reset(Vec3.ZERO) }

    fun reset(biasPrior: Vec3) {
        p = Vec3.ZERO; v = Vec3.ZERO; b = biasPrior
        P.fill(0.0)
        for (i in 3..5) P[i * N + i] = 1e-4
        for (i in 6..8) P[i * N + i] = initialBiasStd * initialBiasStd
    }

    /** Varianza media de la posición (m²). */
    val positionVariance get() = (P[0] + P[N + 1] + P[2 * N + 2]) / 3

    /** Aceleración lineal en el mundo con el sesgo actual. */
    fun linearAccel(q: Quat, accelBody: Vec3): Vec3 =
        q.rotate(accelBody - b) - Vec3(0.0, 0.0, MahonyFilter.GRAVITY)

    fun predict(q: Quat, accelBody: Vec3, dt: Double, maxSpeed: Double) {
        val a = linearAccel(q, accelBody)
        p = p + v * dt + a * (0.5 * dt * dt)
        v = v + a * dt
        val s = v.norm()
        if (s > maxSpeed) v = v * (maxSpeed / s)

        // F = I + [0 I·dt 0; 0 0 −R·dt; 0 0 0]
        F.fill(0.0)
        for (i in 0 until N) F[i * N + i] = 1.0
        for (i in 0..2) F[i * N + 3 + i] = dt
        val R = rotationMatrix(q)
        for (r in 0..2) for (c in 0..2) F[(3 + r) * N + 6 + c] = -R[r * 3 + c] * dt
        // P = F·P·Fᵀ + Q
        mul(F, P, tmp); mulT(tmp, F, P)
        val qv = accelNoise * accelNoise * dt
        val qb = biasWalk * biasWalk * dt
        for (i in 0..2) { P[i * N + i] += qv * dt * dt / 3; P[(3 + i) * N + 3 + i] += qv; P[(6 + i) * N + 6 + i] += qb }
    }

    /** Velocidad nula (reposo). */
    fun zeroVelocity(sigma: Double) {
        for (i in 0..2) updateComponent(3, i, 0.0 - v[i], sigma * sigma)
    }

    /** Medida de la componente de la velocidad a lo largo de `dir` (unitario, mundo). */
    fun velocityAlong(dir: Vec3, value: Double, sigma: Double) {
        val h = DoubleArray(N)
        h[3] = dir.x; h[4] = dir.y; h[5] = dir.z
        update(h, value - (v dot dir), sigma * sigma)
    }

    /** Medida de posición en un eje (0..2). */
    fun positionAxis(axis: Int, value: Double, sigma: Double) {
        updateComponent(0, axis, value - p[axis], sigma * sigma)
    }

    private fun updateComponent(block: Int, i: Int, innov: Double, r: Double) {
        val h = DoubleArray(N); h[block + i] = 1.0
        update(h, innov, r)
    }

    /** Actualización escalar con forma de Joseph (estable numéricamente). */
    private fun update(h: DoubleArray, innov: Double, r: Double) {
        val ph = DoubleArray(N)
        for (i in 0 until N) { var s = 0.0; for (j in 0 until N) s += P[i * N + j] * h[j]; ph[i] = s }
        var S = r; for (i in 0 until N) S += h[i] * ph[i]
        if (S <= 0) return
        val k = DoubleArray(N) { ph[it] / S }
        // Corrección del estado nominal.
        p = p + Vec3(k[0], k[1], k[2]) * innov
        v = v + Vec3(k[3], k[4], k[5]) * innov
        b = b + Vec3(k[6], k[7], k[8]) * innov
        // P = (I − K h) P (I − K h)ᵀ + K r Kᵀ
        for (i in 0 until N) for (j in 0 until N) F[i * N + j] = (if (i == j) 1.0 else 0.0) - k[i] * h[j]
        mul(F, P, tmp); mulT(tmp, F, P)
        for (i in 0 until N) for (j in 0 until N) P[i * N + j] += k[i] * r * k[j]
    }

    fun positionStd(): Double = sqrt(positionVariance)

    private operator fun Vec3.get(i: Int) = when (i) { 0 -> x; 1 -> y; else -> z }

    companion object {
        const val N = 9

        /** C = A·B (N×N). */
        private fun mul(a: DoubleArray, b: DoubleArray, c: DoubleArray) {
            for (i in 0 until N) for (j in 0 until N) {
                var s = 0.0; for (k in 0 until N) s += a[i * N + k] * b[k * N + j]; c[i * N + j] = s
            }
        }

        /** C = A·Bᵀ (N×N). */
        private fun mulT(a: DoubleArray, b: DoubleArray, c: DoubleArray) {
            for (i in 0 until N) for (j in 0 until N) {
                var s = 0.0; for (k in 0 until N) s += a[i * N + k] * b[j * N + k]; c[i * N + j] = s
            }
        }

        /** Matriz de rotación 3×3 (dispositivo -> mundo) del cuaternión. */
        fun rotationMatrix(q: Quat): DoubleArray {
            val (w, x, y, z) = q
            return doubleArrayOf(
                1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
                2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
                2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
            )
        }
    }
}
