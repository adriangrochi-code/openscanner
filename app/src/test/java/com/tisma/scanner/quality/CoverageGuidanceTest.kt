package com.tisma.scanner.quality

import com.tisma.scanner.ins.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CoverageGuidanceTest {
    private fun dir(azDeg: Double, elDeg: Double): Vec3 {
        val a = Math.toRadians(azDeg); val e = Math.toRadians(elDeg)
        return Vec3(cos(e) * cos(a), cos(e) * sin(a), sin(e))
    }

    @Test fun pointsToTheNearestGapAndItsSide() {
        val m = CoverageMap()
        // Media altura (anillo 1, 24 sectores de 15°): cubierto de 0° a 90°.
        for (az in 0..90 step 5) m.add(dir(az.toDouble(), 40.0))
        val origin = m.origin()!!
        // En 80°: el hueco más cercano está girando hacia azimut creciente.
        val g = m.guidance(m.relativeAz(80.0 + origin), 40.0)
        assertEquals(1, g.ring)
        assertEquals(1, g.turn)
        assertNotNull(g.gapStartDeg)
        // En 10°: más cerca girando al revés (el hueco da la vuelta por 360°).
        val back = m.guidance(m.relativeAz(10.0 + origin), 40.0)
        assertEquals(-1, back.turn)
    }

    @Test fun completeRingSuggestsTheNextHeight() {
        val m = CoverageMap()
        for (az in 0 until 360 step 5) m.add(dir(az.toDouble(), 40.0))
        val g = m.guidance(100.0, 40.0)
        assertEquals(0, g.turn)
        assertNull(g.gapStartDeg)
        // Anillo pendiente más cercano: uno de los vecinos (0 o 2).
        assert(g.nextRing == 0 || g.nextRing == 2) { "siguiente ${g.nextRing}" }
    }

    @Test fun belowTheDomeAsksToLookFromHigher() {
        val g = CoverageMap().guidance(0.0, 2.0)
        assertNull(g.ring)
    }
}
