package com.tisma.scanner.quality

import com.tisma.scanner.camera.Optics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticsTest {
    // Principal típica de móvil: f 5,6 mm, f/1,75, sensor 7,4 mm, salida 4000 px.
    private val coc = Optics.cocMm(7.4, 4000)
    private val dof = Optics.dofDiopters(5.6, 1.75, coc)

    @Test fun dofIsAboutFourTenthsOfADiopter() = assertEquals(0.41, dof, 0.02)

    @Test fun closeUpNeedsManyPlanesFarNeedsOne() {
        val close = Optics.focusStack(centerD = 10.0, partDepthM = 0.03, dofD = dof, minFocusD = 12.0)
        val far = Optics.focusStack(centerD = 3.3, partDepthM = 0.03, dofD = dof, minFocusD = 12.0)
        assertTrue("cerca: ${close.size}", close.size >= 5)
        assertTrue("lejos: ${far.size}", far.size <= 2)
        // Ordenados de cerca a lejos y sin pasar del enfoque mínimo.
        assertTrue(close.first() > close.last())
        assertTrue(close.all { it <= 12.0f })
    }

    @Test fun stackIsCappedAndClampedToMinFocus() {
        val s = Optics.focusStack(centerD = 20.0, partDepthM = 0.05, dofD = dof, minFocusD = 10.0, maxSteps = 6)
        assertTrue(s.size <= 6)
        assertTrue(s.all { it <= 10.0f })
    }

    @Test fun gsdAtTwentyCentimetres() {
        val fx = Optics.fxPixels(5.6, 7.4, 4000)
        assertEquals(0.066, Optics.gsdMm(0.20, fx), 0.005)
    }

    @Test fun stackHasNoGapsBetweenPlanes() {
        for (center in listOf(4.0, 6.0, 8.0, 10.0)) for (depth in listOf(0.01, 0.03, 0.05)) {
            val planes = Optics.focusStack(center, depth, dof, minFocusD = 12.0, maxSteps = 30)
            val d = 1.0 / center
            // Más cerca que el enfoque mínimo (12 D) es físicamente imposible de enfocar.
            val near = minOf(1.0 / (d - depth / 2), 12.0); val far = 1.0 / (d + depth / 2)
            // Cada dioptría de la pieza debe caer dentro de ±ΔD/2 de algún plano.
            var x = far
            while (x <= near) {
                assertTrue("hueco en $x D (centro $center, prof $depth): $planes",
                    planes.any { kotlin.math.abs(it - x) <= dof / 2 + 1e-9 })
                x += 0.01
            }
        }
    }

    @Test fun depthSweepBracketsTheSubject() {
        // Pieza a 25 cm (4 D): planos de ~17,5 cm a ~35 cm, de cerca a lejos.
        val s = Optics.depthSweep(4.0, minFocusD = 11.1)
        assertEquals(7, s.size)
        assertEquals(1 / 0.175, s.first().toDouble(), 0.05)
        assertEquals(1 / 0.35, s.last().toDouble(), 0.05)
        // Muy cerca: no pasa del enfoque mínimo. En infinito: no hay barrido.
        assertTrue(Optics.depthSweep(10.0, minFocusD = 11.1).all { it <= 11.1f })
        assertTrue(Optics.depthSweep(0.0, minFocusD = 11.1).isEmpty())
    }
}
