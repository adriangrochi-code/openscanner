package com.tisma.scanner.ins

import kotlin.math.sqrt

/**
 * Filtro de Kalman (de estado de error) para la parte de TRASLACIÓN de la INS:
 * posición p, velocidad v (marco mundo) y sesgo del acelerómetro b (marco del dispositivo).
 * La actitud la da el filtro de Mahony (ya afinado: brújula sólo en rumbo, ZARU).
 *
 *   predicción:  a = R·(a_medida − b) − g   (trapecio entre la muestra anterior a₀ y la actual a₁)
 *                v += (a₀ + a₁)/2·dt ;  p += v·dt + (2a₀ + a₁)/6·dt²   (exacto si a varía linealmente)
 *   errores:     δp' = δv ;  δv' = −R·δb + ruido ;  δb' = paseo aleatorio
 *
 * Correcciones (cada una pesa según su incertidumbre):
 *   - ZUPT: v = 0 al detectar reposo. Por las correlaciones aprendidas corrige también la
 *     posición y el sesgo; al cambiar de orientación entre paradas, el sesgo se hace observable
 *     en los 3 ejes (quieto en una sola orientación, sólo el eje de la gravedad).
 *   - Velocidad por la cámara (flujo óptico + distancia del enfoque), por componentes.
 *   - Posición GNSS.
 *   - Odometría visual-inercial (MSCKF, ver [VisualOdometry]): el estado se amplía con CLONES,
 *     la posición de la cámara en los últimos fotogramas clave. Un punto visto desde varios clones
 *     restringe sus posiciones relativas y, por las correlaciones con p y v, el estado actual.
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
    /** Aceleración lineal (mundo) de la muestra anterior, para integrar por trapecio. */
    private var prevAccel: Vec3? = null

    /** Posición de la cámara en un fotograma clave pasado (parte del estado). */
    class Clone(val tNs: Long, var c: Vec3, val xAxis: Vec3, val yAxis: Vec3, val zAxis: Vec3)
    val clones = ArrayList<Clone>()

    /** Dimensión del estado de error: 9 (p, v, b) + 3 por clon. */
    var dim = N; private set
    /** Covarianza dim×dim (fila mayor): 0-2 posición, 3-5 velocidad, 6-8 sesgo, luego clones. */
    private var P = DoubleArray(N * N)
    private val F = DoubleArray(N * N)
    private val tmp = DoubleArray(N * N)

    init { reset(Vec3.ZERO) }

    fun reset(biasPrior: Vec3) {
        p = Vec3.ZERO; v = Vec3.ZERO; b = biasPrior; prevAccel = null
        clones.clear(); dim = N; P = DoubleArray(N * N)
        for (i in 3..5) P[i * N + i] = 1e-4
        for (i in 6..8) P[i * N + i] = initialBiasStd * initialBiasStd
    }

    /** Varianza media de la posición (m²). */
    val positionVariance get() = (P[0] + P[dim + 1] + P[2 * dim + 2]) / 3

    /** Aceleración lineal en el mundo con el sesgo actual. */
    fun linearAccel(q: Quat, accelBody: Vec3): Vec3 =
        q.rotate(accelBody - b) - Vec3(0.0, 0.0, MahonyFilter.GRAVITY)

    fun predict(q: Quat, accelBody: Vec3, dt: Double, maxSpeed: Double) {
        val a1 = linearAccel(q, accelBody)
        val a0 = prevAccel ?: a1
        prevAccel = a1
        p = p + v * dt + (a0 * 2.0 + a1) * (dt * dt / 6)
        v = v + (a0 + a1) * (0.5 * dt)
        val s = v.norm()
        if (s > maxSpeed) v = v * (maxSpeed / s)

        // F = I + [0 I·dt 0; 0 0 −R·dt; 0 0 0] (sólo actúa sobre p, v, b; los clones no cambian).
        F.fill(0.0)
        for (i in 0 until N) F[i * N + i] = 1.0
        for (i in 0..2) F[i * N + 3 + i] = dt
        val R = rotationMatrix(q)
        for (r in 0..2) for (c in 0..2) F[(3 + r) * N + 6 + c] = -R[r * 3 + c] * dt
        // Bloque 9×9: F·Pxx·Fᵀ + Q.
        val pxx = DoubleArray(N * N)
        for (r in 0 until N) for (c in 0 until N) pxx[r * N + c] = P[r * dim + c]
        mul(F, pxx, tmp); mulT(tmp, F, pxx)
        val qv = accelNoise * accelNoise * dt
        val qb = biasWalk * biasWalk * dt
        for (i in 0..2) { pxx[i * N + i] += qv * dt * dt / 3; pxx[(3 + i) * N + 3 + i] += qv; pxx[(6 + i) * N + 6 + i] += qb }
        for (r in 0 until N) for (c in 0 until N) P[r * dim + c] = pxx[r * N + c]
        // Bloques cruzados con los clones: F·Pxc (y su traspuesta).
        if (dim > N) {
            val col = DoubleArray(N)
            for (c in N until dim) {
                for (r in 0 until N) { var s2 = 0.0; for (k in 0 until N) s2 += F[r * N + k] * P[k * dim + c]; col[r] = s2 }
                for (r in 0 until N) { P[r * dim + c] = col[r]; P[c * dim + r] = col[r] }
            }
        }
    }

    /** Velocidad nula (reposo). */
    fun zeroVelocity(sigma: Double) {
        for (i in 0..2) updateComponent(3, i, 0.0 - v[i], sigma * sigma)
    }

    /** Medida de la componente de la velocidad a lo largo de `dir` (unitario, mundo). */
    fun velocityAlong(dir: Vec3, value: Double, sigma: Double) {
        val h = DoubleArray(dim)
        h[3] = dir.x; h[4] = dir.y; h[5] = dir.z
        update(h, value - (v dot dir), sigma * sigma)
    }

    /** Medida de posición en un eje (0..2). */
    fun positionAxis(axis: Int, value: Double, sigma: Double) {
        updateComponent(0, axis, value - p[axis], sigma * sigma)
    }

    private fun updateComponent(block: Int, i: Int, innov: Double, r: Double) {
        val h = DoubleArray(dim); h[block + i] = 1.0
        update(h, innov, r)
    }

    // ------------------------------------------------------------------ clones (MSCKF)

    /**
     * Añade un clon: posición de la cámara en el instante `tNs`, que fue hace `dtBack` segundos
     * (el fotograma se procesa con algo de retraso): c = p − v·dtBack.
     */
    fun addClone(tNs: Long, dtBack: Double, xAxis: Vec3, yAxis: Vec3, zAxis: Vec3): Clone {
        val c = Clone(tNs, p - v * dtBack, xAxis, yAxis, zAxis)
        // J (3×dim): ∂c/∂p = I, ∂c/∂v = −dtBack·I.
        val nd = dim + 3
        val np = DoubleArray(nd * nd)
        for (r in 0 until dim) for (k in 0 until dim) np[r * nd + k] = P[r * dim + k]
        for (i in 0..2) for (k in 0 until dim) {
            val jp = P[i * dim + k] - dtBack * P[(3 + i) * dim + k]       // (J·P)[i][k]
            np[(dim + i) * nd + k] = jp; np[k * nd + dim + i] = jp
        }
        for (i in 0..2) for (j in 0..2) {
            // J·P·Jᵀ
            val v2 = P[i * dim + j] - dtBack * (P[(3 + i) * dim + j] + P[i * dim + 3 + j]) + dtBack * dtBack * P[(3 + i) * dim + 3 + j]
            np[(dim + i) * nd + dim + j] = v2
        }
        P = np; dim = nd
        clones += c
        return c
    }

    /** Quita (marginaliza) el clon `idx`. */
    fun removeClone(idx: Int) {
        val o = N + 3 * idx
        val nd = dim - 3
        val np = DoubleArray(nd * nd)
        var rr = 0
        for (r in 0 until dim) {
            if (r in o until o + 3) continue
            var cc = 0
            for (c in 0 until dim) { if (c in o until o + 3) continue; np[rr * nd + cc] = P[r * dim + c]; cc++ }
            rr++
        }
        P = np; dim = nd
        clones.removeAt(idx)
    }

    /**
     * Varias medidas escalares linealizadas en el MISMO punto (filas de una medida vectorial con
     * ruido independiente): cada fila se aplica en orden restando a su residuo lo que ya corrigieron
     * las anteriores, así equivale a la actualización conjunta.
     */
    fun updateRows(rows: List<DoubleArray>, residuals: DoubleArray, r: Double) {
        val applied = DoubleArray(dim)
        for ((k, h) in rows.withIndex()) {
            var already = 0.0
            for (i in 0 until dim) already += h[i] * applied[i]
            val dx = update(h, residuals[k] - already, r) ?: continue
            for (i in 0 until dim) applied[i] += dx[i]
        }
    }

    /** Actualización escalar. Devuelve la corrección aplicada (o null si no se pudo). */
    private fun update(h: DoubleArray, innov: Double, r: Double): DoubleArray? {
        val n = dim
        val ph = DoubleArray(n)
        for (i in 0 until n) { var s = 0.0; val row = i * n; for (j in 0 until n) s += P[row + j] * h[j]; ph[i] = s }
        var S = r; for (i in 0 until n) S += h[i] * ph[i]
        if (S <= 0) return null
        val dx = DoubleArray(n) { ph[it] / S * innov }
        // Corrección del estado nominal.
        p = p + Vec3(dx[0], dx[1], dx[2])
        v = v + Vec3(dx[3], dx[4], dx[5])
        b = b + Vec3(dx[6], dx[7], dx[8])
        for ((ci, c) in clones.withIndex()) { val o = N + 3 * ci; c.c = c.c + Vec3(dx[o], dx[o + 1], dx[o + 2]) }
        // P = P − (Ph)(Ph)ᵀ/S, simétrica.
        for (i in 0 until n) { val ki = ph[i] / S; val row = i * n; for (j in 0 until n) P[row + j] -= ki * ph[j] }
        for (i in 0 until n) for (j in i + 1 until n) { val m = 0.5 * (P[i * n + j] + P[j * n + i]); P[i * n + j] = m; P[j * n + i] = m }
        return dx
    }

    /** Covarianza (copia) para quien construye medidas (p.ej. el control de coherencia del MSCKF). */
    fun covariance(): DoubleArray = P.copyOf()

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
