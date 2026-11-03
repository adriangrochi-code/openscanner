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
    /** Corrección máxima que se acepta de la posición INS con la imagen (m). 0 = no corregir. */
    private val maxCorrectionM: Double = 0.05,
    /**
     * Con este paralaje el punto queda fijo. Si se siguiera recalculando con cada vista nueva
     * (cuyas posiciones derivan), el mapa "acompañaría" a la deriva y no podría corregirla.
     */
    private val freezeDeg: Double = 8.0,
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
                   val thumb: IntArray?, val tw: Int, val th: Int, val viewDir: Vec3,
                   /** Vista desde la que se hizo (para la fusión volumétrica del modelo local). */
                   val view: View? = null, val gw: Int = 0, val gh: Int = 0,
                   /** Malla cerrada con normales hacia fuera por el orden de vértices (modelo local). */
                   val outward: Boolean = false,
                   /** Color por vértice (ARGB) en vez de textura. */
                   val vertexColors: IntArray? = null)

    private val fragments = ArrayList<Fragment>()
    /** Nueva superficie sólo si la vista difiere al menos esto de las que ya hay (grados). */
    var fragmentEveryDeg = 8.0

    private class Keyframe(val view: View, val thumb: IntArray?, val tw: Int, val th: Int, val gw: Int, val gh: Int)
    private class Obs(val kf: Int, val u: Double, val v: Double)
    /** `frozen`: punto ya bien triangulado (≥ freezeDeg): no se recalcula, sirve de referencia. */
    private class Track(val obs: ArrayList<Obs> = ArrayList(), var point: Int = -1, var frozen: Boolean = false)

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
    /**
     * Añade un fotograma clave y devuelve el centro de la cámara CORREGIDO con la imagen (o null).
     *
     * La pose llega de la INS (orientación del giróscopo + posición del Kalman). Con la orientación
     * conocida, los puntos 3D ya triangulados que se ven en esta imagen fijan la posición por
     * mínimos cuadrados (cada uno es un rayo que debe pasar por su punto). Así la deriva de la
     * posición INS no ensucia la nube, y quien llama puede devolver esa medida a la INS.
     */
    @Synchronized fun addKeyframe(gray: OpticalFlow.Image, view: View, thumb: IntArray? = null, tw: Int = 0, th: Int = 0): Vec3? {
        val k = keyframes.size
        val prev = prevImage
        var moved: List<DoubleArray?> = emptyList()
        if (prev != null && tracks.isNotEmpty()) {
            moved = OpticalFlow.trackPoints(prev, gray, tracks.map { val o = it.obs.last(); doubleArrayOf(o.u, o.v) })
        }
        // Posición: el desplazamiento desde la vista anterior en la DIRECCIÓN que dice la imagen
        // y con la LONGITUD que dice la INS, encadenado desde la vista anterior ya corregida.
        // (La deriva de la INS entre dos fotos es un % apreciable del paso y, al triangular, se
        // amplificaría en centímetros de profundidad.)
        var fixed: Vec3? = null
        val prevKf = keyframes.lastOrNull()
        val lastIns = lastInsCenter
        if (maxCorrectionM > 0 && prevKf != null && lastIns != null && moved.isNotEmpty()) {
            val dIns = view.center - lastIns
            val mag = dIns.norm()
            var step = dIns
            if (mag > 0.002) {
                val a = ArrayList<Vec3>(); val b = ArrayList<Vec3>()
                for ((i, t) in tracks.withIndex()) {
                    val p = moved[i] ?: continue
                    val o = t.obs.last()
                    a += prevKf.view.ray(o.u, o.v); b += view.ray(p[0], p[1])
                }
                translationDirection(a, b)?.let { dir ->
                    val d = if ((dir dot dIns) < 0) -dir else dir
                    if (Math.toDegrees(acos((d dot (dIns * (1.0 / mag))).coerceIn(-1.0, 1.0))) < 60.0) step = d * mag
                }
            }
            val c = prevKf.view.center + step
            if ((c - view.center).norm() <= maxCorrectionM * 4) fixed = c
        }
        // Ajuste fino con los puntos fijos ya conocidos (ahora fiables: las direcciones son de la imagen).
        if (fixed != null) {
            val refs = tracks.indices.mapNotNull { i ->
                val p = moved.getOrNull(i) ?: return@mapNotNull null
                val t = tracks[i]
                if (t.point < 0 || !t.frozen) null else Ref(px[t.point], p, t.obs.first().kf)
            }
            val chained = View(view.xAxis, view.yAxis, view.zAxis, fixed, view.f, view.cx, view.cy)
            visualCenter(chained, refs, maxShift = 0.01)?.let { fixed = it }
        }
        lastInsCenter = view.center
        if (fixed != null) insOffset = fixed!! - view.center
        val v = if (fixed != null) View(view.xAxis, view.yAxis, view.zAxis, fixed, view.f, view.cx, view.cy) else view
        keyframes += Keyframe(v, thumb, tw, th, gray.w, gray.h)
        // Las miniaturas viejas ya se usaron para colorear: se liberan.
        if (k - maxThumbs >= 0) keyframes[k - maxThumbs]?.let { keyframes[k - maxThumbs] = Keyframe(it.view, null, 0, 0, it.gw, it.gh) }
        if (moved.isNotEmpty()) {
            val alive = ArrayList<Track>()
            for ((i, t) in tracks.withIndex()) {
                val p = moved[i] ?: continue
                // Un punto fijo debe seguir viéndose donde se proyecta: si no, el seguimiento se
                // ha ido a otra superficie (p.ej. su cara ya no se ve) y el track se termina.
                if (t.frozen) {
                    val q = v.project(px[t.point])
                    if (q == null || kotlin.math.hypot(q[0] - p[0], q[1] - p[1]) > maxReprojPx * 2) continue
                }
                t.obs += Obs(k, p[0], p[1])
                alive += t
                if (t.frozen) pointObs[t.point] = t.obs.toList() else triangulate(t)
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
        return fixed
    }

    /**
     * Centro de cámara con orientación conocida a partir de correspondencias 2D-3D:
     * Σ(I − ddᵀ)·C = Σ(I − ddᵀ)·X. Dos pasadas (la segunda sin los que no reproyectan) y sólo si
     * la corrección es razonable frente a la INS.
     */
    private var lastInsCenter: Vec3? = null
    /** Corrección actual de la posición INS (centro corregido − centro INS) para la AR. */
    @Volatile var insOffset: Vec3 = Vec3.ZERO; private set

    /**
     * Dirección del desplazamiento entre dos vistas con la rotación conocida: cada punto seguido
     * da dos rayos (mundo) a y b que, con la traslación t, deben ser coplanarios: (a × b)·t = 0.
     * t es el autovector menor de Σ nnᵀ (n = a × b); una segunda pasada quita los que no cuadran.
     */
    internal fun translationDirection(a: List<Vec3>, b: List<Vec3>): Vec3? {
        if (a.size < 12) return null
        var ns = a.indices.map { a[it] cross b[it] }.filter { it.norm() > 1e-6 }
        if (ns.size < 12) return null
        var t: Vec3? = null
        repeat(2) { pass ->
            if (pass == 1) {
                val cur = t ?: return null
                // Residuo angular de cada punto respecto al plano epipolar.
                val res = ns.map { kotlin.math.abs((it dot cur) / it.norm()) }
                val lim = res.sorted()[res.size * 3 / 4] * 2.5 + 1e-5
                ns = ns.filterIndexed { i, _ -> res[i] <= lim }
                if (ns.size < 10) return null
            }
            val m = DoubleArray(9)
            for (n in ns) { val nn = doubleArrayOf(n.x, n.y, n.z); for (r in 0..2) for (c in 0..2) m[r * 3 + c] += nn[r] * nn[c] }
            t = smallestEigenvector(m) ?: return null
        }
        return t
    }

    /** Autovector del menor autovalor de una matriz simétrica 3×3 (Jacobi: exacto aunque haya dos casi iguales). */
    private fun smallestEigenvector(m0: DoubleArray): Vec3? {
        val a = Array(3) { r -> DoubleArray(3) { c -> m0[r * 3 + c] } }
        val v = Array(3) { r -> DoubleArray(3) { c -> if (r == c) 1.0 else 0.0 } }
        repeat(50) {
            var p = 0; var q = 1
            var off = kotlin.math.abs(a[0][1])
            if (kotlin.math.abs(a[0][2]) > off) { p = 0; q = 2; off = kotlin.math.abs(a[0][2]) }
            if (kotlin.math.abs(a[1][2]) > off) { p = 1; q = 2; off = kotlin.math.abs(a[1][2]) }
            if (off < 1e-18) return@repeat
            val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
            val t = (if (theta >= 0) 1.0 else -1.0) / (kotlin.math.abs(theta) + sqrt(theta * theta + 1))
            val c = 1 / sqrt(t * t + 1); val sn = t * c
            for (k in 0..2) {   // A = Jᵀ A J
                val akp = a[k][p]; val akq = a[k][q]
                a[k][p] = c * akp - sn * akq; a[k][q] = sn * akp + c * akq
            }
            for (k in 0..2) {
                val apk = a[p][k]; val aqk = a[q][k]
                a[p][k] = c * apk - sn * aqk; a[q][k] = sn * apk + c * aqk
            }
            for (k in 0..2) {
                val vkp = v[k][p]; val vkq = v[k][q]
                v[k][p] = c * vkp - sn * vkq; v[k][q] = sn * vkp + c * vkq
            }
        }
        val i = (0..2).minByOrNull { a[it][it] } ?: return null
        return Vec3(v[0][i], v[1][i], v[2][i]).normalized()
    }

    /** Referencia para situar la cámara: punto fijo, dónde se ve ahora y en qué vista nació. */
    internal class Ref(val x: Vec3, val uv: DoubleArray, val born: Int = 0)

    internal fun visualCenter(view: View, pairs: List<Ref>, maxShift: Double = maxCorrectionM): Vec3? {
        if (pairs.size < 8) return null
        // Más peso a los puntos más viejos: nacieron de poses con menos deriva acumulada.
        val oldest = pairs.minOf { it.born }
        fun weight(r: Ref): Double { val a = 1.0 + (r.born - oldest) / 2.0; return 1.0 / (a * a) }
        var c = view.center
        var use = pairs
        repeat(2) { pass ->
            if (pass == 1) {
                val trial = View(view.xAxis, view.yAxis, view.zAxis, c, view.f, view.cx, view.cy)
                use = pairs.filter { r ->
                    val q = trial.project(r.x) ?: return@filter false
                    kotlin.math.hypot(q[0] - r.uv[0], q[1] - r.uv[1]) < 3.0
                }
                if (use.size < 8) return null
            }
            val a = DoubleArray(9); val b = DoubleArray(3)
            for (ref in use) {
                val d = view.ray(ref.uv[0], ref.uv[1]); val w = weight(ref)
                val dd = doubleArrayOf(d.x, d.y, d.z); val xx = doubleArrayOf(ref.x.x, ref.x.y, ref.x.z)
                for (r in 0..2) for (s2 in 0..2) {
                    val m = w * ((if (r == s2) 1.0 else 0.0) - dd[r] * dd[s2])
                    a[r * 3 + s2] += m; b[r] += m * xx[s2]
                }
            }
            val sol = solve3(a, b) ?: return null
            c = Vec3(sol[0], sol[1], sol[2])
        }
        return if ((c - view.center).norm() <= maxShift) c else null
    }

    /** Superficie desde la vista k, si mira desde una dirección nueva. */
    private fun buildFragment(k: Int) {
        val kf = keyframes[k] ?: return
        val dir = kf.view.zAxis
        if (fragments.any { Math.toDegrees(acos((it.viewDir dot dir).coerceIn(-1.0, 1.0))) < fragmentEveryDeg }) return
        // Sólo puntos fijos (bien triangulados) y que se ven donde se proyectan.
        val cand = tracks.filter { it.point >= 0 && it.frozen && it.obs.last().kf == k }
        if (cand.size < 12) return
        val cu = DoubleArray(cand.size) { cand[it].obs.last().u }
        val cv = DoubleArray(cand.size) { cand[it].obs.last().v }
        val cd = cand.map { kf.view.depth(px[it.point]) }
        // Fuera los que no cuadran con sus vecinos en la imagen (profundidad ≠ mediana local).
        val keep = cand.indices.filter { i ->
            val nb = cand.indices.filter { j -> j != i && kotlin.math.hypot(cu[i] - cu[j], cv[i] - cv[j]) < kf.gw * 0.08 }
            if (nb.size < 3) return@filter false
            val med = nb.map { cd[it] }.sorted()[nb.size / 2]
            kotlin.math.abs(cd[i] - med) < 0.04 * med
        }
        if (keep.size < 12) return
        val vis = keep.map { cand[it] }
        val us = DoubleArray(vis.size) { cu[keep[it]] }
        val vs = DoubleArray(vis.size) { cv[keep[it]] }
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
        fragments += Fragment(fragments.size, xyz, uv, tris.toIntArray(), kf.thumb, kf.tw, kf.th, dir, kf.view, kf.gw, kf.gh)
        if (fragments.size > MAX_FRAGMENTS) fragments.removeAt(0)
    }

    /** Copia de los trozos de superficie para dibujar. */
    @Synchronized fun fragments(): List<Fragment> = fragments.toList()

    /**
     * Triangula un punto seguido con su PRIMERA y su ÚLTIMA vista (la de mayor paralaje). Usar sólo
     * esas dos ata el punto a la pose más antigua (la que menos ha derivado) en vez de arrastrarlo
     * con la deriva de todas las intermedias. Al llegar a `freezeDeg` queda fijo como referencia.
     */
    private fun triangulate(t: Track) {
        if (t.obs.size < 2 || t.frozen) return
        val o0 = t.obs.first(); val o1 = t.obs.last()
        val v0 = keyframes[o0.kf]!!.view; val v1 = keyframes[o1.kf]!!.view
        val d0 = v0.ray(o0.u, o0.v); val d1 = v1.ray(o1.u, o1.v)
        val parallax = Math.toDegrees(acos((d0 dot d1).coerceIn(-1.0, 1.0)))
        if (parallax < minParallaxDeg) return
        // Punto más cercano a ambos rayos: Σ(I − ddᵀ) X = Σ(I − ddᵀ) C.
        val a = DoubleArray(9); val b = DoubleArray(3)
        for ((d, c) in listOf(d0 to v0.center, d1 to v1.center)) {
            val dd = doubleArrayOf(d.x, d.y, d.z); val cc = doubleArrayOf(c.x, c.y, c.z)
            for (r in 0..2) for (s2 in 0..2) {
                val m = (if (r == s2) 1.0 else 0.0) - dd[r] * dd[s2]
                a[r * 3 + s2] += m; b[r] += m * cc[s2]
            }
        }
        val x = solve3(a, b) ?: return
        val p = Vec3(x[0], x[1], x[2])
        for ((v, o) in listOf(v0 to o0, v1 to o1)) {
            if (v.depth(p) !in depthRange) return
            val q = v.project(p) ?: return
            if (kotlin.math.hypot(q[0] - o.u, q[1] - o.v) > maxReprojPx) return
        }
        if (t.point >= 0) { px[t.point] = p; pointObs[t.point] = t.obs.toList() }
        else if (px.size < maxPoints) {
            t.point = px.size; px += p; colors += 0; pointObs += t.obs.toList()
        }
        if (parallax >= freezeDeg) t.frozen = true
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
        fragments.clear(); lastInsCenter = null; insOffset = Vec3.ZERO
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
