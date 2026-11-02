package com.tisma.scanner.ins

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Reproduce lo medido en el Honor: acelerómetro con −0,049 m/s² de error en la dirección de g,
 * móvil en la mano (pulso de 8 Hz) y rotado a varias orientaciones sin desplazarlo.
 * Antes la INS se iba decenas de metros; ahora debe quedarse cerca del origen.
 */
class HandheldRotationTest {
    private val g = MahonyFilter.GRAVITY

    @Test fun rotatingInHandDoesNotRunAway() {
        val r = Random(7)
        val ins = StrapdownIns()
        val bodyBias = Vec3(0.03, -0.02, -0.049)          // sesgo fijo del sensor (marco del móvil)
        var q = Quat.IDENTITY                              // orientación real dispositivo->mundo
        val rate = 400; val dt = 1.0 / rate
        var t = 0L
        var maxPos = 0.0
        // Secuencia: quieto en la mano, girar 90° en X, quieto, girar 90° en Y, quieto, girar -60° en Z…
        val plan = listOf(
            Vec3.ZERO to 3.0, Vec3(PI / 2, 0.0, 0.0) to 1.0, Vec3.ZERO to 3.0,
            Vec3(0.0, PI / 2, 0.0) to 1.0, Vec3.ZERO to 3.0, Vec3(0.0, 0.0, -PI / 3) to 1.0, Vec3.ZERO to 3.0,
            Vec3(-PI / 2, 0.0, 0.0) to 1.0, Vec3.ZERO to 3.0,
        )
        for ((turn, secs) in plan) {
            val n = (secs * rate).toInt()
            val w = turn * (1.0 / secs)
            for (i in 0 until n) {
                // Pulso de la mano: oscilación de 8 Hz, ±0,08 rad/s.
                val tremor = Vec3(sin(2 * PI * 8 * t * 1e-9), sin(2 * PI * 7 * t * 1e-9 + 1), 0.0) * 0.08
                val wb = w + tremor
                q = (q * Quat.fromRotationVector(wb * dt)).normalized()
                val noise = Vec3(r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0))
                val accel = q.conj().rotate(Vec3(0.0, 0.0, g)) + bodyBias + noise * 0.02
                val ts = t
                ins.onAccel(ts, accel)
                ins.onGyro(ts + 1_000_000, wb + noise * 0.002)
                t += (dt * 1e9).toLong()
                ins.latest()?.let { maxPos = maxOf(maxPos, it.position.norm()) }
            }
        }
        // Rotado en el sitio: sólo los giros (4 s en total) acumulan error. Antes: decenas de metros.
        assertTrue("posición máxima %.3f m".format(maxPos), maxPos < 0.08)
        assertTrue("debe detectar reposo con el pulso de la mano", ins.latest()!!.stationary)
    }
}
