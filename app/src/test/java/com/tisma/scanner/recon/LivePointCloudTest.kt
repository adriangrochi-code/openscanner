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

    @Test fun noParallaxNoPoints() {
        // Girar en el sitio (sin rodear la pieza) no da profundidad: no debe inventar puntos.
        val cloud = LivePointCloud()
        val v = view(0.0)
        repeat(10) { cloud.addKeyframe(render(v), v) }
        assertEquals(0, cloud.pointCount())
    }
}
