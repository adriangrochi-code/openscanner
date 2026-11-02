package com.tisma.scanner.ins

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeSelectorTest {
    private fun st(tMs: Long, yawDeg: Double, x: Double = 0.0) = InsState(
        tMs * 1_000_000, Quat.fromRotationVector(Vec3(0.0, 0.0, Math.toRadians(yawDeg))),
        Vec3(x, 0.0, 0.0), Vec3.ZERO, false,
    )

    @Test fun triggersOnRotationNotOnBlur() {
        val k = KeyframeSelector()
        assertTrue(k.shouldTrigger(st(0, 0.0), 0.0)); k.markTriggered(st(0, 0.0))
        assertFalse(k.shouldTrigger(st(500, 3.0), 0.1))      // poco giro
        assertTrue(k.shouldTrigger(st(600, 10.0), 0.1))      // giro suficiente
        assertFalse(k.shouldTrigger(st(700, 20.0), 1.5))     // demasiado rápido -> borroso
        assertTrue(k.shouldTrigger(st(800, 0.0, 0.05), 0.1)) // traslación
    }
}
