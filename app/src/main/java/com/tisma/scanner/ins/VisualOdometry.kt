package com.tisma.scanner.ins

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Odometría visual-inercial clásica: MSCKF (Mourikis y Roumeliotis, 2007), la base de OpenVINS,
 * implementada aquí sobre el Kalman de traslación de la INS.
 *
 * - En cada fotograma clave se añade un CLON al estado: la posición de la cámara en ese instante.
 *   La orientación la da el giróscopo (filtro de Mahony): aquí se toma como conocida.
 * - Cada punto seguido en la imagen acumula sus observaciones (coordenadas normalizadas) en los
 *   clones donde se vio.
 * - Cuando el punto deja de verse (o su clon más viejo va a salir de la ventana), se triangula con
 *   esas vistas, se elimina su posición 3D de las ecuaciones proyectando sobre el espacio nulo
 *   izquierdo de ∂z/∂X, y lo que queda restringe sólo las posiciones de los clones. Por las
 *   correlaciones del filtro corrige también la posición, velocidad y sesgo actuales.
 *
 * Así la ESCALA la pone el acelerómetro y la GEOMETRÍA la imagen, cada una con su incertidumbre:
 * es lo que fallaba al encadenar "dirección por imagen, longitud por INS".
 */
class VisualOdometry(
    private val kf: TranslationKalman,
    /** Clones en la ventana (más = más precisión, más cálculo). */
    private val maxClones: Int = 12,
    /** Ruido de medida del seguimiento (px de la imagen en la que se sigue). */
    private val pixelSigma: Double = 1.0,
    /** Paralaje mínimo para usar un punto (grados). */
    private val minParallaxDeg: Double = 1.5,
    /**
     * Un seguimiento largo se usa por tramos de esta longitud (y sigue desde su última vista):
     * esperar a que termine retrasaría la corrección mientras la INS deriva.
     */
    private val chunk: Int = 4,
) {
    private class Obs(val clone: TranslationKalman.Clone, val x: Double, val y: Double)
    private val tracks = HashMap<Int, ArrayList<Obs>>()

    /** Estadística del último fotograma (para el registro). */
    var lastFeaturesUsed = 0; private set
    var lastRows = 0; private set

    /**
     * Nuevo fotograma clave.
     * @param dtBack segundos desde el instante del fotograma hasta ahora (estado actual del filtro)
     * @param obs puntos seguidos visibles en este fotograma: (id del seguimiento, u, v) en píxeles
     * @param f, cx, cy intrínsecos en esos píxeles
     * @return posición de la cámara en ese fotograma, ya corregida
     */
    fun onKeyframe(tNs: Long, dtBack: Double, xAxis: Vec3, yAxis: Vec3, zAxis: Vec3,
                   obs: List<Triple<Int, Double, Double>>, f: Double, cx: Double, cy: Double): Vec3 {
        val clone = kf.addClone(tNs, dtBack, xAxis, yAxis, zAxis)
        val seen = HashSet<Int>()
        for ((id, u, v) in obs) {
            tracks.getOrPut(id) { ArrayList() } += Obs(clone, (u - cx) / f, (v - cy) / f)
            seen += id
        }
        // Puntos que ya no se ven, y los que dependen del clon que va a salir de la ventana.
        val oldest = if (kf.clones.size > maxClones) kf.clones.first() else null
        val ready = tracks.filter { (id, o) -> id !in seen || (oldest != null && o.first().clone === oldest) }.keys.toList()
        val chunks = tracks.filter { (id, o) -> id in seen && id !in ready && o.size >= chunk }.keys.toList()
        update((ready + chunks).mapNotNull { tracks[it] }, f)
        for (id in ready) tracks.remove(id)
        // El tramo usado se descarta; el seguimiento continúa desde la vista actual.
        for (id in chunks) tracks[id]?.let { o -> val last = o.last(); o.clear(); o += last }
        if (oldest != null) {
            kf.removeClone(0)
            for (o in tracks.values) o.removeAll { it.clone === oldest }
            tracks.values.removeAll { it.isEmpty() }
        }
        return clone.c
    }

    fun reset() { tracks.clear() }

    private fun update(features: List<List<Obs>>, f: Double) {
        val rows = ArrayList<DoubleArray>(); val res = ArrayList<Double>()
        var used = 0
        val cov = kf.covariance()
        val sigma2 = (pixelSigma / f) * (pixelSigma / f)
        for (all in features) {
            val o = all.filter { ob -> kf.clones.any { it === ob.clone } }
            if (o.size < 3) continue
            val x = triangulate(o) ?: continue
            val fr = ArrayList<DoubleArray>(); val fres = ArrayList<Double>()
            if (!addFeatureRows(o, x, f, fr, fres)) continue
            // Control chi² (95 %): ¿el residuo cabe en lo que el filtro espera (estado + ruido)?
            if (!chiSquareOk(fr, fres, cov, sigma2)) continue
            rows += fr; res += fres
            used++
        }
        lastFeaturesUsed = used; lastRows = rows.size
        if (rows.isNotEmpty()) {
            val sigma = pixelSigma / f
            kf.updateRows(rows, res.toDoubleArray(), sigma * sigma)
        }
    }

    private fun ray(o: Obs) = (o.clone.xAxis * o.x + o.clone.yAxis * o.y + o.clone.zAxis).normalized()

    /** Punto más cercano a todos los rayos + 5 pasos de Gauss-Newton en el error de reproyección. */
    private fun triangulate(o: List<Obs>): Vec3? {
        val rays = o.map { ray(it) }
        val par = rays.drop(1).maxOf { Math.toDegrees(acos((it dot rays[0]).coerceIn(-1.0, 1.0))) }
        if (par < minParallaxDeg) return null
        val a = DoubleArray(9); val b = DoubleArray(3)
        for ((i, d) in rays.withIndex()) {
            val c = o[i].clone.c
            val dd = doubleArrayOf(d.x, d.y, d.z); val cc = doubleArrayOf(c.x, c.y, c.z)
            for (r in 0..2) for (s in 0..2) { val m = (if (r == s) 1.0 else 0.0) - dd[r] * dd[s]; a[r * 3 + s] += m; b[r] += m * cc[s] }
        }
        var x = solve3(a, b)?.let { Vec3(it[0], it[1], it[2]) } ?: return null
        repeat(5) {
            val jtj = DoubleArray(9); val jtr = DoubleArray(3)
            for (ob in o) {
                val (pc, jx) = projJacobian(ob.clone, x) ?: return null
                val rx = ob.x - pc.x / pc.z; val ry = ob.y - pc.y / pc.z
                for (r in 0..2) {
                    jtr[r] += jx[0][r] * rx + jx[1][r] * ry
                    for (s in 0..2) jtj[r * 3 + s] += jx[0][r] * jx[0][s] + jx[1][r] * jx[1][s]
                }
            }
            val dx = solve3(jtj, jtr) ?: return@repeat
            x = x + Vec3(dx[0], dx[1], dx[2])
        }
        return x
    }

    /** Punto en ejes de la cámara del clon y ∂z/∂X (2×3) de z = (Xc/Zc, Yc/Zc). */
    private fun projJacobian(c: TranslationKalman.Clone, x: Vec3): Pair<Vec3, Array<DoubleArray>>? {
        val d = x - c.c
        val pc = Vec3(d dot c.xAxis, d dot c.yAxis, d dot c.zAxis)
        if (pc.z < 1e-3) return null
        val iz = 1.0 / pc.z
        // Jπ (2×3) · Rᵀ (filas = ejes de la cámara en el mundo).
        val j0 = doubleArrayOf(iz, 0.0, -pc.x * iz * iz); val j1 = doubleArrayOf(0.0, iz, -pc.y * iz * iz)
        val ax = arrayOf(c.xAxis, c.yAxis, c.zAxis)
        fun row(j: DoubleArray) = DoubleArray(3) { k ->
            j[0] * ax[0].comp(k) + j[1] * ax[1].comp(k) + j[2] * ax[2].comp(k)
        }
        return pc to arrayOf(row(j0), row(j1))
    }

    private fun Vec3.comp(k: Int) = when (k) { 0 -> x; 1 -> y; else -> z }

    /**
     * Filas del MSCKF para un punto: residuos y jacobianos respecto a los clones, proyectados sobre
     * el complemento ortogonal de ∂z/∂X (así el punto desaparece y el ruido sigue siendo σ²·I).
     */
    private fun addFeatureRows(o: List<Obs>, x: Vec3, f: Double, rows: MutableList<DoubleArray>, res: MutableList<Double>): Boolean {
        val m2 = 2 * o.size
        val hx = Array(m2) { DoubleArray(3) }
        val hc = Array(m2) { DoubleArray(kf.dim) }
        val r = DoubleArray(m2)
        for ((i, ob) in o.withIndex()) {
            val (pc, jx) = projJacobian(ob.clone, x) ?: return false
            r[2 * i] = ob.x - pc.x / pc.z; r[2 * i + 1] = ob.y - pc.y / pc.z
            // Errores groseros (seguimiento perdido) fuera ya; el resto lo decide el chi².
            if (sqrt(r[2 * i] * r[2 * i] + r[2 * i + 1] * r[2 * i + 1]) * f > 30.0) return false
            val ci = kf.clones.indexOfFirst { it === ob.clone }
            val col = TranslationKalman.N + 3 * ci
            for (k in 0..2) {
                hx[2 * i][k] = jx[0][k]; hx[2 * i + 1][k] = jx[1][k]
                hc[2 * i][col + k] = -jx[0][k]; hc[2 * i + 1][col + k] = -jx[1][k]
            }
        }
        // Base ortonormal del complemento de las columnas de hx (Gram-Schmidt).
        val basis = ArrayList<DoubleArray>()
        for (k in 0..2) addOrthonormal(basis, DoubleArray(m2) { hx[it][k] })
        if (basis.size < 3) return false
        val comp = ArrayList<DoubleArray>()
        for (e in 0 until m2) {
            if (comp.size == m2 - 3) break
            val v = DoubleArray(m2); v[e] = 1.0
            val before = basis.size
            addOrthonormal(basis, v)
            if (basis.size > before) comp += basis.last()
        }
        for (a in comp) {
            val h = DoubleArray(kf.dim)
            var rr = 0.0
            for (i in 0 until m2) {
                if (a[i] == 0.0) continue
                rr += a[i] * r[i]
                val row = hc[i]
                for (k in h.indices) h[k] += a[i] * row[k]
            }
            rows += h; res += rr
        }
        return true
    }

    /** γ = rᵀ (H P Hᵀ + σ²I)⁻¹ r frente al percentil 95 de χ² con d grados de libertad. */
    private fun chiSquareOk(h: List<DoubleArray>, r: List<Double>, cov: DoubleArray, sigma2: Double): Boolean {
        val d = h.size; val n = kf.dim
        val hp = Array(d) { i -> DoubleArray(n) { j -> var s = 0.0; for (k in 0 until n) s += h[i][k] * cov[k * n + j]; s } }
        val S = Array(d) { i -> DoubleArray(d) { j -> var s = if (i == j) sigma2 else 0.0; for (k in 0 until n) s += hp[i][k] * h[j][k]; s } }
        // Cholesky S = L·Lᵀ; γ = |L⁻¹ r|².
        val L = Array(d) { DoubleArray(d) }
        for (i in 0 until d) for (j in 0..i) {
            var s = S[i][j]; for (k in 0 until j) s -= L[i][k] * L[j][k]
            if (i == j) { if (s <= 0) return false; L[i][i] = sqrt(s) } else L[i][j] = s / L[j][j]
        }
        val y = DoubleArray(d)
        for (i in 0 until d) { var s = r[i]; for (k in 0 until i) s -= L[i][k] * y[k]; y[i] = s / L[i][i] }
        val gamma = y.sumOf { it * it }
        // Wilson-Hilferty: χ²₀.₉₅(d) ≈ d·(1 − 2/(9d) + 1.645·√(2/(9d)))³
        val c = 2.0 / (9 * d)
        val lim = d * Math.pow(1 - c + 1.645 * sqrt(c), 3.0)
        return gamma <= lim
    }

    private fun addOrthonormal(basis: MutableList<DoubleArray>, v0: DoubleArray) {
        val v = v0.copyOf()
        repeat(2) {                          // dos pasadas: Gram-Schmidt estable
            for (u in basis) { var d = 0.0; for (i in v.indices) d += u[i] * v[i]; for (i in v.indices) v[i] -= d * u[i] }
        }
        var n = 0.0; for (x in v) n += x * x
        n = sqrt(n)
        if (n < 1e-8) return
        for (i in v.indices) v[i] /= n
        basis += v
    }

    private fun solve3(a: DoubleArray, b: DoubleArray): DoubleArray? {
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        if (kotlin.math.abs(det) < 1e-18) return null
        fun d(c: Int): Double {
            val m = a.copyOf(); for (r in 0..2) m[r * 3 + c] = b[r]
            return m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        }
        return doubleArrayOf(d(0) / det, d(1) / det, d(2) / det)
    }
}
