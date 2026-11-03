package com.tisma.scanner.quality

import com.tisma.scanner.ins.OpticalVelocity
import com.tisma.scanner.ins.Quat
import com.tisma.scanner.ins.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MotionDeblurTest {
    /** Textura sintética (manchas como el tapete) para medir. */
    private fun texture(w: Int, h: Int): FloatArray {
        val r = Random(4); val img = FloatArray(w * h) { 110f }
        repeat(w * h / 30) {
            val cx = r.nextInt(w); val cy = r.nextInt(h); val v = r.nextDouble(20.0, 235.0).toFloat()
            for (j in -2..2) for (i in -2..2) {
                val x = cx + i; val y = cy + j
                if (x in 0 until w && y in 0 until h && i * i + j * j <= 5) img[y * w + x] = v
            }
        }
        return img
    }

    private fun blur(img: FloatArray, w: Int, h: Int, k: List<IntArray>) = FloatArray(img.size) { i ->
        val x = i % w; val y = i / w
        k.sumOf { img[(y - it[1]).coerceIn(0, h - 1) * w + (x - it[0]).coerceIn(0, w - 1)].toDouble() }.toFloat() / k.size
    }

    private fun rmse(a: FloatArray, b: FloatArray) = kotlin.math.sqrt(a.indices.sumOf { ((a[it] - b[it]) * (a[it] - b[it])).toDouble() } / a.size)

    @Test fun knownMotionBlurIsUndone() {
        val w = 160; val h = 120
        val sharp = texture(w, h)
        val k = MotionDeblur.lineKernel(6.0, 3.0)
        val r = Random(9)
        val blurred = blur(sharp, w, h, k).map { (it + r.nextDouble(-2.0, 2.0).toFloat()).coerceIn(0f, 255f) }.toFloatArray()
        val fixed = MotionDeblur.deconvolve(blurred, w, h, k)
        val before = rmse(blurred, sharp); val after = rmse(fixed, sharp)
        println("arrastre 6,7 px: error %.1f -> %.1f niveles".format(before, after))
        assertTrue("después %.2f vs antes %.2f".format(after, before), after < before * 0.75)
    }

    @Test fun bandsGiveTheSameResultAsTheWholeImage() {
        val w = 120; val h = 90
        val img = blur(texture(w, h), w, h, MotionDeblur.lineKernel(2.0, 5.0))
        val k = MotionDeblur.lineKernel(2.0, 5.0)
        val whole = MotionDeblur.deconvolve(img, w, h, k, 6)
        val out = FloatArray(w * h)
        MotionDeblur.deconvolveBands({ y0, rows -> img.copyOfRange(y0 * w, (y0 + rows) * w) }, w, h, k,
            { y0, rows, band -> band.copyInto(out, y0 * w) }, bandRows = 20, iterations = 6)
        val maxDiff = whole.indices.maxOf { kotlin.math.abs(whole[it] - out[it]) }
        assertTrue("diferencia máx $maxDiff", maxDiff < 6f)
    }

    @Test fun blurVectorFromRotation() {
        // Giro de 0,01 rad alrededor del eje Y del dispositivo durante la exposición, cámara a 90°.
        val axes = OpticalVelocity.cameraAxesInDevice(90)
        val q1 = Quat.fromRotationVector(Vec3(0.0, 0.01, 0.0))
        val (dx, dy) = MotionDeblur.blurVector(Quat.IDENTITY, q1, axes, 3000.0)
        assertEquals(30.0, kotlin.math.hypot(dx, dy), 0.5)   // f·θ
    }
}
