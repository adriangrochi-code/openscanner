package com.tisma.scanner.quality

import com.tisma.scanner.ins.Quat
import com.tisma.scanner.ins.Vec3
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Quitar el desenfoque de MOVIMIENTO de una foto sabiendo cómo se movió el móvil durante la
 * exposición (giróscopo/INS). El arrastre es conocido —dirección y longitud en píxeles—, así que
 * no hay que adivinarlo: se deshace con Richardson-Lucy (deconvolución iterativa que no crea
 * valores negativos y estropea poco el ruido con pocas iteraciones).
 *
 * Kotlin puro (se prueba en el ordenador). La parte de Android (leer/escribir el JPEG) está en
 * [JpegDeblur].
 */
object MotionDeblur {
    /** Por debajo no hay nada que arreglar; por encima la foto no se recupera bien. */
    const val MIN_PX = 1.5
    const val MAX_PX = 15.0

    /**
     * Arrastre de la imagen (px, ejes del sensor) entre el inicio y el final de la exposición.
     * @param q0 orientación del móvil al empezar, q1 al terminar (dispositivo -> mundo)
     * @param axes ejes de la cámara (x derecha y abajo z delante, en la imagen del sensor) en ejes del dispositivo
     * @param f focal en píxeles de la foto
     */
    fun blurVector(q0: Quat, q1: Quat, axes: Triple<Vec3, Vec3, Vec3>, f: Double): Pair<Double, Double> {
        val (cx, cy, cz) = axes
        // Un punto de la escena en el centro al empezar: dónde está al terminar (en el sensor).
        val v = (q1.conj() * q0).rotate(cz)
        val z = v dot cz
        if (z <= 1e-6) return 0.0 to 0.0
        return f * (v dot cx) / z to f * (v dot cy) / z
    }

    /** Núcleo: segmento centrado de (−dx/2, −dy/2) a (dx/2, dy/2) en pasos de ~1 px. */
    fun lineKernel(dx: Double, dy: Double): List<IntArray> {
        val len = sqrt(dx * dx + dy * dy)
        val n = maxOf(2, ceil(len).toInt() + 1)
        return (0 until n).map { k ->
            val t = k.toDouble() / (n - 1) - 0.5
            intArrayOf((dx * t).roundToInt(), (dy * t).roundToInt())
        }
    }

    /**
     * Richardson-Lucy sobre una banda w×h de luminancia (0..255). Devuelve la banda corregida.
     * Los bordes se tratan repitiendo el píxel más cercano.
     */
    fun deconvolve(obs: FloatArray, w: Int, h: Int, kernel: List<IntArray>, iterations: Int = 10): FloatArray {
        val n = kernel.size; val inv = 1f / n
        val est = FloatArray(obs.size) { maxOf(obs[it], 1f) }
        val conv = FloatArray(obs.size)
        val ratio = FloatArray(obs.size)
        fun idx(x: Int, y: Int) = y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)
        repeat(iterations) {
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                for (k in kernel) s += est[idx(x - k[0], y - k[1])]
                conv[y * w + x] = s * inv
            }
            for (i in obs.indices) ratio[i] = obs[i] / maxOf(conv[i], 1e-3f)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                for (k in kernel) s += ratio[idx(x + k[0], y + k[1])]
                val i = y * w + x
                // Corrección acotada: evita que el ruido se dispare en zonas planas.
                est[i] = (est[i] * (s * inv).coerceIn(0.5f, 2f)).coerceIn(0f, 255f)
            }
        }
        return est
    }

    /** Aplica [deconvolve] a una imagen completa por bandas (memoria acotada). */
    fun deconvolveBands(lum: (y0: Int, rows: Int) -> FloatArray, w: Int, h: Int, kernel: List<IntArray>,
                        out: (y0: Int, rows: Int, band: FloatArray) -> Unit, bandRows: Int = 256, iterations: Int = 10) {
        val margin = kernel.maxOf { kotlin.math.abs(it[1]) } * iterations.coerceAtMost(3) + 4
        var y0 = 0
        while (y0 < h) {
            val rows = minOf(bandRows, h - y0)
            val a = maxOf(0, y0 - margin); val b = minOf(h, y0 + rows + margin)
            val band = deconvolve(lum(a, b - a), w, b - a, kernel, iterations)
            out(y0, rows, band.copyOfRange((y0 - a) * w, (y0 - a + rows) * w))
            y0 += rows
        }
    }
}
