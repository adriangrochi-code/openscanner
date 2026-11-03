package com.tisma.scanner.recon

/**
 * Triangulación de Delaunay 2D (Bowyer-Watson). Para unos cientos de puntos por vista es de
 * sobra rápida y no necesita librerías. Devuelve índices (a, b, c) en sentido antihorario.
 */
object Delaunay {
    fun triangulate(xs: DoubleArray, ys: DoubleArray): List<IntArray> {
        val n = xs.size
        if (n < 3) return emptyList()
        var minX = xs.min(); var minY = ys.min(); var maxX = xs.max(); var maxY = ys.max()
        val d = maxOf(maxX - minX, maxY - minY).coerceAtLeast(1e-9) * 20
        val mx = (minX + maxX) / 2; val my = (minY + maxY) / 2
        // Supertriángulo que contiene todo (índices n, n+1, n+2).
        val px = xs.copyOf(n + 3); val py = ys.copyOf(n + 3)
        px[n] = mx - d; py[n] = my - d
        px[n + 1] = mx + d; py[n + 1] = my - d
        px[n + 2] = mx; py[n + 2] = my + d
        class Tri(val a: Int, val b: Int, val c: Int) {
            val cx: Double; val cy: Double; val r2: Double
            init {
                val ax = px[a]; val ay = py[a]; val bx = px[b]; val by = py[b]; val qx = px[c]; val qy = py[c]
                val dd = 2 * (ax * (by - qy) + bx * (qy - ay) + qx * (ay - by))
                if (kotlin.math.abs(dd) < 1e-18) { cx = 0.0; cy = 0.0; r2 = Double.MAX_VALUE } else {
                    val a2 = ax * ax + ay * ay; val b2 = bx * bx + by * by; val c2 = qx * qx + qy * qy
                    cx = (a2 * (by - qy) + b2 * (qy - ay) + c2 * (ay - by)) / dd
                    cy = (a2 * (qx - bx) + b2 * (ax - qx) + c2 * (bx - ax)) / dd
                    r2 = (ax - cx) * (ax - cx) + (ay - cy) * (ay - cy)
                }
            }
            fun inCircle(x: Double, y: Double) = (x - cx) * (x - cx) + (y - cy) * (y - cy) < r2 * (1 - 1e-12)
        }
        var tris = arrayListOf(Tri(n, n + 1, n + 2))
        for (i in 0 until n) {
            val x = px[i]; val y = py[i]
            val bad = tris.filter { it.inCircle(x, y) }
            // Borde del hueco: aristas que sólo pertenecen a un triángulo malo.
            val edges = HashMap<Long, IntArray>()
            for (t in bad) for ((u, v) in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                val key = minOf(u, v).toLong() * 1_000_003 + maxOf(u, v)
                if (edges.remove(key) == null) edges[key] = intArrayOf(u, v)
            }
            val badSet = bad.toHashSet()
            tris = ArrayList(tris.filter { it !in badSet })
            for (e in edges.values) tris += Tri(e[0], e[1], i)
        }
        return tris.filter { it.a < n && it.b < n && it.c < n }.map { t ->
            // Antihorario en coordenadas de imagen (y hacia abajo se invierte, da igual: consistente).
            val cross = (px[t.b] - px[t.a]) * (py[t.c] - py[t.a]) - (py[t.b] - py[t.a]) * (px[t.c] - px[t.a])
            if (cross >= 0) intArrayOf(t.a, t.b, t.c) else intArrayOf(t.a, t.c, t.b)
        }
    }
}
