package com.tisma.scanner.recon

import com.tisma.scanner.ins.Vec3
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Modelo 3D calculado EN EL MÓVIL, sin servidor (calidad media).
 *
 *  1. Cada trozo de superficie de la reconstrucción en vivo se dibuja en su propia vista como
 *     mapa de profundidad.
 *  2. Fusión volumétrica TSDF: cada vóxel guarda la distancia (truncada) a la superficie vista
 *     desde cada cámara, promediada. El ruido de cada vista se compensa con las demás.
 *  3. Malla con "surface nets" (un vértice por celda que cruza la superficie), orientada hacia
 *     fuera, y color por vértice tomado de la vista que mejor la ve.
 *
 * Unidades: metros (la escala viene de la INS). [writeStl] exporta en milímetros.
 */
class LocalModel(val xyz: FloatArray, val tris: IntArray, val colors: IntArray) {
    val vertexCount get() = xyz.size / 3
    val triangleCount get() = tris.size / 3

    /**
     * Como trozo dibujable por el visor de la pieza, simplificado a ≤ `maxTris` triángulos para
     * que girarlo sea fluido (el STL exportado conserva toda la resolución).
     */
    fun asFragment(id: Int = -1, maxTris: Int = 30_000): LivePointCloud.Fragment {
        val m = if (triangleCount > maxTris) decimate(maxTris) else this
        return LivePointCloud.Fragment(id, m.xyz, FloatArray(m.xyz.size / 3 * 2), m.tris, null, 0, 0,
            Vec3(0.0, 0.0, -1.0), outward = true, vertexColors = m.colors)
    }

    /**
     * Simplificación por agrupación de vértices: los que caen en la misma celda de una rejilla se
     * funden (posición y color medios) y los triángulos que quedan degenerados se quitan. Se
     * agranda la celda hasta bajar de `target` triángulos. Rápida y mantiene la orientación.
     */
    fun decimate(target: Int): LocalModel {
        var lo0 = Float.MAX_VALUE; var lo1 = Float.MAX_VALUE; var lo2 = Float.MAX_VALUE; var hiMax = -Float.MAX_VALUE
        for (i in 0 until vertexCount) {
            lo0 = minOf(lo0, xyz[3 * i]); lo1 = minOf(lo1, xyz[3 * i + 1]); lo2 = minOf(lo2, xyz[3 * i + 2])
            hiMax = maxOf(hiMax, xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2])
        }
        val extent = maxOf(1e-6f, hiMax - minOf(lo0, lo1, lo2))
        var cell = extent / 256f
        var result = this
        repeat(8) {
            val map = HashMap<Long, Int>()
            val remap = IntArray(vertexCount)
            val acc = ArrayList<DoubleArray>()        // x, y, z, r, g, b, n
            for (i in 0 until vertexCount) {
                val kx = ((xyz[3 * i] - lo0) / cell).toLong(); val ky = ((xyz[3 * i + 1] - lo1) / cell).toLong()
                val kz = ((xyz[3 * i + 2] - lo2) / cell).toLong()
                val key = (kx shl 42) or (ky shl 21) or kz
                val id = map.getOrPut(key) { acc += DoubleArray(7); acc.size - 1 }
                remap[i] = id
                val a = acc[id]; val c = colors[i]
                a[0] += xyz[3 * i].toDouble(); a[1] += xyz[3 * i + 1].toDouble(); a[2] += xyz[3 * i + 2].toDouble()
                a[3] += ((c shr 16) and 0xFF).toDouble(); a[4] += ((c shr 8) and 0xFF).toDouble(); a[5] += (c and 0xFF).toDouble(); a[6] += 1.0
            }
            val nt = ArrayList<Int>()
            val seen = HashSet<Long>()
            for (t in 0 until triangleCount) {
                val a = remap[tris[3 * t]]; val b = remap[tris[3 * t + 1]]; val c = remap[tris[3 * t + 2]]
                if (a == b || b == c || a == c) continue
                val lo = minOf(a, b, c).toLong(); val hi = maxOf(a, b, c).toLong(); val mid = (a + b + c - lo - hi)
                if (!seen.add((lo shl 42) or (mid shl 21) or hi)) continue        // triángulo repetido
                nt += a; nt += b; nt += c
            }
            val nx = FloatArray(acc.size * 3); val nc = IntArray(acc.size)
            for ((i, a) in acc.withIndex()) {
                val n = a[6]
                nx[3 * i] = (a[0] / n).toFloat(); nx[3 * i + 1] = (a[1] / n).toFloat(); nx[3 * i + 2] = (a[2] / n).toFloat()
                nc[i] = (0xFF shl 24) or ((a[3] / n).toInt() shl 16) or ((a[4] / n).toInt() shl 8) or (a[5] / n).toInt()
            }
            result = LocalModel(nx, nt.toIntArray(), nc)
            if (result.triangleCount <= target) return result
            cell *= 1.4f
        }
        return result
    }

    /** STL binario en milímetros (para imprimir o CAD). */
    fun writeStl(f: File) {
        DataOutputStream(f.outputStream().buffered()).use { out ->
            out.write(ByteArray(80).also { "Tisma Scanner - modelo local (mm)".toByteArray().copyInto(it) })
            val bb = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(triangleCount).array())
            for (t in 0 until triangleCount) {
                bb.clear()
                val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
                val n = normal(a, b, c)
                bb.putFloat(n.x.toFloat()); bb.putFloat(n.y.toFloat()); bb.putFloat(n.z.toFloat())
                for (v in intArrayOf(a, b, c)) for (k in 0..2) bb.putFloat(xyz[3 * v + k] * 1000f)
                bb.putShort(0)
                out.write(bb.array())
            }
        }
    }

    /** PLY con color por vértice (metros). */
    fun writePly(f: File) {
        f.bufferedWriter().use { w ->
            w.write("ply\nformat ascii 1.0\ncomment Tisma Scanner: modelo local\nelement vertex $vertexCount\n")
            w.write("property float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\n")
            w.write("element face $triangleCount\nproperty list uchar int vertex_indices\nend_header\n")
            for (i in 0 until vertexCount) {
                val c = colors[i]
                w.write("%.5f %.5f %.5f %d %d %d\n".format(java.util.Locale.US, xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2],
                    (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF))
            }
            for (t in 0 until triangleCount) w.write("3 ${tris[3 * t]} ${tris[3 * t + 1]} ${tris[3 * t + 2]}\n")
        }
    }

    private fun p(i: Int) = Vec3(xyz[3 * i].toDouble(), xyz[3 * i + 1].toDouble(), xyz[3 * i + 2].toDouble())
    private fun normal(a: Int, b: Int, c: Int) = ((p(b) - p(a)) cross (p(c) - p(a))).normalized()

    companion object {
        /**
         * @param resolution vóxeles en el lado mayor de la pieza (128 ≈ 0,5 mm en una pieza de 6 cm)
         * @param depthW, depthH resolución de los mapas de profundidad
         */
        fun build(fragments: List<LivePointCloud.Fragment>, resolution: Int = 128, depthW: Int = 160, depthH: Int = 120,
                  dense: List<DepthPrior.DepthView> = emptyList(),
                  progress: (Float) -> Unit = {}): LocalModel? {
            val frs = fragments.filter { it.view != null && it.tris.size >= 9 && it.gw > 0 }
            if (frs.size < 2) return null
            // Caja de la pieza: percentiles 2-98 de los vértices, más un margen.
            val pts = frs.flatMap { f -> (0 until f.xyz.size / 3).map { i -> floatArrayOf(f.xyz[3 * i], f.xyz[3 * i + 1], f.xyz[3 * i + 2]) } }
            val lo = FloatArray(3); val hi = FloatArray(3)
            for (k in 0..2) {
                val s = pts.map { it[k] }.sorted()
                lo[k] = s[s.size * 2 / 100]; hi[k] = s[s.size * 98 / 100]
            }
            val ext = (0..2).maxOf { hi[it] - lo[it] }.coerceAtLeast(1e-3f)
            for (k in 0..2) { lo[k] -= ext * 0.1f; hi[k] += ext * 0.1f }
            val vox = (0..2).maxOf { hi[it] - lo[it] } / resolution
            val nx = ((hi[0] - lo[0]) / vox).toInt() + 1; val ny = ((hi[1] - lo[1]) / vox).toInt() + 1; val nz = ((hi[2] - lo[2]) / vox).toInt() + 1
            val trunc = 4f * vox
            val tsdf = FloatArray(nx * ny * nz) { 1f }
            val wsum = FloatArray(nx * ny * nz)

            // 1+2: mapa de profundidad de cada trozo (y los densos de la IA) y fusión.
            // Cada vista se funde en paralelo por capas Z (cada vóxel lo toca un solo hilo) y sin crear
            // objetos: las coordenadas de cámara de un vóxel son lineales en i, así que se avanzan
            // sumando un incremento fijo (en el móvil esto eran ~50 millones de Vec3 por modelo).
            fun integrate(v: LivePointCloud.View, depth: DoubleArray, dw: Int, dh: Int, sx: Double, sy: Double, weight: Float) {
                val xa = v.xAxis; val ya = v.yAxis; val za = v.zAxis; val c = v.center
                val fsx = v.f * sx; val fsy = v.f * sy; val cxs = v.cx * sx; val cys = v.cy * sy
                val ix = xa.x * vox; val iy = ya.x * vox; val iz = za.x * vox
                java.util.stream.IntStream.range(0, nz).parallel().forEach { k ->
                    val pz = lo[2] + (k + 0.5) * vox - c.z
                    for (j in 0 until ny) {
                        val py = lo[1] + (j + 0.5) * vox - c.y
                        val px0 = lo[0] + 0.5 * vox - c.x
                        var X = xa.x * px0 + xa.y * py + xa.z * pz
                        var Y = ya.x * px0 + ya.y * py + ya.z * pz
                        var Z = za.x * px0 + za.y * py + za.z * pz
                        var idx = (k * ny + j) * nx
                        for (i in 0 until nx) {
                            if (Z > 1e-4) {
                                val u = (cxs + fsx * X / Z).toInt(); val w = (cys + fsy * Y / Z).toInt()
                                if (u in 0 until dw && w in 0 until dh) {
                                    val d = depth[w * dw + u]
                                    if (!d.isNaN()) {
                                        val sdf = (d - Z).toFloat()
                                        if (sdf >= -trunc) {               // detrás de la superficie: no se sabe
                                            val t = min(1f, sdf / trunc)
                                            val wo = wsum[idx]
                                            tsdf[idx] = (tsdf[idx] * wo + t * weight) / (wo + weight); wsum[idx] = wo + weight
                                        }
                                    }
                                }
                            }
                            X += ix; Y += iy; Z += iz; idx++
                        }
                    }
                }
            }
            val total = frs.size + dense.size
            for ((fi, f) in frs.withIndex()) {
                val v = f.view!!
                val sx = depthW.toDouble() / f.gw; val sy = depthH.toDouble() / f.gh
                integrate(v, median3(rasterize(f, v, depthW, depthH, sx, sy), depthW, depthH), depthW, depthH, sx, sy, 1f)
                progress(0.8f * (fi + 1) / total)
            }
            // Profundidad densa de la IA ya en metros: rellena entre los puntos medidos.
            for ((di, d) in dense.withIndex()) {
                integrate(d.view, DoubleArray(d.depth.size) { d.depth[it].toDouble() }, d.w, d.h,
                    d.w.toDouble() / d.gw, d.h.toDouble() / d.gh, DENSE_WEIGHT)
                progress(0.8f * (frs.size + di + 1) / total)
            }

            // 3: surface nets.
            fun at(i: Int, j: Int, k: Int) = (k * ny + j) * nx + i
            fun inside(idx: Int) = wsum[idx] > 0 && tsdf[idx] < 0
            fun known(idx: Int) = wsum[idx] > 0
            val cellVertex = IntArray(nx * ny * nz) { -1 }
            val verts = ArrayList<Float>()
            for (k in 0 until nz - 1) for (j in 0 until ny - 1) for (i in 0 until nx - 1) {
                var any = false; var all = true; var allKnown = true
                for (c in 0..7) {
                    val idx = at(i + (c and 1), j + ((c shr 1) and 1), k + ((c shr 2) and 1))
                    if (!known(idx)) allKnown = false
                    if (inside(idx)) any = true else all = false
                }
                if (!allKnown || !any || all) continue
                // Media de los cruces de las aristas de la celda.
                var px = 0.0; var py = 0.0; var pz = 0.0; var n = 0
                for ((a, b) in EDGES) {
                    val ia = at(i + (a and 1), j + ((a shr 1) and 1), k + ((a shr 2) and 1))
                    val ib = at(i + (b and 1), j + ((b shr 1) and 1), k + ((b shr 2) and 1))
                    val da = tsdf[ia]; val db = tsdf[ib]
                    if ((da < 0) == (db < 0)) continue
                    val t = da / (da - db)
                    px += (a and 1) + t * ((b and 1) - (a and 1))
                    py += ((a shr 1) and 1) + t * (((b shr 1) and 1) - ((a shr 1) and 1))
                    pz += ((a shr 2) and 1) + t * (((b shr 2) and 1) - ((a shr 2) and 1))
                    n++
                }
                cellVertex[at(i, j, k)] = verts.size / 3
                verts += lo[0] + (i + 0.5f + (px / n).toFloat()) * vox
                verts += lo[1] + (j + 0.5f + (py / n).toFloat()) * vox
                verts += lo[2] + (k + 0.5f + (pz / n).toFloat()) * vox
            }
            val tris = ArrayList<Int>()
            // Cada arista de la rejilla que cruza la superficie une las 4 celdas que la rodean.
            fun quad(c0: Int, c1: Int, c2: Int, c3: Int, flip: Boolean) {
                val v = intArrayOf(cellVertex[c0], cellVertex[c1], cellVertex[c2], cellVertex[c3])
                if (v.any { it < 0 }) return
                if (flip) { tris += v[0]; tris += v[2]; tris += v[1]; tris += v[0]; tris += v[3]; tris += v[2] }
                else { tris += v[0]; tris += v[1]; tris += v[2]; tris += v[0]; tris += v[2]; tris += v[3] }
            }
            for (k in 1 until nz - 1) for (j in 1 until ny - 1) for (i in 1 until nx - 1) {
                val c = at(i, j, k)
                if (!known(c)) continue
                val ins = inside(c)
                // Arista en X (i -> i+1): celdas (i, j-1..j, k-1..k).
                if (i + 1 < nx && known(at(i + 1, j, k)) && ins != inside(at(i + 1, j, k)))
                    quad(at(i, j - 1, k - 1), at(i, j, k - 1), at(i, j, k), at(i, j - 1, k), ins)
                if (j + 1 < ny && known(at(i, j + 1, k)) && ins != inside(at(i, j + 1, k)))
                    quad(at(i - 1, j, k - 1), at(i - 1, j, k), at(i, j, k), at(i, j, k - 1), ins)
                if (k + 1 < nz && known(at(i, j, k + 1)) && ins != inside(at(i, j, k + 1)))
                    quad(at(i - 1, j - 1, k), at(i, j - 1, k), at(i, j, k), at(i - 1, j, k), ins)
            }
            if (tris.isEmpty()) return null
            val xyz = verts.toFloatArray()
            // Orientación hacia fuera: la normal debe apuntar hacia donde la distancia crece (fuera).
            fun sdfAt(x: Float, y: Float, z: Float): Float? {
                val i = ((x - lo[0]) / vox - 0.5f).toInt().coerceIn(0, nx - 1)
                val j = ((y - lo[1]) / vox - 0.5f).toInt().coerceIn(0, ny - 1)
                val k = ((z - lo[2]) / vox - 0.5f).toInt().coerceIn(0, nz - 1)
                val idx = at(i, j, k)
                return if (wsum[idx] > 0) tsdf[idx] else null
            }
            for (t in 0 until tris.size / 3) {
                val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
                val ux = xyz[3 * b] - xyz[3 * a]; val uy = xyz[3 * b + 1] - xyz[3 * a + 1]; val uz = xyz[3 * b + 2] - xyz[3 * a + 2]
                val wx = xyz[3 * c] - xyz[3 * a]; val wy = xyz[3 * c + 1] - xyz[3 * a + 1]; val wz = xyz[3 * c + 2] - xyz[3 * a + 2]
                val nxv = uy * wz - uz * wy; val nyv = uz * wx - ux * wz; val nzv = ux * wy - uy * wx
                val nl = kotlin.math.sqrt(nxv * nxv + nyv * nyv + nzv * nzv); if (nl < 1e-12f) continue
                val cx = (xyz[3 * a] + xyz[3 * b] + xyz[3 * c]) / 3; val cy = (xyz[3 * a + 1] + xyz[3 * b + 1] + xyz[3 * c + 1]) / 3
                val cz = (xyz[3 * a + 2] + xyz[3 * b + 2] + xyz[3 * c + 2]) / 3
                val h = vox * 1.5f / nl
                val fwd = sdfAt(cx + nxv * h, cy + nyv * h, cz + nzv * h) ?: continue
                val back = sdfAt(cx - nxv * h, cy - nyv * h, cz - nzv * h) ?: continue
                if (fwd < back) { tris[3 * t + 1] = c; tris[3 * t + 2] = b }
            }
            val colors = colorVertices(xyz, tris, frs)
            progress(1f)
            return LocalModel(xyz, tris.toIntArray(), colors)
        }

        /** Peso de cada mapa denso de la IA frente a una superficie medida (1). */
        const val DENSE_WEIGHT = 1f

        private val EDGES = listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7, 0 to 2, 1 to 3, 4 to 6, 5 to 7, 0 to 4, 1 to 5, 2 to 6, 3 to 7)

        /** Mediana 3×3 (quita picos aislados; los bordes sin dato se quedan sin dato). */
        private fun median3(d: DoubleArray, w: Int, h: Int): DoubleArray {
            val out = d.copyOf(); val buf = DoubleArray(9)
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                if (d[y * w + x].isNaN()) continue
                var n = 0
                for (j in -1..1) for (i in -1..1) { val v = d[(y + j) * w + x + i]; if (!v.isNaN()) buf[n++] = v }
                if (n >= 5) { java.util.Arrays.sort(buf, 0, n); out[y * w + x] = buf[n / 2] }
            }
            return out
        }

        /** Profundidad (m) de los triángulos del trozo vistos desde su cámara; NaN = sin dato. */
        private fun rasterize(f: LivePointCloud.Fragment, v: LivePointCloud.View, w: Int, h: Int, sx: Double, sy: Double): DoubleArray {
            val depth = DoubleArray(w * h) { Double.NaN }
            val n = f.xyz.size / 3
            val px = DoubleArray(n); val py = DoubleArray(n); val pz = DoubleArray(n)
            for (i in 0 until n) {
                val p = Vec3(f.xyz[3 * i].toDouble(), f.xyz[3 * i + 1].toDouble(), f.xyz[3 * i + 2].toDouble())
                val q = v.project(p)
                px[i] = (q?.get(0) ?: Double.NaN) * sx; py[i] = (q?.get(1) ?: Double.NaN) * sy; pz[i] = v.depth(p)
            }
            for (t in 0 until f.tris.size / 3) {
                val a = f.tris[3 * t]; val b = f.tris[3 * t + 1]; val c = f.tris[3 * t + 2]
                if (px[a].isNaN() || px[b].isNaN() || px[c].isNaN()) continue
                val x0 = max(0, floor(min(px[a], min(px[b], px[c]))).toInt()); val x1 = min(w - 1, floor(max(px[a], max(px[b], px[c]))).toInt())
                val y0 = max(0, floor(min(py[a], min(py[b], py[c]))).toInt()); val y1 = min(h - 1, floor(max(py[a], max(py[b], py[c]))).toInt())
                val den = (py[b] - py[c]) * (px[a] - px[c]) + (px[c] - px[b]) * (py[a] - py[c])
                if (abs(den) < 1e-12) continue
                for (y in y0..y1) for (x in x0..x1) {
                    val cx = x + 0.5; val cy = y + 0.5
                    val l0 = ((py[b] - py[c]) * (cx - px[c]) + (px[c] - px[b]) * (cy - py[c])) / den
                    val l1 = ((py[c] - py[a]) * (cx - px[c]) + (px[a] - px[c]) * (cy - py[c])) / den
                    val l2 = 1 - l0 - l1
                    if (l0 < 0 || l1 < 0 || l2 < 0) continue
                    val z = l0 * pz[a] + l1 * pz[b] + l2 * pz[c]
                    val i = y * w + x
                    if (depth[i].isNaN() || z < depth[i]) depth[i] = z
                }
            }
            return depth
        }

        /** Color de cada vértice: el de la vista que lo mira más de frente (sin textura: gris). */
        private fun colorVertices(xyz: FloatArray, tris: List<Int>, frs: List<LivePointCloud.Fragment>): IntArray {
            val n = xyz.size / 3
            val nrm = DoubleArray(n * 3)
            for (t in 0 until tris.size / 3) {
                val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
                fun p(i: Int) = Vec3(xyz[3 * i].toDouble(), xyz[3 * i + 1].toDouble(), xyz[3 * i + 2].toDouble())
                val fn = (p(b) - p(a)) cross (p(c) - p(a))
                for (v in intArrayOf(a, b, c)) { nrm[3 * v] += fn.x; nrm[3 * v + 1] += fn.y; nrm[3 * v + 2] += fn.z }
            }
            return IntArray(n) { i ->
                val p = Vec3(xyz[3 * i].toDouble(), xyz[3 * i + 1].toDouble(), xyz[3 * i + 2].toDouble())
                val nv = Vec3(nrm[3 * i], nrm[3 * i + 1], nrm[3 * i + 2]).normalized()
                var best = -1.0; var col = 0xFF9E9E9E.toInt()
                for (f in frs) {
                    val th = f.thumb ?: continue
                    val v = f.view ?: continue
                    val facing = -(nv dot v.zAxis)
                    if (facing <= best) continue
                    val q = v.project(p) ?: continue
                    val u = (q[0] * f.tw / f.gw).toInt(); val w = (q[1] * f.th / f.gh).toInt()
                    if (u !in 0 until f.tw || w !in 0 until f.th) continue
                    best = facing; col = th[w * f.tw + u]
                }
                col
            }
        }
    }
}
