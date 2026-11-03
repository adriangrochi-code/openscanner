package com.tisma.scanner.ins

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Calibración AUTOMÁTICA del acelerómetro, en segundo plano y sin pedir nada al usuario.
 *
 * Por qué: quieto en UNA orientación sólo se ve el error del eje de la gravedad; el de los otros
 * dos ejes parece una pequeña inclinación y, al coger el móvil y girarlo, "filtra" gravedad como
 * aceleración (0,1° = 0,017 m/s²): la posición pasa de centímetros a decímetros.
 *
 * Cómo: cada vez que el móvil queda quieto unos segundos se guarda su "postura" (la gravedad
 * medida). Con posturas de direcciones distintas, el error se vuelve observable:
 *   - ≥ 3 posturas bien repartidas (p.ej. boca arriba, de pie y de costado): SESGO de los 3 ejes;
 *   - las 6 direcciones (cada eje en ambos sentidos): SESGO y ESCALA de cada eje.
 *   modelo:  a_real = s ⊙ (a_medida − b)  con  |a_real| = g  en cada postura.
 * Las posturas se acumulan entre sesiones: la calibración mejora sola con el uso.
 */
class AccelCalibration(
    private val holdSeconds: Double = 2.5,
    /** Desviación máxima de las muestras para considerar reposo (m/s²). */
    private val stillStd: Double = 0.06,
    /** Dos posturas a menos de este ángulo se consideran la misma (se queda la más reciente). */
    private val minSeparationDeg: Double = 25.0,
    private val maxPoses: Int = 16,
) {
    enum class Mode(val label: String) { BIAS("sesgo"), BIAS_SCALE("sesgo y escala") }

    data class Result(val bias: Vec3, val scale: Vec3, val rmsResidual: Double, val mode: Mode = Mode.BIAS_SCALE, val poses: Int = 6) {
        fun apply(a: Vec3) = Vec3(scale.x * (a.x - bias.x), scale.y * (a.y - bias.y), scale.z * (a.z - bias.z))
        /** ¿Resultado plausible para un acelerómetro de móvil? */
        val plausible get() = bias.norm() < 1.0 && listOf(scale.x, scale.y, scale.z).all { abs(it - 1) < 0.05 } && rmsResidual < 0.05
    }

    private val window = ArrayDeque<Pair<Long, Vec3>>()
    private var sx = 0.0; private var sy = 0.0; private var sz = 0.0; private var sq = 0.0
    private val poses = ArrayList<Vec3>()
    private var capturedThisRest = false

    @Volatile var still = false; private set

    val poseCount get() = synchronized(this) { poses.size }
    fun poses(): List<Vec3> = synchronized(this) { poses.toList() }
    @Synchronized fun restore(saved: List<Vec3>) { poses.clear(); poses.addAll(saved.takeLast(maxPoses)) }
    @Synchronized fun reset() {
        window.clear(); poses.clear(); capturedThisRest = false; still = false
        sx = 0.0; sy = 0.0; sz = 0.0; sq = 0.0
    }

    /**
     * Alimentar con cada muestra cruda. Devuelve true cuando se ha guardado una postura NUEVA
     * (dirección distinta a las conocidas): es el momento de recalcular.
     */
    @Synchronized fun feed(tNs: Long, a: Vec3): Boolean {
        // Ventana deslizante con sumas acumuladas: a 400 Hz recorrerla entera en cada muestra
        // crearía cientos de miles de objetos por segundo.
        window.addLast(tNs to a)
        sx += a.x; sy += a.y; sz += a.z; sq += a dot a
        val holdNs = (holdSeconds * 1e9).toLong()
        while (window.isNotEmpty() && tNs - window.first().first > holdNs) {
            val o = window.removeFirst().second
            sx -= o.x; sy -= o.y; sz -= o.z; sq -= o dot o
        }
        val n = window.size.toDouble()
        val mean = Vec3(sx / n, sy / n, sz / n)
        val variance = (sq / n - (mean dot mean)).coerceAtLeast(0.0) / 3
        still = sqrt(variance) < stillStd && abs(mean.norm() - MahonyFilter.GRAVITY) < 0.5
        if (!still) { capturedThisRest = false; return false }
        // La ventana entera (holdSeconds) ya está quieta: eso es el reposo, no hay que esperar más.
        val span = window.last().first - window.first().first
        if (capturedThisRest || span < holdNs * 0.95) return false
        capturedThisRest = true          // una postura por cada reposo
        val dir = mean.normalized()
        val near = poses.indexOfFirst { Math.toDegrees(acos((it.normalized() dot dir).coerceIn(-1.0, 1.0))) < minSeparationDeg }
        return if (near >= 0) { poses[near] = mean; false }          // misma postura: refrescar
        else { poses += mean; while (poses.size > maxPoses) poses.removeAt(0); true }
    }

    /** ¿Las posturas cubren cada eje en ambos sentidos? (hace observable la escala) */
    private fun coversSixDirections(): Boolean = (0..2).all { ax ->
        val c = poses.map { doubleArrayOf(it.x, it.y, it.z)[ax] / it.norm() }
        c.any { it > 0.6 } && c.any { it < -0.6 }
    }

    /** ¿Direcciones suficientemente distintas para el sesgo de 3 ejes? (menor valor singular) */
    private fun spreadForBias(): Boolean {
        if (poses.size < 3) return false
        val m = Array(3) { DoubleArray(3) }
        for (p in poses) { val u = p.normalized(); val c = doubleArrayOf(u.x, u.y, u.z)
            for (i in 0..2) for (j in 0..2) m[i][j] += c[i] * c[j] }
        // Menor autovalor de la matriz 3×3 simétrica (por el polinomio característico no hace falta:
        // basta con el determinante normalizado frente a la traza).
        val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        val tr = m[0][0] + m[1][1] + m[2][2]
        return det / (tr * tr * tr / 27) > 0.05
    }

    /**
     * Ajuste de Gauss-Newton: sesgo y escala si las posturas cubren las 6 direcciones; sólo sesgo
     * (escala 1) si hay al menos 3 bien repartidas; null si aún no hay bastantes.
     */
    @Synchronized fun solve(g: Double = MahonyFilter.GRAVITY): Result? {
        val mode = when {
            coversSixDirections() && poses.size >= 6 -> Mode.BIAS_SCALE
            spreadForBias() -> Mode.BIAS
            else -> return null
        }
        val free = if (mode == Mode.BIAS_SCALE) 6 else 3
        val x = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        repeat(30) {
            val jtj = Array(free) { DoubleArray(free) }
            val jtr = DoubleArray(free)
            for (m in poses) {
                val mm = doubleArrayOf(m.x, m.y, m.z)
                val u = DoubleArray(3) { x[3 + it] * (mm[it] - x[it]) }
                val n = sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2])
                val r = n - g
                val jrow = DoubleArray(6)
                for (k in 0..2) {
                    jrow[k] = -u[k] * x[3 + k] / n                 // ∂r/∂b_k
                    jrow[3 + k] = u[k] * (mm[k] - x[k]) / n       // ∂r/∂s_k
                }
                for (i in 0 until free) { jtr[i] += jrow[i] * r; for (j in 0 until free) jtj[i][j] += jrow[i] * jrow[j] }
            }
            for (i in 0 until free) jtj[i][i] += 1e-9
            val dx = solveLinear(jtj, jtr) ?: return null
            for (i in 0 until free) x[i] -= dx[i]
        }
        val res = poses.map { m -> Vec3(x[3] * (m.x - x[0]), x[4] * (m.y - x[1]), x[5] * (m.z - x[2])).norm() - g }
        return Result(Vec3(x[0], x[1], x[2]), Vec3(x[3], x[4], x[5]), sqrt(res.sumOf { it * it } / res.size), mode, poses.size)
    }

    private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (c in 0 until n) {
            val p = (c until n).maxByOrNull { abs(m[it][c]) }!!
            if (abs(m[p][c]) < 1e-15) return null
            val t = m[c]; m[c] = m[p]; m[p] = t
            for (r in 0 until n) if (r != c) {
                val f = m[r][c] / m[c][c]
                for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }
}
