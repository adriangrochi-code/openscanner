package com.tisma.scanner.ins

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Volver a la app mientras se escriben las últimas fotos del escaneo reinicia la INS (otro marco, desde el origen).
 * Esas fotos tomaban su pose y su arrastre de la INS nueva: frames.csv mezclaba dos marcos sin aviso. Con la época de
 * la foto, una pose de otra época no se da.
 */
class InsEpochTest {
    private fun feed(ins: StrapdownIns, fromNs: Long, seconds: Int) {
        val g = MahonyFilter.GRAVITY
        for (i in 0 until seconds * 400) {
            val t = fromNs + i * 2_500_000L
            ins.onGyro(t, Vec3(0.0, 0.0, 0.0))
            ins.onAccel(t + 1_000_000L, Vec3(0.0, 0.0, g))
        }
    }

    @Test fun aPoseFromBeforeAResetIsNotGiven() {
        val ins = StrapdownIns()
        feed(ins, 1_000_000_000L, 2)
        val photoEpoch = ins.epoch
        assertNotNull(ins.stateAt(2_000_000_000L, photoEpoch))
        ins.reset()                              // back to the app: the INS starts again
        feed(ins, 3_000_000_000L, 2)
        assertNull("the photo's pose is not in this INS frame", ins.stateAt(2_000_000_000L, photoEpoch))
        assertNotNull(ins.stateAt(4_000_000_000L, ins.epoch))
    }
}
