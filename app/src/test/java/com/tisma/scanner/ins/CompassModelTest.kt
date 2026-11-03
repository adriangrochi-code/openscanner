package com.tisma.scanner.ins

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lo medido en el Honor: rumbo +30° en 5 min con el móvil QUIETO (6 °/min) mientras el
 * giróscopo sólo deriva 0,02 °/min. Causas: rumbo inicial arbitrario que la brújula corregía
 * despacio, y campo de interior distorsionado aceptado como bueno.
 */
class CompassModelTest {
    private val g = MahonyFilter.GRAVITY
    // Campo de Uruguay: ~23 µT con inclinación ≈ −35° (apunta hacia arriba).
    private val inclDeg = -35.0
    private val fieldUt = 23.0
    private fun earthField(northAngle: Double): Vec3 {
        val i = Math.toRadians(inclDeg)
        // En el marco del móvil (pantalla arriba): norte magnético a `northAngle` del eje X.
        return Vec3(fieldUt * cos(i) * cos(northAngle), fieldUt * cos(i) * sin(northAngle), -fieldUt * sin(i))
    }

    /** Rumbo (°) de la INS respecto al norte magnético y su cambio en `secs` segundos quieto. */
    private fun run(magMeasured: Vec3, expected: Boolean, secs: Int): Pair<Double, Double> {
        val ins = StrapdownIns()
        if (expected) ins.setExpectedMagneticField(fieldUt, inclDeg)
        var t = 0L
        var headingAt1s = 0.0
        fun heading(): Double {
            val m = ins.latest()!!.orientation.rotate(magMeasured)
            return Math.toDegrees(atan2(m.y, m.x))
        }
        for (i in 0 until 400 * secs) {
            ins.onAccel(t, Vec3(0.0, 0.0, g))
            if (i % 4 == 0) ins.onMag(t, magMeasured)
            ins.onGyro(t + 1_000_000, Vec3.ZERO)
            t += 2_500_000
            if (i == 400) headingAt1s = heading()
        }
        val q0 = ins.stateAt(1_000_000_000L)!!.orientation
        val yawChange = Math.toDegrees(q0.angleTo(ins.latest()!!.orientation))
        return headingAt1s to yawChange
    }

    @Test fun headingTakenFromCompassAtStartNoSlowConvergence() {
        val (h1, change) = run(earthField(Math.toRadians(50.0)), expected = true, secs = 60)
        println("brújula buena: rumbo tras 1 s %.2f° · cambio en 1 min quieto %.3f°".format(h1, change))
        assertTrue("debe alinearse al norte enseguida ($h1°)", kotlin.math.abs(h1) < 1.0)
        assertTrue("quieto no debe girar ($change°)", change < 0.2)
    }

    @Test fun distortedIndoorFieldIsIgnored() {
        // Campo 40 % más intenso que el modelo (hierro, electrónica): la brújula se ignora y el
        // rumbo lo mantiene el giróscopo (que en el Honor deriva 0,02 °/min).
        val distorted = earthField(Math.toRadians(50.0)) * 1.4
        val (_, change) = run(distorted, expected = true, secs = 120)
        println("campo distorsionado: cambio de rumbo en 2 min quieto %.3f°".format(change))
        assertTrue("con campo distorsionado no debe girar ($change°)", change < 0.2)
    }
}
