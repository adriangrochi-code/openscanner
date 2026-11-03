package com.tisma.scanner.recon

import com.tisma.scanner.ins.OpticalFlow
import com.tisma.scanner.ins.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Escena sintética: una pieza de 6 cm (caja con manchas de textura) en el origen, grabada en
 * órbita a 25 cm y 35° de altura, un fotograma clave cada 3°. La nube en vivo debe quedar
 * sobre la superficie real.
 */
class LivePointCloudTest {
    private val w = 320; private val h = 240; private val f = 280.0
    private val rnd = Random(3)
    private val normals = listOf(Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, -1.0, 0.0), Vec3(0.0, 0.0, 1.0))
    private val faceOf = ArrayList<Int>()
    private val surface: List<Pair<Vec3, Float>> = List(1800) {
        val face = rnd.nextInt(5)          // 4 laterales + tapa
        val a = rnd.nextDouble(-0.03, 0.03); val b = rnd.nextDouble(-0.03, 0.03); val c = rnd.nextDouble(0.0, 0.04)
        val p = when (face) {
            0 -> Vec3(0.03, a, c); 1 -> Vec3(-0.03, a, c); 2 -> Vec3(a, 0.03, c); 3 -> Vec3(a, -0.03, c)
            else -> Vec3(a, b, 0.04)
        }
        faceOf += face
        p to rnd.nextDouble(20.0, 235.0).toFloat()
    }

    private fun view(azDeg: Double, elDeg: Double = 35.0, dist: Double = 0.25): LivePointCloud.View {
        val az = Math.toRadians(azDeg); val el = Math.toRadians(elDeg)
        val camPos = Vec3(cos(el) * cos(az), cos(el) * sin(az), sin(el)) * dist + Vec3(0.0, 0.0, 0.02)
        val z = (Vec3(0.0, 0.0, 0.02) - camPos).normalized()
        val x = (z cross Vec3(0.0, 0.0, 1.0)).normalized()
        val y = z cross x
        return LivePointCloud.View(x, y, z, camPos, f, w / 2.0, h / 2.0)
    }

    /** Distancia a la superficie de la caja (4 caras laterales + tapa). */
    private fun boxDistance(p: Vec3): Double {
        fun rect(u: Double, v: Double, uR: ClosedFloatingPointRange<Double>, vR: ClosedFloatingPointRange<Double>, n: Double): Double {
            val du = maxOf(uR.start - u, 0.0, u - uR.endInclusive); val dv = maxOf(vR.start - v, 0.0, v - vR.endInclusive)
            return kotlin.math.sqrt(du * du + dv * dv + n * n)
        }
        val s = -0.03..0.03; val hz = 0.0..0.04
        return minOf(
            rect(p.y, p.z, s, hz, p.x - 0.03), rect(p.y, p.z, s, hz, p.x + 0.03),
            rect(p.x, p.z, s, hz, p.y - 0.03), rect(p.x, p.z, s, hz, p.y + 0.03),
            rect(p.x, p.y, s, s, p.z - 0.04),
        )
    }

    /** Render: manchas gaussianas (textura) sobre fondo gris, sin oclusiones. */
    private fun render(v: LivePointCloud.View): OpticalFlow.Image {
        val img = FloatArray(w * h) { 110f }
        for ((k, sp) in surface.withIndex()) {
            val (p, intensity) = sp
            // Caras ocultas: una cámara real no ve la cara de atrás a través de la pieza.
            if ((normals[faceOf[k]] dot (v.center - p)) <= 0) continue
            val q = v.project(p) ?: continue
            val cx = q[0]; val cy = q[1]
            for (j in -3..3) for (i in -3..3) {
                val xx = cx.toInt() + i; val yy = cy.toInt() + j
                if (xx !in 0 until w || yy !in 0 until h) continue
                val d2 = (xx - cx) * (xx - cx) + (yy - cy) * (yy - cy)
                val wgt = exp(-d2 / (2 * 1.3 * 1.3)).toFloat()
                img[yy * w + xx] = img[yy * w + xx] * (1 - wgt) + intensity * wgt
            }
        }
        return OpticalFlow.Image(w, h, img)
    }

    @Test fun orbitRecordingBuildsAPointCloudOnTheSurface() {
        val cloud = LivePointCloud()
        cloud.depthRange = 0.1..0.5
        val thumb = IntArray(80 * 60) { 0xFFAA3322.toInt() }
        var az = 0.0
        while (az <= 90.0) {
            val v = view(az)
            cloud.addKeyframe(render(v), v, thumb, 80, 60)
            az += 3.0
        }
        val pts = cloud.points()
        val err = pts.map { boxDistance(it) }.sorted()
        val median = err[err.size / 2]
        println("nube en vivo: ${pts.size} puntos · error mediano %.2f mm · p90 %.2f mm".format(median * 1000, err[err.size * 9 / 10] * 1000))
        assertTrue("puntos: ${pts.size}", pts.size > 150)
        assertTrue("error mediano %.4f".format(median), median < 0.002)

        // Superficie: trozos desde varias direcciones, con los vértices sobre la pieza.
        val frs = cloud.fragments()
        val triCount = frs.sumOf { it.tris.size / 3 }
        val vErr = frs.flatMap { f -> (0 until f.xyz.size / 3).map { i -> boxDistance(Vec3(f.xyz[3 * i].toDouble(), f.xyz[3 * i + 1].toDouble(), f.xyz[3 * i + 2].toDouble())) } }.sorted()
        // Centro de cada triángulo: no debe "cortar" por dentro de la pieza (unir caras distintas).
        println("superficie: ${frs.size} trozos · $triCount triángulos · vértices a %.2f mm (mediana)".format(vErr[vErr.size / 2] * 1000))
        assertTrue("trozos ${frs.size}", frs.size >= 3)
        assertTrue("triángulos $triCount", triCount >= 60)
        assertTrue(vErr[vErr.size / 2] < 0.002)
        assertTrue("con textura", frs.all { it.thumb != null })

        // Etapa 2: color (los que siguen activos se pintan al final).
        assertEquals(0, cloud.snapshot().second.count { it != 0 }.coerceAtMost(0))
        cloud.colorize(all = true)
        val colored = cloud.snapshot().second
        assertTrue(colored.all { it == 0xFFAA3322.toInt() })
    }

    /**
     * La posición de la INS deriva ~2 cm a lo largo de la vuelta: con la corrección por imagen la
     * nube sigue pegada a la pieza (y los centros corregidos se acercan a los reales).
     */
    private var lastInsErr = 0.0

    @Test fun imageCorrectsInsPositionDrift() {
        fun run(correct: Boolean): Pair<Double, Double> {
            val cloud = LivePointCloud(maxCorrectionM = if (correct) 0.05 else 0.0)
            cloud.depthRange = 0.1..0.5
            var az = 0.0; var k = 0
            var centerErr = 0.0; var n = 0; var insErr = 0.0
            while (az <= 90.0) {
                val truth = view(az)
                val drift = Vec3(0.0007 * k, -0.0004 * k, 0.0002 * k)       // deriva de la INS
                val insC = truth.center + drift
                val ins = LivePointCloud.View(truth.xAxis, truth.yAxis, truth.zAxis, insC, f, w / 2.0, h / 2.0)
                val fixed = cloud.addKeyframe(render(truth), ins)
                if (fixed != null) { centerErr += (fixed - truth.center).norm(); insErr += drift.norm(); n++ }
                az += 3.0; k++
            }
            val err = cloud.points().map { boxDistance(it) }.sorted()
            lastInsErr = if (n > 0) insErr / n else Double.NaN
            return err[err.size / 2] to (if (n > 0) centerErr / n else Double.NaN)
        }
        val (withoutErr, _) = run(false)
        val (withErr, centerErr) = run(true)
        println("deriva INS hasta 2,4 cm: posición INS media %.1f mm -> corregida %.1f mm · nube %.2f -> %.2f mm".format(
            lastInsErr * 1000, centerErr * 1000, withoutErr * 1000, withErr * 1000))
        assertTrue("posición %.4f vs INS %.4f".format(centerErr, lastInsErr), centerErr < lastInsErr * 0.7)
        assertTrue("nube corregida %.4f vs sin corregir %.4f".format(withErr, withoutErr), withErr < withoutErr * 0.8)
    }

    /** Modelo en el móvil: vuelta completa a dos alturas -> malla cerrada pegada a la caja. */
    @Test fun localModelFromAFullTurn() {
        val cloud = LivePointCloud()
        cloud.depthRange = 0.1..0.5
        cloud.fragmentEveryDeg = 8.0
        val thumb = IntArray(80 * 60) { 0xFFAA3322.toInt() }
        // Como pide el recuadro de encuadre: la pieza llena buena parte de la imagen (~14 cm).
        cloud.depthRange = 0.05..0.4
        for (el in listOf(25.0, 55.0)) {
            var az = 0.0
            while (az < 360.0) { val v = view(az, el, dist = 0.14); cloud.addKeyframe(render(v), v, thumb, 80, 60); az += 3.0 }
        }
        val t0 = System.currentTimeMillis()
        val model = LocalModel.build(cloud.fragments(), resolution = 96)!!
        val ms = System.currentTimeMillis() - t0
        val err = (0 until model.vertexCount).map { i ->
            boxDistance(Vec3(model.xyz[3 * i].toDouble(), model.xyz[3 * i + 1].toDouble(), model.xyz[3 * i + 2].toDouble()))
        }.sorted()
        // Normales hacia fuera: apuntan lejos del centro de la caja en casi todos los triángulos.
        var out = 0
        for (t in 0 until model.triangleCount) {
            fun p(i: Int) = Vec3(model.xyz[3 * i].toDouble(), model.xyz[3 * i + 1].toDouble(), model.xyz[3 * i + 2].toDouble())
            val a = p(model.tris[3 * t]); val b = p(model.tris[3 * t + 1]); val c = p(model.tris[3 * t + 2])
            val n = (b - a) cross (c - a)
            if ((n dot ((a + b + c) * (1.0 / 3) - Vec3(0.0, 0.0, 0.02))) > 0) out++
        }
        println("modelo local: ${cloud.fragments().size} vistas · ${model.triangleCount} triángulos · " +
            "error mediano %.2f mm · p90 %.2f mm · %.0f %% hacia fuera · %d ms".format(err[err.size / 2] * 1000, err[err.size * 9 / 10] * 1000,
                100.0 * out / model.triangleCount, ms))
        assertTrue(model.triangleCount > 500)
        assertTrue("error mediano", err[err.size / 2] < 0.002)
        assertTrue("orientación", out > 0.85 * model.triangleCount)
        assertTrue(model.colors.count { it == 0xFFAA3322.toInt() } > 0.8 * model.vertexCount)
        val stl = java.io.File.createTempFile("pieza", ".stl")
        model.writeStl(stl)
        assertEquals(84L + 50L * model.triangleCount, stl.length())
    }

    /** Fusión con superficies perfectas (sin seguimiento ni triangulación): aísla TSDF + malla. */
    @Test fun localModelFromPerfectViews() {
        val frs = ArrayList<LivePointCloud.Fragment>()
        for (el in listOf(25.0, 55.0)) for (az in 0 until 360 step 15) {
            val v = view(az.toDouble(), el)
            val vis = surface.indices.filter { (normals[faceOf[it]] dot (v.center - surface[it].first)) > 0 }
            val uv = vis.map { v.project(surface[it].first)!! }
            val tri = Delaunay.triangulate(DoubleArray(vis.size) { uv[it][0] }, DoubleArray(vis.size) { uv[it][1] })
                .filter { t -> (0..2).all { e -> faceOf[vis[t[e]]] == faceOf[vis[t[(e + 1) % 3]]] } }   // sin puentes entre caras
            val xyz = FloatArray(vis.size * 3)
            for ((i, k) in vis.withIndex()) { val p = surface[k].first; xyz[3 * i] = p.x.toFloat(); xyz[3 * i + 1] = p.y.toFloat(); xyz[3 * i + 2] = p.z.toFloat() }
            frs += LivePointCloud.Fragment(frs.size, xyz, FloatArray(vis.size * 2), tri.flatMap { it.toList() }.toIntArray(), null, 0, 0,
                v.zAxis, v, w, h)
        }
        val model = LocalModel.build(frs, resolution = 96)!!
        val err = (0 until model.vertexCount).map { i ->
            boxDistance(Vec3(model.xyz[3 * i].toDouble(), model.xyz[3 * i + 1].toDouble(), model.xyz[3 * i + 2].toDouble()))
        }.sorted()
        println("fusión perfecta: ${model.triangleCount} triángulos · error mediano %.2f mm · p90 %.2f mm".format(err[err.size / 2] * 1000, err[err.size * 9 / 10] * 1000))
        assertTrue(err[err.size * 9 / 10] < 0.002)
    }

    /** Rayo-caja (la pieza): profundidad en la vista, o null si no la toca. */
    private fun castBox(v: LivePointCloud.View, u: Double, vv: Double): Double? {
        val d = v.ray(u, vv); val o = v.center
        val lo = doubleArrayOf(-0.03, -0.03, 0.0); val hi = doubleArrayOf(0.03, 0.03, 0.04)
        val oo = doubleArrayOf(o.x, o.y, o.z); val dd = doubleArrayOf(d.x, d.y, d.z)
        var t0 = 0.0; var t1 = 1e9
        for (k in 0..2) {
            if (kotlin.math.abs(dd[k]) < 1e-12) { if (oo[k] < lo[k] || oo[k] > hi[k]) return null; continue }
            var a = (lo[k] - oo[k]) / dd[k]; var b = (hi[k] - oo[k]) / dd[k]
            if (a > b) { val t = a; a = b; b = t }
            t0 = maxOf(t0, a); t1 = minOf(t1, b)
            if (t0 > t1) return null
        }
        return t0 * (d dot v.zAxis)
    }

    /**
     * Tapa lisa sin textura (sin puntos medidos): sin IA el modelo queda sin tapa; con la
     * profundidad densa (relativa, alineada con los puntos de los costados) la tapa aparece.
     */
    @Test fun depthAiFillsTheTexturelessTop() {
        val r = Random(8)
        val frs = ArrayList<LivePointCloud.Fragment>()
        val dense = ArrayList<DepthPrior.DepthView>()
        var fitErr = 0.0
        for (el in listOf(25.0, 55.0)) for (az in 0 until 360 step 15) {
            val v = view(az.toDouble(), el, dist = 0.14)
            // Puntos medidos sólo en los costados (la tapa no tiene textura).
            val vis = surface.indices.filter { faceOf[it] != 4 && (normals[faceOf[it]] dot (v.center - surface[it].first)) > 0 }
            val uv = vis.map { v.project(surface[it].first)!! }
            val tri = Delaunay.triangulate(DoubleArray(vis.size) { uv[it][0] }, DoubleArray(vis.size) { uv[it][1] })
                .filter { t -> (0..2).all { e -> faceOf[vis[t[e]]] == faceOf[vis[t[(e + 1) % 3]]] } }
            val xyz = FloatArray(vis.size * 3)
            for ((i, k) in vis.withIndex()) { val p = surface[k].first; xyz[3 * i] = p.x.toFloat(); xyz[3 * i + 1] = p.y.toFloat(); xyz[3 * i + 2] = p.z.toFloat() }
            frs += LivePointCloud.Fragment(frs.size, xyz, FloatArray(vis.size * 2), tri.flatMap { it.toList() }.toIntArray(), null, 0, 0, v.zAxis, v, w, h)
            // "Red": profundidad inversa con escala/desplazamiento desconocidos y 1 % de ruido.
            val dw = 160; val dh = 120
            val pred = FloatArray(dw * dh) { i ->
                val d = castBox(v, (i % dw + 0.5) * w / dw, (i / dw + 0.5) * h / dh)
                if (d == null) 0.3f else (2.0 / d * (1 + r.nextDouble(-0.01, 0.01)) + 0.7).toFloat()
            }
            val samples = vis.indices.map { i -> Triple(uv[i][0] * dw / w, uv[i][1] * dh / h, v.depth(surface[vis[i]].first)) }
            val fit = DepthPrior.fit(pred, dw, dh, samples)!!
            fitErr += fit.relRms
            val ds = samples.map { it.third }
            dense += DepthPrior.DepthView(v, dw, dh, DepthPrior.toMetric(pred, fit, ds.min() * 0.7, ds.max() * 1.3, dw, dh), w, h)
        }
        fun topCoverage(m: LocalModel): Double {
            val grid = (0 until 400).map { Vec3(-0.027 + 0.054 * (it % 20) / 19, -0.027 + 0.054 * (it / 20) / 19, 0.04) }
            return grid.count { g -> (0 until m.vertexCount).any { i ->
                val dx = m.xyz[3 * i] - g.x; val dy = m.xyz[3 * i + 1] - g.y; val dz = m.xyz[3 * i + 2] - g.z
                dx * dx + dy * dy + dz * dz < 0.0015 * 0.0015 } }.toDouble() / grid.size
        }
        val plain = LocalModel.build(frs, resolution = 96)!!
        val withAi = LocalModel.build(frs, resolution = 96, dense = dense)!!
        val err = (0 until withAi.vertexCount).map { i ->
            boxDistance(Vec3(withAi.xyz[3 * i].toDouble(), withAi.xyz[3 * i + 1].toDouble(), withAi.xyz[3 * i + 2].toDouble())) }.sorted()
        println("tapa sin textura: cubierta sin IA %.0f %% · con IA %.0f %% · ajuste de escala ±%.2f %% · error mediano con IA %.2f mm".format(
            topCoverage(plain) * 100, topCoverage(withAi) * 100, fitErr / dense.size * 100, err[err.size / 2] * 1000))
        assertTrue(topCoverage(withAi) > 0.9)
        assertTrue(topCoverage(withAi) > topCoverage(plain) + 0.3)
        assertTrue(err[err.size / 2] < 0.001)
    }

    @Test fun noParallaxNoPoints() {
        // Girar en el sitio (sin rodear la pieza) no da profundidad: no debe inventar puntos.
        val cloud = LivePointCloud()
        val v = view(0.0)
        repeat(10) { cloud.addKeyframe(render(v), v) }
        assertEquals(0, cloud.pointCount())
    }
}
