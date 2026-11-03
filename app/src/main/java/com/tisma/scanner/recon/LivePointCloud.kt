package com.tisma.scanner.recon

import com.tisma.scanner.ins.OpticalFlow
import com.tisma.scanner.ins.Vec3
import java.io.File
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Reconstrucción en vivo (vista previa) mientras se graba el vídeo 360.
 *
 * Etapa 1 · forma: en cada fotograma clave se siguen esquinas (Lucas-Kanade con ida y vuelta)
 * desde el fotograma anterior; cuando un punto se ha visto desde direcciones que difieren al
 * menos `minParallaxDeg`, se triangula con todas sus vistas (mínimos cuadrados de rayos) y
 * aparece en la nube, todavía SIN color.
 * Etapa 2 · color: más tarde (cuando el punto ya no se ve), se pinta con el color medio de las
 * miniaturas en que apareció.
 *
 * Es una vista previa: el modelo final lo hace el servidor con todas las fotos a resolución
 * completa. Aquí manda la velocidad (imágenes de 320×240, unos cientos de puntos por vista).
 */
class LivePointCloud(
    private val minParallaxDeg: Double = 3.0,
    private val maxReprojPx: Double = 1.5,
    private val maxTracks: Int = 300,
    private val maxPoints: Int = 60_000,
    private val maxThumbs: Int = 400,
) {
    /**
     * Pose de una vista: ejes de la cámara (x derecha, y abajo, z delante) en el mundo y su centro.
     * `f`, `cx`, `cy` en píxeles de la imagen en grises que se pasa.
     */
    class View(val xAxis: Vec3, val yAxis: Vec3, val zAxis: Vec3, val center: Vec3, val f: Double, val cx: Double, val cy: Double) {
        fun ray(u: Double, v: Double): Vec3 = (xAxis * ((u - cx) / f) + yAxis * ((v - cy) / f) + zAxis).normalized()
        fun project(p: Vec3): DoubleArray? {
            val d = p - center
            val z = d dot zAxis
            if (z <= 1e-4) return null
            return doubleArrayOf(cx + f * (d dot xAxis) / z, cy + f * (d dot yAxis) / z)
        }
        fun depth(p: Vec3) = (p - center) dot zAxis
    }

    /**
     * Trozo de superficie visto desde una vista: triángulos entre los puntos triangulados que se
     * ven en ella (Delaunay en la imagen), con la miniatura de esa vista como textura.
     * `xyz` en metros (mundo), `uv` en píxeles de la miniatura, `tris` índices de 3 en 3.
     */
    class Fragment(val id: Int, val xyz: FloatArray, val uv: FloatArray, val tris: IntArray,
                   val thumb: IntArray?, val tw: Int, val th: Int, val viewDir: Vec3)

    private val fragments = ArrayList<Fragment>()
    /** Nueva superficie sólo si la vista difiere al menos esto de las que ya hay (grados). */
    var fragmentEveryDeg = 12.0

    private class Keyframe(val view: View, val thumb: IntArray?, val tw: Int, val th: Int, val gw: Int, val gh: Int)
    private class Obs(val kf: Int, val u: Double, val v: Double)
    private class Track(val obs: ArrayList<Obs> = ArrayList(), var point: Int = -1)

    private val keyframes = ArrayList<Keyframe?>()
    private var prevImage: OpticalFlow.Image? = null
    private var tracks = ArrayList<Track>()
    private val px = ArrayList<Vec3>()
    private val colors = ArrayList<Int>()          // 0 = aún sin color
    private val pointObs = ArrayList<List<Obs>>()
    /** Ventana de profundidad válida (m): fuera de ella el punto es fondo o un error. */
    @Volatile var depthRange: ClosedFloatingPointRange<Double> = 0.03..3.0

    val keyframeCount get() = synchronized(this) { keyframes.size }

    /**
     * Añade un fotograma clave: imagen en grises (p.ej. 320×240), miniatura RGB opcional para
     * el color (ARGB, `tw`×`th`, misma encuadre) y su pose.
     */
    @Synchronized fun addKeyframe(gray: OpticalFlow.Image, view: View, thumb: IntArray? = null, tw: Int = 0, th: Int = 0) {
        val k = keyframes.size
        keyframes += Keyframe(view, thumb, tw, th, gray.w, gray.h)
        // Las miniaturas viejas ya se usaron para colorear: se liberan.
        if (k - maxThumbs >= 0) keyframes[k - maxThumbs]?.let { keyframes[k - maxThumbs] = Keyframe(it.view, null, 0, 0, it.gw, it.gh) }
        val prev = prevImage
        if (prev != null && tracks.isNotEmpty()) {
            val last = tracks.map { val o = it.obs.last(); doubleArrayOf(o.u, o.v) }
            val moved = OpticalFlow.trackPoints(prev, gray, last)
            val alive = ArrayList<Track>()
            for ((i, t) in tracks.withIndex()) {
                val p = moved[i] ?: continue
                t.obs += Obs(k, p[0], p[1])
                alive += t
                triangulate(t)
            }
            tracks = alive
        }
        // Puntos nuevos donde no queda ninguno seguido.
        if (tracks.size < maxTracks) {
            val occupied = tracks.map { val o = it.obs.last(); doubleArrayOf(o.u, o.v) }
            for (c in OpticalFlow.corners(gray, 20, 15, occupied).take(maxTracks - tracks.size)) {
                tracks += Track(arrayListOf(Obs(k, c[0], c[1])))
            }
        }
        prevImage = gray
        buildFragment(k)
    }

    /** Superficie desde la vista k, si mira desde una dirección nueva. */
    private fun buildFragment(k: Int) {
        val kf = keyframes[k] ?: return
        val dir = kf.view.zAxis
        if (fragments.any { Math.toDegrees(acos((it.viewDir dot dir).coerceIn(-1.0, 1.0))) < fragmentEveryDeg }) return
        val vis = tracks.filter { it.point >= 0 && it.obs.last().kf == k }
        if (vis.size < 12) return
        val us = DoubleArray(vis.size) { vis[it].obs.last().u }
        val vs = DoubleArray(vis.size) { vis[it].obs.last().v }
        val pts = vis.map { px[it.point] }
        val depth = pts.map { kf.view.depth(it) }
        val maxEdgePx = kf.gw * 0.12
        val tris = ArrayList<Int>()
        for (t in Delaunay.triangulate(us, vs)) {
            // Fuera: triángulos enormes (unen zonas separadas) o que saltan de profundidad (borde
            // de la pieza contra el fondo) o casi de canto respecto a la cámara.
            var ok = true
            for (e in 0..2) {
                val a = t[e]; val b = t[(e + 1) % 3]
                if (kotlin.math.hypot(us[a] - us[b], vs[a] - vs[b]) > maxEdgePx) ok = false
                if (kotlin.math.abs(depth[a] - depth[b]) > 0.12 * minOf(depth[a], depth[b])) ok = false
            }
            if (!ok) continue
            val n = (pts[t[1]] - pts[t[0]]) cross (pts[t[2]] - pts[t[0]])
            if (n.norm() < 1e-12) continue
            if (kotlin.math.abs(n.normalized() dot dir) < 0.15) continue
            tris += t[0]; tris += t[1]; tris += t[2]
        }
        if (tris.size < 3 * 6) return
        val xyz = FloatArray(pts.size * 3)
        val uv = FloatArray(pts.size * 2)
        for ((i, p) in pts.withIndex()) {
            xyz[3 * i] = p.x.toFloat(); xyz[3 * i + 1] = p.y.toFloat(); xyz[3 * i + 2] = p.z.toFloat()
            uv[2 * i] = (us[i] * kf.tw / kf.gw).toFloat(); uv[2 * i + 1] = (vs[i] * kf.th / kf.gh).toFloat()
        }
        fragments += Fragment(fragments.size, xyz, uv, tris.toIntArray(), kf.thumb, kf.tw, kf.th, dir)
        if (fragments.size > MAX_FRAGMENTS) fragments.removeAt(0)
    }

    /** Copia de los trozos de superficie para dibujar. */
    @Synchronized fun fragments(): List<Fragment> = fragments.toList()

    /** Triangula (o refina) un punto seguido con todas sus vistas. */
    private fun triangulate(t: Track) {
        if (t.obs.size < 2) return
        val views = t.obs.map { keyframes[it.kf]!!.view }
        val rays = t.obs.mapIndexed { i, o -> views[i].ray(o.u, o.v) }
        // Paralaje: mayor ángulo entre el primer rayo y los demás.
        val parallax = rays.drop(1).maxOf { Math.toDegrees(acos((it dot rays[0]).coerceIn(-1.0, 1.0))) }
        if (parallax < minParallaxDeg) return
        // Punto más cercano a todos los rayos: Σ(I − ddᵀ) X = Σ(I − ddᵀ) C.
        val a = DoubleArray(9); val b = DoubleArray(3)
        for ((i, d) in rays.withIndex()) {
            val c = views[i].center
            val dd = doubleArrayOf(d.x, d.y, d.z); val cc = doubleArrayOf(c.x, c.y, c.z)
            for (r in 0..2) for (s in 0..2) {
                val m = (if (r == s) 1.0 else 0.0) - dd[r] * dd[s]
                a[r * 3 + s] += m; b[r] += m * cc[s]
            }
        }
        val x = solve3(a, b) ?: return
        val p = Vec3(x[0], x[1], x[2])
        for ((i, o) in t.obs.withIndex()) {
            val v = views[i]
            val z = v.depth(p)
            if (z !in depthRange) return
            val q = v.project(p) ?: return
            val e = sqrt((q[0] - o.u) * (q[0] - o.u) + (q[1] - o.v) * (q[1] - o.v))
            if (e > maxReprojPx) return
        }
        if (t.point >= 0) { px[t.point] = p; pointObs[t.point] = t.obs.toList() }
        else if (px.size < maxPoints) {
            t.point = px.size; px += p; colors += 0; pointObs += t.obs.toList()
        }
    }

    /**
     * Etapa 2: color medio de las miniaturas para los puntos que ya no se siguen (o todos si
     * `all`). Devuelve cuántos se pintaron.
     */
    @Synchronized fun colorize(all: Boolean = false): Int {
        val active = if (all) emptySet() else tracks.mapNotNull { it.point.takeIf { p -> p >= 0 } }.toHashSet()
        var n = 0
        for (i in px.indices) {
            if (colors[i] != 0 || i in active) continue
            var r = 0L; var g = 0L; var bl = 0L; var cnt = 0
            for (o in pointObs[i]) {
                val kf = keyframes[o.kf] ?: continue
                val th = kf.thumb ?: continue
                val x = (o.u * kf.tw / kf.gw).toInt().coerceIn(0, kf.tw - 1)
                val y = (o.v * kf.th / kf.gh).toInt().coerceIn(0, kf.th - 1)
                val c = th[y * kf.tw + x]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF; cnt++
            }
            if (cnt == 0) continue
            colors[i] = (0xFF shl 24) or ((r / cnt).toInt() shl 16) or ((g / cnt).toInt() shl 8) or (bl / cnt).toInt()
            n++
        }
        return n
    }

    /** Copia para dibujar: xyz intercalados y colores (0 = sin color todavía). */
    @Synchronized fun snapshot(): Pair<FloatArray, IntArray> {
        val xyz = FloatArray(px.size * 3)
        for ((i, p) in px.withIndex()) { xyz[3 * i] = p.x.toFloat(); xyz[3 * i + 1] = p.y.toFloat(); xyz[3 * i + 2] = p.z.toFloat() }
        return xyz to colors.toIntArray()
    }

    /**
     * Para la guía en realidad aumentada: xyz y calidad 0..1 de cada punto (cuántas vistas lo
     * ven y desde qué ángulo): 0 = visto apenas (rojo), 1 = bien cubierto (verde).
     */
    @Synchronized fun quality(): Pair<FloatArray, FloatArray> {
        val xyz = FloatArray(px.size * 3); val q = FloatArray(px.size)
        for ((i, p) in px.withIndex()) {
            xyz[3 * i] = p.x.toFloat(); xyz[3 * i + 1] = p.y.toFloat(); xyz[3 * i + 2] = p.z.toFloat()
            val obs = pointObs[i]
            val first = keyframes[obs.first().kf]?.view; val last = keyframes[obs.last().kf]?.view
            val spread = if (first != null && last != null)
                Math.toDegrees(acos((first.ray(obs.first().u, obs.first().v) dot last.ray(obs.last().u, obs.last().v)).coerceIn(-1.0, 1.0))) else 0.0
            // ≥ 8 vistas y ≥ 20° entre la primera y la última = bien cubierto.
            q[i] = (0.5 * ((obs.size - 2) / 6.0).coerceIn(0.0, 1.0) + 0.5 * (spread / 20.0).coerceIn(0.0, 1.0)).toFloat()
        }
        return xyz to q
    }

    @Synchronized fun pointCount() = px.size
    @Synchronized fun coloredCount() = colors.count { it != 0 }

    @Synchronized fun points(): List<Vec3> = px.toList()

    /** Nube en PLY (ASCII, metros) para verla en cualquier visor (MeshLab, CloudCompare…). */
    @Synchronized fun writePly(f: File) {
        f.bufferedWriter().use { w ->
            w.write("ply\nformat ascii 1.0\ncomment Tisma Scanner: vista previa en vivo (no es el modelo final)\n")
            w.write("element vertex ${px.size}\nproperty float x\nproperty float y\nproperty float z\n")
            w.write("property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n")
            for ((i, p) in px.withIndex()) {
                val c = colors[i].takeIf { it != 0 } ?: 0xFF9E9E9E.toInt()
                w.write("%.5f %.5f %.5f %d %d %d\n".format(java.util.Locale.US, p.x, p.y, p.z, (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF))
            }
        }
    }

    @Synchronized fun reset() {
        keyframes.clear(); tracks.clear(); px.clear(); colors.clear(); pointObs.clear(); prevImage = null
        fragments.clear()
    }

    private fun solve3(a: DoubleArray, b: DoubleArray): DoubleArray? {
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        if (kotlin.math.abs(det) < 1e-12) return null
        fun d(c: Int): Double {
            val m = a.copyOf(); for (r in 0..2) m[r * 3 + c] = b[r]
            return m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        }
        return doubleArrayOf(d(0) / det, d(1) / det, d(2) / det)
    }

    /** Superficie en OBJ (sin textura) para abrirla en cualquier visor. */
    @Synchronized fun writeObj(f: File) {
        f.bufferedWriter().use { w ->
            w.write("# Tisma Scanner: superficie de vista previa (no es el modelo final)\n")
            var base = 1
            for (fr in fragments) {
                for (i in 0 until fr.xyz.size / 3) w.write("v %.5f %.5f %.5f\n".format(java.util.Locale.US, fr.xyz[3 * i], fr.xyz[3 * i + 1], fr.xyz[3 * i + 2]))
                for (t in 0 until fr.tris.size / 3) w.write("f ${fr.tris[3 * t] + base} ${fr.tris[3 * t + 1] + base} ${fr.tris[3 * t + 2] + base}\n")
                base += fr.xyz.size / 3
            }
        }
    }

    companion object {
        const val MAX_FRAGMENTS = 60

        /**
         * Pose de la cámara en una ÓRBITA alrededor de la pieza: la orientación sale de la INS
         * (giróscopo, muy precisa) y el centro se pone a `distM` (enfoque) detrás de la pieza, que
         * está en el origen. Es lo que hace un escaneo 360 y evita la deriva de posición de la INS.
         */
        fun orbitView(xAxis: Vec3, yAxis: Vec3, zAxis: Vec3, distM: Double, f: Double, cx: Double, cy: Double): View =
            View(xAxis, yAxis, zAxis, zAxis * -distM, f, cx, cy)
    }
}
