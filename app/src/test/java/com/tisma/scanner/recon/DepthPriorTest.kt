package com.tisma.scanner.recon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DepthPriorTest {
    @Test fun recoversScaleShiftAndModelKindDespiteOutliers() {
        val r = Random(2); val w = 100; val h = 80
        val truth = FloatArray(w * h) { i -> (0.12 + 0.05 * (i % w) / w + 0.02 * (i / w) / h).toFloat() }
        for (inverse in listOf(true, false)) {
            val pred = FloatArray(w * h) { i -> if (inverse) (3.0 / truth[i] + 0.5).toFloat() else (7.0 * truth[i] - 0.2).toFloat() }
            val samples = List(80) {
                val u = r.nextInt(w); val v = r.nextInt(h)
                val d = truth[v * w + u].toDouble()
                Triple(u + 0.5, v + 0.5, if (it % 7 == 0) d * 1.4 else d * (1 + r.nextDouble(-0.003, 0.003)))   // 15 % atípicos
            }
            val fit = DepthPrior.fit(pred, w, h, samples)!!
            assertEquals(inverse, fit.inverse)
            val m = DepthPrior.toMetric(pred, fit, 0.05, 1.0)
            val maxRel = truth.indices.maxOf { kotlin.math.abs(m[it] - truth[it]) / truth[it] }
            assertTrue("inversa=$inverse error máx ${maxRel * 100} %", maxRel < 0.01)
        }
    }
}
