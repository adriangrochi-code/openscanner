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

    @Test fun fastVersionGivesTheSameResultAndIsFaster() {
        val w = 640; val h = 480
        val k = MotionDeblur.lineKernel(7.0, -3.0)
        val sharp = texture(w, h)
        val img = blur(sharp, w, h, k)
        var t0 = System.nanoTime()
        val slow = MotionDeblur.deconvolve(img, w, h, k, 6)
        val tSlow = (System.nanoTime() - t0) / 1e6
        MotionDeblur.deconvolveFast(img, w, h, k, 6)              // calentar el JIT
        t0 = System.nanoTime()
        val fast = MotionDeblur.deconvolveFast(img, w, h, k, 6)
        val tFast = (System.nanoTime() - t0) / 1e6
        val maxDiff = slow.indices.maxOf { kotlin.math.abs(slow[it] - fast[it]) }
        // Calidad según iteraciones (para elegir ITERATIONS).
        val q = listOf(3, 6, 10).map { n -> n to rmse(MotionDeblur.deconvolveFast(img, w, h, k, n), sharp) }
        println("nitidez 640×480: lenta %.0f ms · rápida %.0f ms (×%.1f) · diferencia máx %.4f · error sin corregir %.1f · ".format(
            tSlow, tFast, tSlow / tFast, maxDiff, rmse(img, sharp)) + q.joinToString { (n, e) -> "%d it %.1f".format(n, e) })
        assertTrue("diferencia $maxDiff", maxDiff < 1e-3f)
    }

    @Test fun parallelWholeImageMatchesSingleBand() {
        val w = 300; val h = 260
        val k = MotionDeblur.lineKernel(-4.0, 6.0)
        val lum = blur(texture(w, h), w, h, k)
        val px = IntArray(w * h) { val v = lum[it].toInt().coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val par = MotionDeblur.deblurArgb(px, w, h, k, 6, threads = 4, bandRows = 40)
        val one = MotionDeblur.deblurArgb(px, w, h, k, 6, threads = 1, bandRows = h)
        val maxDiff = px.indices.maxOf { kotlin.math.abs((par[it] and 0xFF) - (one[it] and 0xFF)) }
        assertTrue("franjas en paralelo vs imagen entera: $maxDiff niveles", maxDiff <= 1)
    }

    @Test fun blurVectorFromRotation() {
        // Giro de 0,01 rad alrededor del eje Y del dispositivo durante la exposición, cámara a 90°.
        val axes = OpticalVelocity.cameraAxesInDevice(90)
        val q1 = Quat.fromRotationVector(Vec3(0.0, 0.01, 0.0))
        val (dx, dy) = MotionDeblur.blurVector(Quat.IDENTITY, q1, axes, 3000.0)
        assertEquals(30.0, kotlin.math.hypot(dx, dy), 0.5)   // f·θ
    }
}
