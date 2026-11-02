package com.tisma.scanner.quality

import com.tisma.scanner.ins.Quat
import com.tisma.scanner.ins.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CoverageMapTest {
    private fun dir(azDeg: Double, elDeg: Double): Vec3 {
        val a = Math.toRadians(azDeg); val e = Math.toRadians(elDeg)
        return Vec3(cos(e) * cos(a), cos(e) * sin(a), sin(e))
    }

    @Test fun phoneFlatLookingDownIsTopView() {
        // Pantalla hacia arriba = orientación identidad; la trasera mira al suelo -> vista cenital.
        val (_, el) = CoverageMap.azEl(CoverageMap.viewDirection(Quat.IDENTITY, backFacing = true))
        assertEquals(90.0, el, 1e-9)
    }

    @Test fun phoneUprightIsSideView() {
        // Móvil vertical: eje Y del dispositivo hacia arriba (rotación de -90° en X).
        val q = Quat.fromRotationVector(Vec3(Math.toRadians(90.0), 0.0, 0.0))
        val (_, el) = CoverageMap.azEl(CoverageMap.viewDirection(q, backFacing = true))
        assertEquals(0.0, el, 1e-6)
    }

    @Test fun fullOrbitAtOneHeightFillsThatRingOnly() {
        val m = CoverageMap()
        for (az in 0 until 360 step 10) m.add(dir(az.toDouble(), 40.0))
        assertEquals(1.0, m.ringCoverage(1), 1e-9)
        assertEquals(0.0, m.ringCoverage(0), 1e-9)
        assertTrue(m.hints().first().contains("10–30°"))
    }

    @Test fun azimuthIsRelativeToFirstPhotoAndGapIsReported() {
        val m = CoverageMap()
        // Empieza en azimut absoluto 200° y recorre solo media vuelta.
        for (k in 0 until 18) m.add(dir(200.0 + k * 10.0, 40.0))
        assertEquals(0.5, m.ringCoverage(1), 0.05)
        val hint = m.hints().first { it.contains("30–50°") }
        assertTrue(hint, hint.contains("180°") || hint.contains("165°"))
    }

    @Test fun belowTableIsNotCounted() {
        val m = CoverageMap()
        assertNull(m.add(dir(0.0, -20.0)))
        assertEquals(1, m.below)
        assertEquals(0.0, m.coverage(), 1e-9)
    }
}
