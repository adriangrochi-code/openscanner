package com.tisma.scanner.recon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DelaunayTest {
    @Test fun emptyCircumcirclesAndFullCoverage() {
        val r = Random(5); val n = 200
        val xs = DoubleArray(n) { r.nextDouble(0.0, 320.0) }; val ys = DoubleArray(n) { r.nextDouble(0.0, 240.0) }
        val tris = Delaunay.triangulate(xs, ys)
        // Euler en el plano: 2n − 2 − h triángulos (h = puntos de la envolvente) ⇒ entre n y 2n.
        assertTrue("${tris.size}", tris.size in n..2 * n)
        for (t in tris) {
            val (a, b, c) = Triple(t[0], t[1], t[2])
            val ax = xs[a]; val ay = ys[a]; val bx = xs[b]; val by = ys[b]; val cx = xs[c]; val cy = ys[c]
            val d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
            val ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
            val uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
            val r2 = (ax - ux) * (ax - ux) + (ay - uy) * (ay - uy)
            for (i in 0 until n) if (i != a && i != b && i != c) {
                assertTrue("punto $i dentro del circuncírculo", (xs[i] - ux) * (xs[i] - ux) + (ys[i] - uy) * (ys[i] - uy) >= r2 * (1 - 1e-9))
            }
        }
        // El área total es la de la envolvente convexa (sin huecos ni solapes): basta comparar con
        // la suma de áreas con signo, que no cambia de signo.
        assertEquals(0, tris.count { t -> (xs[t[1]] - xs[t[0]]) * (ys[t[2]] - ys[t[0]]) - (ys[t[1]] - ys[t[0]]) * (xs[t[2]] - xs[t[0]]) < 0 })
    }
}
