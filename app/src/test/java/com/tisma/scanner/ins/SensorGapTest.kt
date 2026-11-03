package com.tisma.scanner.ins

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * App en segundo plano: Android ralentiza (o corta) los sensores. Antes la INS seguía integrando
 * con muestras a 5 Hz y la precisión pasaba de centímetros a metros al volver.
 */
class SensorGapTest {
    private val g = MahonyFilter.GRAVITY

    private fun feed(ins: StrapdownIns, r: Random, t0: Long, seconds: Double, rateHz: Int): Long {
        var t = t0
        val step = (1e9 / rateHz).toLong()
        val bias = Vec3(0.03, -0.02, -0.045)
        repeat((seconds * rateHz).toInt()) {
            val n = Vec3(r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0))
            ins.onAccel(t, Vec3(0.0, 0.0, g) + bias + n * 0.02)
            ins.onGyro(t, n * 0.002)
            t += step
        }
        return t
    }

    @Test fun throttledSensorsInBackgroundDoNotBlowUpThePosition() {
        val r = Random(4)
        val ins = StrapdownIns()
        var t = feed(ins, r, 0L, 5.0, 400)                     // en la mano, app abierta
        val stdBefore = kotlin.math.sqrt(ins.positionVariance)
        t = feed(ins, r, t, 30.0, 5)                            // segundo plano: 5 Hz
        t = feed(ins, r, t, 3.0, 400)                           // vuelve
        val st = ins.latest()!!
        val std = kotlin.math.sqrt(ins.positionVariance)
        println("tras 30 s a 5 Hz: posición %.3f m · precisión ±%.3f m (antes ±%.3f) · huecos %d".format(
            st.position.norm(), std, stdBefore, ins.gaps))
        assertTrue("posición ${st.position.norm()}", st.position.norm() < 0.2)
        assertTrue("precisión $std", std < 0.3)
        assertTrue(ins.gaps > 0)
    }

    @Test fun aSilentGapIsNotIntegrated() {
        val r = Random(5)
        val ins = StrapdownIns()
        var t = feed(ins, r, 0L, 5.0, 400)
        t += 10_000_000_000L                                   // 10 s sin ninguna muestra
        feed(ins, r, t, 3.0, 400)
        assertTrue(ins.latest()!!.position.norm() < 0.1)
        assertTrue(kotlin.math.sqrt(ins.positionVariance) < 0.3)
    }
}
