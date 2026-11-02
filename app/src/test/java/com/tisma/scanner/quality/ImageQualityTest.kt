package com.tisma.scanner.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ImageQualityTest {
    private val w = 256; private val h = 192

    private fun noise(seed: Int = 1, scale: Double = 1.0, offset: Int = 0): IntArray {
        val r = Random(seed)
        return IntArray(w * h) { (offset + 60 + r.nextInt(120) * scale).toInt().coerceIn(0, 255) }
    }

    private fun boxBlur(g: IntArray, radius: Int): IntArray {
        val out = IntArray(g.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val yy = y + dy; val xx = x + dx
                if (yy in 0 until h && xx in 0 until w) { s += g[yy * w + xx]; n++ }
            }
            out[y * w + x] = s / n
        }
        return out
    }

    @Test fun blurReducesSharpness() {
        val sharp = ImageQuality.analyzeGray(noise(), w, h)
        val blurred = ImageQuality.analyzeGray(boxBlur(noise(), 3), w, h)
        assertTrue(blurred.sharpness < sharp.sharpness * 0.3)
    }

    @Test fun sharpnessIsRoughlyExposureInvariant() {
        val bright = ImageQuality.analyzeGray(noise(scale = 1.0), w, h)
        val dark = ImageQuality.analyzeGray(noise(scale = 0.3), w, h)
        assertTrue(dark.sharpness > bright.sharpness * 0.7)
    }

    @Test fun flatSurfaceHasLowTexture() {
        val flat = IntArray(w * h) { 128 + (it % 3) / 2 }    // superficie lisa, casi sin variación
        assertTrue(ImageQuality.analyzeGray(flat, w, h).texture < 0.1)
        assertTrue(ImageQuality.analyzeGray(noise(), w, h).texture > 0.9)
    }

    @Test fun blurJudgeIsRelativePerCamera() {
        val j = BlurJudge()
        repeat(6) { assertFalse(j.isBlurry("0", 10.0 + it * 0.1)) }
        assertTrue(j.isBlurry("0", 3.0))
        assertFalse(j.isBlurry("2", 3.0)) // otra cámara, sin historial
    }

    @Test fun blurJudgeAdaptsToSceneChangeInsteadOfDiscardingEverything() {
        val j = BlurJudge()
        repeat(6) { j.isBlurry("0", 10.0) }          // tapete con mucha textura
        // La cámara pasa a encuadrar la pieza lisa: nitidez 3 de forma sostenida.
        val verdicts = (0 until 12).map { j.isBlurry("0", 3.0) }
        assertTrue("no debe marcar todo como movido: $verdicts", verdicts.takeLast(6).none { it })
    }
}
