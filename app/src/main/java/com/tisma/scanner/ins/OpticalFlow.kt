package com.tisma.scanner.ins

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Flujo óptico global entre dos fotogramas pequeños en escala de grises (p.ej. 160×120):
 * Lucas-Kanade piramidal (2 niveles) sobre una rejilla de puntos con textura (Shi-Tomasi).
 * Kotlin puro, sin OpenCV. Devuelve el desplazamiento mediano y cuántos puntos lo apoyan.
 */
object OpticalFlow {

    data class Result(
        /** Desplazamiento mediano (px) del fotograma anterior al actual. */
        val dx: Double,
        val dy: Double,
        /** Puntos seguidos con éxito. */
        val tracked: Int,
        /** Dispersión robusta (MAD, px): alta = movimiento no uniforme o seguimiento malo. */
        val spread: Double,
        /** Desplazamiento de cada punto (para quitar la rotación punto a punto si hace falta). */
        val points: List<FloatArray> = emptyList(),
    )

    class Image(val w: Int, val h: Int, val px: FloatArray) {
        operator fun get(x: Int, y: Int) = px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

        fun bilinear(x: Double, y: Double): Double {
            val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
            val fx = x - x0; val fy = y - y0
            return (this[x0, y0] * (1 - fx) + this[x0 + 1, y0] * fx) * (1 - fy) +
                (this[x0, y0 + 1] * (1 - fx) + this[x0 + 1, y0 + 1] * fx) * fy
        }

        fun half(): Image {
            val w2 = w / 2; val h2 = h / 2
            val out = FloatArray(w2 * h2)
            for (y in 0 until h2) for (x in 0 until w2) {
                out[y * w2 + x] = (this[2 * x, 2 * y] + this[2 * x + 1, 2 * y] + this[2 * x, 2 * y + 1] + this[2 * x + 1, 2 * y + 1]) / 4f
            }
            return Image(w2, h2, out)
        }
    }

    private const val HALF_WIN = 3          // ventana 7×7
    private const val ITER = 8
    private const val MIN_EIG = 25.0        // textura mínima (suma de gradientes² en la ventana)

    fun track(prev: Image, cur: Image, gridX: Int = 10, gridY: Int = 8): Result {
        val p0 = listOf(prev, prev.half()); val p1 = listOf(cur, cur.half())
        val m = 8
        val res = ArrayList<FloatArray>()
        for (gy in 0 until gridY) for (gx in 0 until gridX) {
            val x = m + (prev.w - 2 * m) * (gx + 0.5) / gridX
            val y = m + (prev.h - 2 * m) * (gy + 0.5) / gridY
            if (minEigen(prev, x, y) < MIN_EIG) continue
            var dx = 0.0; var dy = 0.0
            var ok = true
            for (level in 1 downTo 0) {
                val s = if (level == 1) 0.5 else 1.0
                if (level == 0) { dx *= 2; dy *= 2 }
                val r = lk(p0[level], p1[level], x * s, y * s, dx, dy) ?: run { ok = false; null } ?: break
                dx = r[0]; dy = r[1]
            }
            if (ok && abs(dx) < prev.w / 4 && abs(dy) < prev.h / 4) res += floatArrayOf(x.toFloat(), y.toFloat(), dx.toFloat(), dy.toFloat())
        }
        if (res.size < 3) return Result(0.0, 0.0, res.size, Double.MAX_VALUE)
        val mdx = median(res.map { it[2].toDouble() }); val mdy = median(res.map { it[3].toDouble() })
        val dev = res.map { sqrt((it[2] - mdx) * (it[2] - mdx) + (it[3] - mdy) * (it[3] - mdy)) }
        val mad = median(dev)
        val inliers = res.filterIndexed { i, _ -> dev[i] <= 3 * mad + 0.3 }
        return Result(
            median(inliers.map { it[2].toDouble() }), median(inliers.map { it[3].toDouble() }),
            inliers.size, mad, inliers,
        )
    }

    /**
     * Sigue puntos concretos de `prev` a `cur` (pirámide de 3 niveles) con comprobación ida y
     * vuelta: si al volver no cae a menos de `fbMaxPx` del origen, el seguimiento no vale (null).
     */
    fun trackPoints(prev: Image, cur: Image, pts: List<DoubleArray>, fbMaxPx: Double = 1.0): List<DoubleArray?> {
        val p0 = listOf(prev, prev.half()).let { it + it[1].half() }
        val p1 = listOf(cur, cur.half()).let { it + it[1].half() }
        fun one(a: List<Image>, b: List<Image>, x: Double, y: Double): DoubleArray? {
            var dx = 0.0; var dy = 0.0
            for (level in 2 downTo 0) {
                val s = 1.0 / (1 shl level)
                if (level < 2) { dx *= 2; dy *= 2 }
                val r = lk(a[level], b[level], x * s, y * s, dx, dy) ?: return null
                dx = r[0]; dy = r[1]
            }
            return doubleArrayOf(x + dx, y + dy)
        }
        return pts.map { p ->
            val f = one(p0, p1, p[0], p[1]) ?: return@map null
            if (f[0] < 2 || f[1] < 2 || f[0] > cur.w - 3 || f[1] > cur.h - 3) return@map null
            val back = one(p1, p0, f[0], f[1]) ?: return@map null
            if (hypot(back[0] - p[0], back[1] - p[1]) > fbMaxPx) null else f
        }
    }

    /**
     * Esquinas de Shi-Tomasi: la mejor de cada celda de una rejilla (reparto uniforme), saltando
     * las celdas que ya tienen un punto (`occupied`).
     */
    fun corners(im: Image, cellsX: Int, cellsY: Int, occupied: List<DoubleArray> = emptyList(), minEig: Double = MIN_EIG): List<DoubleArray> {
        val cw = im.w.toDouble() / cellsX; val ch = im.h.toDouble() / cellsY
        val taken = BooleanArray(cellsX * cellsY)
        for (p in occupied) {
            val cx = (p[0] / cw).toInt(); val cy = (p[1] / ch).toInt()
            if (cx in 0 until cellsX && cy in 0 until cellsY) taken[cy * cellsX + cx] = true
        }
        val out = ArrayList<DoubleArray>()
        val m = HALF_WIN + 2
        for (cy in 0 until cellsY) for (cx in 0 until cellsX) {
            if (taken[cy * cellsX + cx]) continue
            var best = minEig; var bx = -1.0; var by = -1.0
            var y = maxOf(m.toDouble(), cy * ch + 1)
            while (y < minOf(im.h - m.toDouble(), (cy + 1) * ch)) {
                var x = maxOf(m.toDouble(), cx * cw + 1)
                while (x < minOf(im.w - m.toDouble(), (cx + 1) * cw)) {
                    val e = minEigen(im, x, y)
                    if (e > best) { best = e; bx = x; by = y }
                    x += 2.0
                }
                y += 2.0
            }
            if (bx >= 0) out += doubleArrayOf(bx, by)
        }
        return out
    }

    /** Menor autovalor del tensor de estructura en la ventana (Shi-Tomasi): ¿hay textura? */
    private fun minEigen(im: Image, x: Double, y: Double): Double {
        var gxx = 0.0; var gyy = 0.0; var gxy = 0.0
        val cx = x.toInt(); val cy = y.toInt()
        for (j in -HALF_WIN..HALF_WIN) for (i in -HALF_WIN..HALF_WIN) {
            val gx = (im[cx + i + 1, cy + j] - im[cx + i - 1, cy + j]) / 2.0
            val gy = (im[cx + i, cy + j + 1] - im[cx + i, cy + j - 1]) / 2.0
            gxx += gx * gx; gyy += gy * gy; gxy += gx * gy
        }
        val tr = gxx + gyy; val det = gxx * gyy - gxy * gxy
        return tr / 2 - sqrt(maxOf(tr * tr / 4 - det, 0.0))
    }

    /** Lucas-Kanade iterativo en un nivel. Devuelve el desplazamiento refinado o null. */
    private fun lk(a: Image, b: Image, x: Double, y: Double, dx0: Double, dy0: Double): DoubleArray? {
        var gxx = 0.0; var gyy = 0.0; var gxy = 0.0
        val n = (2 * HALF_WIN + 1) * (2 * HALF_WIN + 1)
        val ix = DoubleArray(n); val iy = DoubleArray(n); val iv = DoubleArray(n)
        var k = 0
        for (j in -HALF_WIN..HALF_WIN) for (i in -HALF_WIN..HALF_WIN) {
            val px = x + i; val py = y + j
            ix[k] = (a.bilinear(px + 1, py) - a.bilinear(px - 1, py)) / 2
            iy[k] = (a.bilinear(px, py + 1) - a.bilinear(px, py - 1)) / 2
            iv[k] = a.bilinear(px, py)
            gxx += ix[k] * ix[k]; gyy += iy[k] * iy[k]; gxy += ix[k] * iy[k]
            k++
        }
        val det = gxx * gyy - gxy * gxy
        if (det < 1e-6) return null
        var dx = dx0; var dy = dy0
        repeat(ITER) {
            var bx = 0.0; var by = 0.0
            k = 0
            for (j in -HALF_WIN..HALF_WIN) for (i in -HALF_WIN..HALF_WIN) {
                val diff = iv[k] - b.bilinear(x + i + dx, y + j + dy)
                bx += diff * ix[k]; by += diff * iy[k]
                k++
            }
            val ux = (gyy * bx - gxy * by) / det
            val uy = (gxx * by - gxy * bx) / det
            dx += ux; dy += uy
            if (ux * ux + uy * uy < 1e-4) return doubleArrayOf(dx, dy)
        }
        return doubleArrayOf(dx, dy)
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}

/**
 * Velocidad lateral de la cámara a partir del flujo óptico, la rotación (giróscopo/INS) y la
 * distancia a la escena (enfoque):
 *   flujo por rotación (centro):   Δu = −f·r_y ,  Δv = +f·r_x      (r en ejes de cámara)
 *   flujo por traslación:          Δu = −f·t_x/Z , Δv = −f·t_y/Z
 * => t_x = −(Δu − Δu_rot)·Z/f ,  t_y = −(Δv − Δv_rot)·Z/f
 * La componente a lo largo del eje óptico no se puede ver con el flujo medio: sólo 2 ejes.
 */
object OpticalVelocity {

    /**
     * Ejes de la cámara (x a la derecha y y hacia abajo en la imagen SIN rotar, z hacia delante)
     * expresados en ejes del dispositivo, para una cámara TRASERA con esa SENSOR_ORIENTATION.
     * (90°: la imagen cruda girada 90° a la derecha queda derecha en vertical.)
     */
    fun cameraAxesInDevice(sensorOrientation: Int): Triple<Vec3, Vec3, Vec3> {
        val z = Vec3(0.0, 0.0, -1.0)
        return when (((sensorOrientation % 360) + 360) % 360) {
            0 -> Triple(Vec3(1.0, 0.0, 0.0), Vec3(0.0, -1.0, 0.0), z)
            180 -> Triple(Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), z)
            270 -> Triple(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0), z)
            else -> Triple(Vec3(0.0, -1.0, 0.0), Vec3(-1.0, 0.0, 0.0), z)   // 90 (la habitual)
        }
    }

    data class Measurement(val dirWorld1: Vec3, val v1: Double, val dirWorld2: Vec3, val v2: Double, val sigma: Double)

    /**
     * @param flow flujo entre los fotogramas (px de la imagen de flujo)
     * @param f focal en px de la imagen de flujo
     * @param depthM distancia a la escena (m), del enfoque
     * @param dt tiempo entre fotogramas (s)
     * @param q0, q1 orientación del dispositivo (dispositivo->mundo) en cada fotograma
     */
    fun estimate(flow: OpticalFlow.Result, f: Double, depthM: Double, dt: Double, q0: Quat, q1: Quat,
                 sensorOrientation: Int): Measurement? {
        if (flow.tracked < 12 || flow.spread > 1.5 || dt <= 0 || depthM !in 0.03..3.0) return null
        val (cx, cy, cz) = cameraAxesInDevice(sensorOrientation)
        // Giro del dispositivo entre fotogramas, en ejes del dispositivo, y luego de la cámara.
        val rDev = rotationVector((q0.conj() * q1).normalized())
        val rx = rDev dot cx; val ry = rDev dot cy
        val du = flow.dx - (-f * ry)
        val dv = flow.dy - (f * rx)
        val tx = -du * depthM / f
        val ty = -dv * depthM / f
        // Dirección de los ejes x/y de la cámara en el mundo (en el fotograma actual).
        val d1 = q1.rotate(cx); val d2 = q1.rotate(cy)
        // Incertidumbre: ~15 % por la distancia (enfoque, escena no plana) + ruido del flujo.
        val speed = sqrt(tx * tx + ty * ty) / dt
        val sigma = 0.15 * speed + (flow.spread + 0.1) * depthM / f / dt * 0.5 + 0.005
        return Measurement(d1, tx / dt, d2, ty / dt, sigma)
    }

    private fun rotationVector(q: Quat): Vec3 {
        val qq = if (q.w < 0) Quat(-q.w, -q.x, -q.y, -q.z) else q
        val s = sqrt(qq.x * qq.x + qq.y * qq.y + qq.z * qq.z)
        if (s < 1e-12) return Vec3(qq.x * 2, qq.y * 2, qq.z * 2)
        val angle = 2 * kotlin.math.atan2(s, qq.w)
        return Vec3(qq.x, qq.y, qq.z) * (angle / s)
    }
}
