package com.tisma.scanner.ins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ImuStillTestTest {
    /** IMU MEMS de móvil simulado: 400 Hz, ruido blanco y sesgos constantes. */
    private fun simulate(seconds: Int, accelBias: Vec3, gyroBias: Vec3): Pair<List<ImuSample>, List<ImuSample>> {
        val r = Random(42)
        fun n(s: Double) = Vec3(r.nextGaussian() * s, r.nextGaussian() * s, r.nextGaussian() * s)
        val acc = ArrayList<ImuSample>(); val gyr = ArrayList<ImuSample>()
        val dt = 2_500_000L
        for (i in 0 until seconds * 400) {
            val t = i * dt
            acc += ImuSample(t, Vec3(0.0, 0.0, MahonyFilter.GRAVITY) + accelBias + n(0.015))
            gyr += ImuSample(t + 1_000_000, gyroBias + n(0.002))
        }
        return acc to gyr
    }

    private fun Random.nextGaussian(): Double {
        val u = nextDouble(1e-12, 1.0); val v = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }

    @Test fun measuresNoiseBiasAndDriftGrowth() {
        val (a, g) = simulate(30, Vec3(0.02, 0.0, 0.0), Vec3(0.005, -0.003, 0.002))
        val rep = ImuStillTest.analyze(a, g)
        assertNotNull(rep); rep!!
        assertEquals(400.0, rep.gyroHz, 5.0)
        assertEquals(Math.toDegrees(0.002), rep.gyroNoiseDegS, 0.02)
        assertEquals(Math.toDegrees(0.00616), rep.gyroBiasDegS.norm(), 0.02)
        assertFalse(rep.moved)
        // La deriva sin paradas crece con el tiempo. Quieto, el sesgo del acelerómetro se confunde
        // con inclinación (el filtro lo absorbe), así que crece ~lineal y no cuadrática.
        val d = rep.freeDriftM.toMap()
        assertTrue("deriva: $d", d.getValue(10.0) > d.getValue(2.0) * 2 && d.getValue(5.0) > d.getValue(1.0))
        assertTrue("deriva 1 s: ${d.getValue(1.0)}", d.getValue(1.0) < 0.05)
        rep.lines().forEach(::println)
    }

    @Test fun detectsThatThePhoneMoved() {
        val (a, g) = simulate(10, Vec3.ZERO, Vec3.ZERO)
        val shaken = a.mapIndexed { i, s -> if (i % 200 < 20) s.copy(v = s.v + Vec3(1.5, 0.0, 0.0)) else s }
        assertTrue(ImuStillTest.analyze(shaken, g)!!.moved)
    }

    /** El caso medido en el Honor: g leída 0,049 m/s² baja -> deriva cuadrática sin compensar. */
    @Test fun gravityErrorIsCompensated() {
        val (a, g) = simulate(30, Vec3(0.0, 0.0, -0.049), Vec3.ZERO)
        val rep = ImuStillTest.analyze(a, g)!!
        assertEquals(-0.049, rep.gravityError, 0.003)
        val raw = rep.freeDriftRawM.toMap().getValue(10.0)
        val comp = rep.freeDriftM.toMap().getValue(10.0)
        assertTrue("sin compensar ~2,4 m: $raw", raw > 1.5)
        assertTrue("compensado mucho menor: $comp vs $raw", comp < raw / 5)
        rep.lines().forEach(::println)
    }
}
