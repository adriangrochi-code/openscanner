package com.tisma.scanner.recon

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Profundidad densa de una red (Depth Anything 3 Small) llevada a METROS con los puntos que sí
 * medimos (triangulados con la odometría): la red sólo rellena entre medidas reales, no inventa
 * la escala ni la forma de las zonas que no vio.
 *
 * La salida de la red es relativa (escala y desplazamiento desconocidos) y según el modelo puede
 * ser profundidad o profundidad inversa: se prueban ambas con un ajuste robusto (Huber, IRLS) y
 * se queda la que mejor explica los puntos medidos.
 */
object DepthPrior {
    /** pred → metros: inverse = false: d = a·p + b ; inverse = true: 1/d = a·p + b. */
    data class Fit(val inverse: Boolean, val a: Double, val b: Double, val relRms: Double, val inliers: Int)

    /** Profundidad densa en metros de una vista (NaN = sin dato), en una rejilla w×h. */
    class DepthView(val view: LivePointCloud.View, val w: Int, val h: Int, val depth: FloatArray,
                    /** Tamaño de la imagen en la que están los intrínsecos de `view`. */
                    val gw: Int, val gh: Int)

    /**
     * @param samples (u, v, profundidad medida en m) en píxeles de la rejilla de `pred`
     */
    fun fit(pred: FloatArray, w: Int, h: Int, samples: List<Triple<Double, Double, Double>>): Fit? {
        val pairs = samples.mapNotNull { (u, v, d) -> if (d <= 0) null else bilinear(pred, w, h, u, v)?.let { it to d } }
        if (pairs.size < 8) return null
        return listOf(false, true).mapNotNull { inv -> robustAffine(pairs, inv) }.minByOrNull { it.relRms }
    }

    /** Valor en (u, v) con píxeles centrados en x + 0,5 (interpolado: en caras oblicuas importa). */
    private fun bilinear(p: FloatArray, w: Int, h: Int, u: Double, v: Double): Double? {
        val x = u - 0.5; val y = v - 0.5
        val x0 = kotlin.math.floor(x).toInt(); val y0 = kotlin.math.floor(y).toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= w || y0 + 1 >= h) return null
        val fx = x - x0; val fy = y - y0
        val a = p[y0 * w + x0]; val b = p[y0 * w + x0 + 1]; val c = p[(y0 + 1) * w + x0]; val d = p[(y0 + 1) * w + x0 + 1]
        if (!(a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite())) return null
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    private fun robustAffine(pairs: List<Pair<Double, Double>>, inverse: Boolean): Fit? {
        val xs = pairs.map { it.first }
        val ys = pairs.map { if (inverse) 1.0 / it.second else it.second }
        val w = DoubleArray(xs.size) { 1.0 }
        var a = 0.0; var b = 0.0
        repeat(10) {
            var sw = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (i in xs.indices) { sw += w[i]; sx += w[i] * xs[i]; sy += w[i] * ys[i]; sxx += w[i] * xs[i] * xs[i]; sxy += w[i] * xs[i] * ys[i] }
            val den = sw * sxx - sx * sx
            if (abs(den) < 1e-15) return null
            a = (sw * sxy - sx * sy) / den; b = (sy - a * sx) / sw
            // Huber sobre el residuo relativo (escala 2 %).
            val res = xs.indices.map { abs(a * xs[it] + b - ys[it]) / abs(ys[it]) }
            val s = maxOf(0.02, res.sorted()[res.size / 2] * 1.4826)
            for (i in xs.indices) w[i] = if (res[i] <= s) 1.0 else s / res[i]
        }
        // Error relativo en PROFUNDIDAD (comparable entre los dos modelos), sobre los no atípicos.
        val rel = xs.indices.map { i ->
            val d = toDepth(xs[i], a, b, inverse)
            if (d.isNaN()) 1.0 else abs(d - pairs[i].second) / pairs[i].second
        }.sorted()
        val keep = rel.take(maxOf(3, (rel.size * 0.8).toInt()))
        val rms = sqrt(keep.sumOf { it * it } / keep.size)
        return Fit(inverse, a, b, rms, rel.count { it < 0.03 })
    }

    private fun toDepth(p: Double, a: Double, b: Double, inverse: Boolean): Double {
        val y = a * p + b
        return if (inverse) (if (y > 1e-6) 1.0 / y else Double.NaN) else (if (y > 0) y else Double.NaN)
    }

    /**
     * Mapa en metros. Se recorta a la franja de profundidades de los puntos medidos (con margen):
     * fuera de ella (fondo, mesa lejana) la red no tiene apoyo y no se usa.
     */
    fun toMetric(pred: FloatArray, fit: Fit, minD: Double, maxD: Double, w: Int = 0, h: Int = 0, edgeRel: Double = 0.02): FloatArray {
        val m = FloatArray(pred.size) { i ->
            val d = toDepth(pred[i].toDouble(), fit.a, fit.b, fit.inverse)
            if (d.isNaN() || d < minD || d > maxD) Float.NaN else d.toFloat()
        }
        if (w <= 0 || h <= 0) return m
        // Bordes (salto de profundidad en el entorno 3×3): ahí el píxel mezcla pieza y fondo.
        val out = m.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val c = m[y * w + x]; if (c.isNaN()) continue
            var lo = c; var hi = c; var gap = false
            for (j in -1..1) for (i in -1..1) {
                val xx = x + i; val yy = y + j
                if (xx !in 0 until w || yy !in 0 until h) continue
                val v = m[yy * w + xx]
                if (v.isNaN()) { gap = true; continue }
                if (v < lo) lo = v; if (v > hi) hi = v
            }
            if (gap || (hi - lo) > edgeRel * c) out[y * w + x] = Float.NaN
        }
        return out
    }
}
