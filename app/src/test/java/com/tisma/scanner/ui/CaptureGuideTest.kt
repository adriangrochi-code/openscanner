package com.tisma.scanner.ui

import com.tisma.scanner.quality.CoverageMap
import com.tisma.scanner.quality.CoverageMap.Guidance
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureGuideTest {
    private val rings = CoverageMap.DEFAULT_RINGS

    @Test fun oneClearInstructionAtATime() {
        assertEquals("Rodea la pieza despacio", captureInstruction(rings, null, 0, false).text)
        assertEquals("Más despacio", captureInstruction(rings, Guidance(1, 0.3, 1, 0.0, 90.0, null), 10, true).text)
        assertEquals("→", captureInstruction(rings, Guidance(1, 0.3, 1, 0.0, 90.0, null), 10, false).icon)
        assertEquals("←", captureInstruction(rings, Guidance(1, 0.3, -1, 0.0, 90.0, null), 10, false).icon)
        assertEquals("↑", captureInstruction(rings, Guidance(1, 1.0, 0, null, null, 2), 10, false).icon)
        assertEquals("↓", captureInstruction(rings, Guidance(1, 1.0, 0, null, null, 0), 10, false).icon)
        assertEquals(Instruction.Tone.DONE, captureInstruction(rings, Guidance(1, 1.0, 0, null, null, null), 10, false).tone)
        assertEquals(Instruction.Tone.WARN, captureInstruction(rings, Guidance(null, 0.0, 0, null, null, 0), 10, false).tone)
    }

    @Test fun ringNamesReadNaturally() {
        assertEquals(listOf("Baja", "Media", "Alta", "Arriba"), rings.indices.map { ringName(it, rings) })
    }
}
