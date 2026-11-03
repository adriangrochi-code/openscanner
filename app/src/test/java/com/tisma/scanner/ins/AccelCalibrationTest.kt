package com.tisma.scanner.ins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.random.Random

class AccelCalibrationTest {
    private val g = MahonyFilter.GRAVITY
    // Sensor "real": sesgo y escala distintos por eje (valores típicos de un móvil).
    private val bias = Vec3(0.035, -0.022, -0.047)
    private val scale = Vec3(1.004, 0.997, 1.003)

    /** Lo que mide el sensor para una aceleración real `a` (inversa de a = s⊙(m − b)). */
    private fun measure(a: Vec3) = Vec3(a.x / scale.x + bias.x, a.y / scale.y + bias.y, a.z / scale.z + bias.z)

    /** Alimenta posturas quietas (4 s cada una, algo torcidas) separadas por movimiento. */
    private fun feedPoses(cal: AccelCalibration, ups: List<Vec3>, measureFn: (Vec3) -> Vec3, seed: Int): Int {
        val r = Random(seed); var t = 0L; var newPoses = 0
        for (up in ups) {
            val tilt = Quat.fromRotationVector(Vec3(r.nextDouble(-0.06, 0.06), r.nextDouble(-0.06, 0.06), 0.0))
            val gBody = tilt.rotate(up) * g
            for (i in 0 until 400 * 4) {
                val n = Vec3(r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0)) * 0.04
                if (cal.feed(t, measureFn(gBody) + n)) newPoses++
                t += 2_500_000
            }
            // Movimiento entre posturas: no debe contar como postura.
            for (i in 0 until 400) { cal.feed(t, measureFn(gBody) + Vec3(r.nextDouble(-2.0, 2.0), 0.0, 0.0)); t += 2_500_000 }
        }
        return newPoses
    }

    private val six = listOf(Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, -1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0))

    @Test fun recoversBiasAndScaleWhenAllSixDirectionsAppear() {
        val cal = AccelCalibration()
        assertEquals(6, feedPoses(cal, six, ::measure, 5))
        val res = cal.solve()
        assertNotNull(res); res!!
        println("6 posturas: sesgo %s (real %s) · escala %s (real %s) · %s".format(res.bias, bias, res.scale, scale, res.mode))
        assertEquals(AccelCalibration.Mode.BIAS_SCALE, res.mode)
        assertEquals(bias.x, res.bias.x, 0.005); assertEquals(bias.y, res.bias.y, 0.005); assertEquals(bias.z, res.bias.z, 0.005)
        assertEquals(scale.x, res.scale.x, 0.001); assertEquals(scale.y, res.scale.y, 0.001); assertEquals(scale.z, res.scale.z, 0.001)
        assertTrue(res.plausible)
    }

    /** Lo que pedía el usuario: sin hacer las 6 caras, sólo posturas cotidianas. */
    @Test fun threeEverydayPosesAlreadyGiveTheBias() {
        val cal = AccelCalibration()
        // Boca arriba, de pie y de costado. Sin error de escala, para medir sólo el sesgo.
        val biasOnly: (Vec3) -> Vec3 = { a -> a + bias }
        feedPoses(cal, listOf(Vec3(0.0, 0.0, 1.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0)), biasOnly, 7)
        val res = cal.solve()
        assertNotNull("con 3 posturas distintas ya debe calibrar", res); res!!
        println("3 posturas: sesgo %s (real %s) · %s".format(res.bias, bias, res.mode))
        assertEquals(AccelCalibration.Mode.BIAS, res.mode)
        assertEquals(bias.x, res.bias.x, 0.005); assertEquals(bias.y, res.bias.y, 0.005); assertEquals(bias.z, res.bias.z, 0.005)
    }

    @Test fun samePoseTwiceIsNotANewPose() {
        val cal = AccelCalibration()
        val up = listOf(Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, 1.0))
        assertEquals(1, feedPoses(cal, up, ::measure, 3))
        assertEquals(null, cal.solve())       // una sola dirección: nada que calibrar
    }

    /**
     * Lo que se ve en el Honor: quieto la INS va de maravilla y, al cogerlo, girarlo y moverlo,
     * el error salta. Móvil quieto 4 s, gira 90° y se desplaza 30 cm en 1,5 s, y se para.
     */
    @Test fun calibrationReducesErrorWhenStartingToMove() {
        fun run(calibrated: Boolean): Double {
            val r = Random(9)
            val ins = StrapdownIns()
            if (calibrated) ins.accelCalibration = AccelCalibration.Result(bias, scale, 0.0)
            var q = Quat.IDENTITY; var p = Vec3.ZERO; var v = Vec3.ZERO
            val dt = 1.0 / 400; var t = 0L
            var maxErr = 0.0
            for (i in 0 until 400 * 9) {
                val ts = i * dt
                val w = if (ts in 4.0..5.0) Vec3(PI / 2, 0.0, 0.0) else Vec3.ZERO          // gira 90° en 1 s
                // Desplazamiento de 30 cm entre 4 y 5,5 s (perfil suave: acelera y frena).
                val aW = if (ts in 4.0..5.5) Vec3(0.3 * 2 * PI / (1.5 * 1.5) * kotlin.math.sin(2 * PI * (ts - 4) / 1.5), 0.0, 0.0) else Vec3.ZERO
                q = (q * Quat.fromRotationVector(w * dt)).normalized()
                v = v + aW * dt; p = p + v * dt
                val specific = q.conj().rotate(aW + Vec3(0.0, 0.0, g))
                val n = Vec3(r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0)) * 0.03
                ins.onAccel(t, measure(specific) + n)
                ins.onGyro(t + 1_000_000, w + n * 0.05)
                t += 2_500_000
                ins.latest()?.let { maxErr = maxOf(maxErr, (it.position - p).norm()) }
            }
            return maxErr
        }
        val raw = run(false); val cal = run(true)
        println("al empezar a moverse: error máx. sin calibrar %.1f cm · con calibración de 6 caras %.1f cm".format(raw * 100, cal * 100))
        assertTrue("la calibración debe reducir el error ($cal vs $raw)", cal < raw * 0.6)
    }
}
