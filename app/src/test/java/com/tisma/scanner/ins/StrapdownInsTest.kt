package com.tisma.scanner.ins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class StrapdownInsTest {
    private val g = MahonyFilter.GRAVITY

    private fun feed(ins: StrapdownIns, seconds: Double, rateHz: Int = 400, t0: Long = 0,
                     accel: (Double) -> Vec3, gyro: (Double) -> Vec3): Long {
        val dtNs = 1_000_000_000L / rateHz
        var t = t0
        val n = (seconds * rateHz).toInt()
        for (i in 0 until n) {
            val ts = (t - t0) * 1e-9
            ins.onAccel(t, accel(ts)); ins.onGyro(t, gyro(ts)); t += dtNs
        }
        return t
    }

    @Test fun stationaryDeviceStaysPut() {
        val ins = StrapdownIns()
        feed(ins, 5.0, accel = { Vec3(0.0, 0.0, g) }, gyro = { Vec3.ZERO })
        val s = ins.latest()!!
        assertTrue(s.stationary)
        assertEquals(0.0, s.position.norm(), 1e-6)
    }

    @Test fun gyroIntegratesYaw() {
        val ins = StrapdownIns()
        // 90° de giro en Z en 2 s.
        feed(ins, 2.0, accel = { Vec3(0.0, 0.0, g) }, gyro = { Vec3(0.0, 0.0, PI / 4) })
        val q = ins.latest()!!.orientation
        val x = q.rotate(Vec3(1.0, 0.0, 0.0))
        assertEquals(0.0, x.x, 0.03); assertEquals(1.0, x.y, 0.03)
    }

    @Test fun tiltIsRecoveredFromGravity() {
        val ins = StrapdownIns()
        // Dispositivo inclinado 30° alrededor de X.
        val a = 30.0 * PI / 180
        feed(ins, 3.0, accel = { Vec3(0.0, g * kotlin.math.sin(a), g * kotlin.math.cos(a)) }, gyro = { Vec3.ZERO })
        val up = ins.latest()!!.orientation.rotate(Vec3(0.0, kotlin.math.sin(a), kotlin.math.cos(a)))
        assertEquals(1.0, up.z, 1e-3)
    }

    @Test fun integratesTranslationAndZuptStops() {
        val ins = StrapdownIns()
        var t = feed(ins, 1.0, accel = { Vec3(0.0, 0.0, g) }, gyro = { Vec3.ZERO })
        // 0.5 s acelerando a +1 m/s² en X, 0.5 s frenando: desplazamiento = 0.25 m.
        t = feed(ins, 0.5, t0 = t, accel = { Vec3(1.0, 0.0, g) }, gyro = { Vec3.ZERO })
        t = feed(ins, 0.5, t0 = t, accel = { Vec3(-1.0, 0.0, g) }, gyro = { Vec3.ZERO })
        feed(ins, 1.0, t0 = t, accel = { Vec3(0.0, 0.0, g) }, gyro = { Vec3.ZERO })
        val s = ins.latest()!!
        assertEquals(0.25, s.position.x, 0.03)
        assertEquals(0.0, s.velocity.norm(), 1e-9)
        assertTrue(s.stationary)
    }

    @Test fun interpolatesStateAtFrameTime() {
        val ins = StrapdownIns()
        feed(ins, 1.0, accel = { Vec3(0.0, 0.0, g) }, gyro = { Vec3(0.0, 0.0, 1.0) })
        val mid = ins.stateAt(500_000_000L)!!
        assertEquals(500_000_000L, mid.timestampNs)
    }

    @Test fun gnssBarelyMovesShortScan() {
        val ins = StrapdownIns()
        feed(ins, 1.0, accel = { Vec3(0.0, 0.0, g) }, gyro = { Vec3.ZERO })
        ins.onGnss(40.0, -3.0, 600.0, 3.0, null)        // fija origen
        ins.onGnss(40.00001, -3.0, 600.0, 3.0, null)    // ~1.1 m al norte, ±3 m
        assertTrue(ins.latest()!!.position.norm() < 0.01)
    }
}
