package com.tisma.scanner.ins

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Órbita alrededor de una pieza a 25 cm durante 12 s. El acelerómetro tiene un error de sesgo y
 * ruido, así que la INS sola deriva; con el MSCKF (puntos de la pieza vistos por la cámara) la
 * posición debe quedar en milímetros.
 */
class VisualOdometryTest {
    private val g = MahonyFilter.GRAVITY
    private val dist = 0.25
    private val omega = 2 * PI / 12.0            // una vuelta en 12 s
    private fun pos(t: Double) = Vec3(dist * cos(omega * t), dist * sin(omega * t), 0.12 + 0.02 * sin(2 * omega * t))
    private fun acc(t: Double): Vec3 {
        val w2 = omega * omega
        return Vec3(-dist * w2 * cos(omega * t), -dist * w2 * sin(omega * t), -0.02 * 4 * w2 * sin(2 * omega * t))
    }
    private fun vel(t: Double) = Vec3(-dist * omega * sin(omega * t), dist * omega * cos(omega * t), 0.02 * 2 * omega * cos(2 * omega * t))

    /** Ejes de una cámara en `c` mirando al centro de la pieza. */
    private fun axes(c: Vec3): Triple<Vec3, Vec3, Vec3> {
        val z = (Vec3(0.0, 0.0, 0.02) - c).normalized()
        val x = (z cross Vec3(0.0, 0.0, 1.0)).normalized()
        return Triple(x, z cross x, z)
    }

    private fun run(withVision: Boolean): Pair<Double, Double> {
        val r = Random(11)
        val kf = TranslationKalman()
        val vo = VisualOdometry(kf)
        // Estado inicial conocido (como tras calibrar quieto): posición y velocidad reales.
        val landmarks = List(400) { Vec3(r.nextDouble(-0.03, 0.03), r.nextDouble(-0.03, 0.03), r.nextDouble(0.0, 0.04)) }
        val f = 280.0; val cx = 160.0; val cy = 120.0
        val rate = 200; val dt = 1.0 / rate
        val bias = Vec3(0.02, -0.015, 0.01)          // error del acelerómetro (m/s²)
        // La posición absoluta no es observable: la INS arranca en 0 y se mide el desplazamiento
        // desde el punto de partida. La velocidad inicial sí se conoce (arranque calibrado).
        val p0 = pos(0.0)
        run { kf.velocityAlong(Vec3(1.0, 0.0, 0.0), vel(0.0).x, 1e-3); kf.velocityAlong(Vec3(0.0, 1.0, 0.0), vel(0.0).y, 1e-3)
              kf.velocityAlong(Vec3(0.0, 0.0, 1.0), vel(0.0).z, 1e-3) }
        var maxErr = 0.0; var sumErr = 0.0; var n = 0
        val trackStart = HashMap<Int, Int>()
        var kfIndex = 0
        for (i in 1..(12 * rate)) {
            val t = i * dt
            val a = acc(t) + Vec3(0.0, 0.0, g) + bias + Vec3(r.nextDouble(-0.05, 0.05), r.nextDouble(-0.05, 0.05), r.nextDouble(-0.05, 0.05))
            kf.predict(Quat.IDENTITY, a, dt, 10.0)
            if (withVision && i % (rate / 5) == 0) {            // 5 fotogramas clave por segundo
                val c = pos(t); val (ax, ay, az) = axes(c)
                val cIns = c - p0       // la cámara en el marco de la INS (origen en el arranque)
                val obs = ArrayList<Triple<Int, Double, Double>>()
                for ((id, lm) in landmarks.withIndex()) {
                    val d = (lm - p0) - cIns
                    val z = d dot az
                    val u = cx + f * (d dot ax) / z + r.nextDouble(-0.5, 0.5)
                    val v = cy + f * (d dot ay) / z + r.nextDouble(-0.5, 0.5)
                    if (u !in 0.0..320.0 || v !in 0.0..240.0) continue
                    // Cada punto se sigue unos 20 fotogramas (4 s) y se "pierde", como el seguimiento real.
                    val start = trackStart.getOrPut(id) { kfIndex }
                    val trackId = id * 1000 + (kfIndex - start) / 20
                    obs += Triple(trackId, u, v)
                }
                vo.onKeyframe((t * 1e9).toLong(), 0.0, ax, ay, az, obs, f, cx, cy)
                kfIndex++
            }
            val e = (kf.p - (pos(t) - p0)).norm()
            maxErr = maxOf(maxErr, e); sumErr += e; n++
        }
        return sumErr / n to maxErr
    }

    @Test fun msckfKeepsTheInsOnTrack() {
        val (insMean, insMax) = run(false)
        val (voMean, voMax) = run(true)
        println("órbita 12 s, sesgo 0,02 m/s²: INS sola media %.1f mm (máx %.1f) · con MSCKF media %.2f mm (máx %.2f)".format(
            insMean * 1000, insMax * 1000, voMean * 1000, voMax * 1000))
        assertTrue("con visión %.4f vs sin %.4f".format(voMean, insMean), voMean < insMean / 20)
        assertTrue("media %.4f".format(voMean), voMean < 0.02)
        assertTrue("máximo %.4f".format(voMax), voMax < 0.05)
    }
}
