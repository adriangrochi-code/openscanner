package com.tisma.scanner.ins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El sesgo del acelerómetro no se corrige dos veces al reabrir la app (prueba de quietud + calibración de 6 caras). */
class BiasPriorsTest {
    // Prueba de quietud hecha antes de la calibración de 6 caras: guardó el sesgo crudo del acelerómetro.
    private val saved = "0.001,0.002,0.003,0.05,-0.04,0.12"

    @Test fun withSixFaceCalibrationTheAccelBiasIsNotAddedAgain() {
        val p = BiasPriors.parse(saved, accelCalibrated = true)!!
        assertEquals(Vec3.ZERO, p.accel)
        assertEquals(Vec3(0.001, 0.002, 0.003), p.gyro)   // el giróscopo sí
    }

    @Test fun withoutItTheStillTestBiasIsTheStart() {
        assertEquals(Vec3(0.05, -0.04, 0.12), BiasPriors.parse(saved, accelCalibrated = false)!!.accel)
    }

    @Test fun nothingOrBrokenSavedIsNoPrior() {
        assertNull(BiasPriors.parse(null, false))
        assertNull(BiasPriors.parse("1,2,3", false))
    }
}
