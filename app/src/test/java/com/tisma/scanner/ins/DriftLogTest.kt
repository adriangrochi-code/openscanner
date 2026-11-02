package com.tisma.scanner.ins

import org.junit.Assert.assertTrue
import org.junit.Test

class DriftLogTest {
    @Test fun summarizesCheckpointsAndGps() {
        // 5 min: deriva lineal de 1 cm/min en X; GPS 30 cm desplazado.
        val rows = (0..300).map { t ->
            DriftRow(t.toDouble(), Vec3(0.01 * t / 60, 0.0, 0.0), Vec3.ZERO, 0.003, true, 0.1 * t / 60,
                Vec3.ZERO, Vec3(0.0, 0.0, -0.045), gps = Vec3(0.3, 0.0, 0.0), gpsAcc = 4.0)
        }
        val l = DriftLog.lines(rows)
        l.forEach(::println)
        assertTrue(l.any { "1 min → 1.0 cm" in it && "5 min → 5.0 cm" in it })
        assertTrue(l.any { it.startsWith("Rumbo") && "0.10 °/min" in it })
        assertTrue(l.any { it.startsWith("INS vs GPS") })
        assertTrue(rows.first().csv().split(",").size == DriftRow.CSV_HEADER.split(",").size)
    }
}
