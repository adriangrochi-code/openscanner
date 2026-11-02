package com.tisma.scanner.ins

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Caminata de ~130 m al aire libre: pasos (oscilación vertical de 1,8 Hz), sesgo del
 * acelerómetro, declinación magnética de 10° (Uruguay) y GPS a 1 Hz con ±3 m de posición y
 * ±0,15 m/s de velocidad Doppler. El marco INS apunta al norte MAGNÉTICO; el GPS da el VERDADERO.
 */
class GnssWalkTest {
    private val g = MahonyFilter.GRAVITY
    private val lat0 = -34.87; private val lon0 = -56.11
    private val mLat = 111_132.92 - 559.82 * cos(2 * Math.toRadians(lat0))
    private val mLon = 111_412.84 * cos(Math.toRadians(lat0))

    /** Error final de posición (m) y error máximo. */
    private fun walk(useVelocity: Boolean, declinationKnown: Boolean): Pair<Double, Double> {
        val r = Random(21)
        fun gauss() = sqrt(-2 * kotlin.math.ln(r.nextDouble(1e-12, 1.0))) * cos(2 * PI * r.nextDouble())
        val decl = 10.0
        val ins = StrapdownIns()
        ins.declinationDeg = if (declinationKnown) decl else 0.0
        val heading = Math.toRadians(-35.0)                  // rumbo de la caminata en el marco INS
        val dir = Vec3(cos(heading), sin(heading), 0.0)
        val vmax = 1.3
        val rate = 400; val dt = 1.0 / rate
        var t = 0L; var p = Vec3.ZERO; var v = Vec3.ZERO
        val bias = Vec3(0.03, -0.02, 0.02)
        var maxErr = 0.0
        for (i in 0 until rate * 100) {
            val ts = i * dt
            val aH = if (ts < 1.0) vmax else 0.0                     // arranca en 1 s, luego constante
            val aStep = 2.0 * sin(2 * PI * 1.8 * ts)                 // pasos
            val aTrue = dir * aH + Vec3(0.0, 0.0, aStep)
            v = v + aTrue * dt; p = p + v * dt
            fun n3(s: Double) = Vec3(gauss() * s, gauss() * s, gauss() * s)
            ins.onAccel(t, Vec3(0.0, 0.0, g) + aTrue + bias + n3(0.02))
            ins.onGyro(t + 1_000_000, n3(0.002))
            if (i % rate == 0) {
                // INS (norte magnético) -> ENU verdadero, con el ruido del GPS.
                val d = Math.toRadians(decl)
                val eM = -p.y; val nM = p.x
                val e = eM * cos(d) + nM * sin(d) + gauss() * 3.0
                val nn = nM * cos(d) - eM * sin(d) + gauss() * 3.0
                val vEm = -v.y; val vNm = v.x
                val ve = vEm * cos(d) + vNm * sin(d) + gauss() * 0.15
                val vn = vNm * cos(d) - vEm * sin(d) + gauss() * 0.15
                val speed = sqrt(ve * ve + vn * vn)
                val bearing = (Math.toDegrees(atan2(ve, vn)) + 360) % 360
                ins.onGnss(lat0 + nn / mLat, lon0 + e / mLon, 30.0 + gauss(), 3.0, 4.5,
                    speedMps = if (useVelocity) speed else null, bearingDeg = if (useVelocity) bearing else null,
                    speedAccMps = if (useVelocity) 0.15 else null)
            }
            t += (dt * 1e9).toLong()
            val err = (ins.latest() ?: continue).position - p
            maxErr = maxOf(maxErr, sqrt(err.x * err.x + err.y * err.y))
        }
        val e = ins.latest()!!.position - p
        return sqrt(e.x * e.x + e.y * e.y) to maxErr
    }

    @Test fun dopplerVelocityAndDeclinationReduceDrift() {
        val posOnly = walk(useVelocity = false, declinationKnown = true)
        val withVel = walk(useVelocity = true, declinationKnown = true)
        val noDecl = walk(useVelocity = true, declinationKnown = false)
        println("130 m: sólo posición GPS %.2f m (máx %.2f) · + velocidad %.2f m (máx %.2f) · sin declinación %.2f m (máx %.2f)"
            .format(posOnly.first, posOnly.second, withVel.first, withVel.second, noDecl.first, noDecl.second))
        assertTrue("con velocidad Doppler debe derivar menos", withVel.second < posOnly.second)
        assertTrue("ignorar la declinación debe ser peor", noDecl.second > withVel.second * 2)
    }
}
