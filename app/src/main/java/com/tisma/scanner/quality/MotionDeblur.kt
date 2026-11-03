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
    /**
     * Por debajo no hace falta (px a la resolución de la reconstrucción, 3200): el detalle ya
     * llega a los puntos; por encima de MAX la foto no se recupera bien.
     */
    const val MIN_REF_PX = 2.5
    const val REF_WIDTH = 3200.0
    /** El mínimo para una foto de este ancho. */
    fun minPx(width: Int) = MIN_REF_PX * maxOf(1.0, width / REF_WIDTH)
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

    /**
     * Igual que [deconvolve] pero rápido: la imagen se rodea de un margen (copia del borde) del
     * tamaño del núcleo, así el bucle interno no comprueba límites, y cada desplazamiento del
     * núcleo es un salto fijo en el array. Mismo resultado.
     */
    fun deconvolveFast(obs: FloatArray, w: Int, h: Int, kernel: List<IntArray>, iterations: Int = ITERATIONS): FloatArray {
        val m = maxOf(1, kernel.maxOf { maxOf(kotlin.math.abs(it[0]), kotlin.math.abs(it[1])) })
        val W = w + 2 * m; val H = h + 2 * m
        val n = kernel.size; val inv = 1f / n
        val offs = IntArray(n) { kernel[it][1] * W + kernel[it][0] }
        val est = FloatArray(W * H); val ratio = FloatArray(W * H)
        val ob = FloatArray(w * h) { obs[it] }
        for (y in 0 until h) for (x in 0 until w) est[(y + m) * W + x + m] = maxOf(obs[y * w + x], 1f)
        fun pad(a: FloatArray) {
            for (y in m until m + h) {
                val row = y * W
                val l = a[row + m]; val r = a[row + m + w - 1]
                for (x in 0 until m) { a[row + x] = l; a[row + m + w + x] = r }
            }
            for (y in 0 until m) {
                System.arraycopy(a, m * W, a, y * W, W)
                System.arraycopy(a, (m + h - 1) * W, a, (m + h + y) * W, W)
            }
        }
        repeat(iterations) {
            pad(est)
            for (y in 0 until h) {
                var i = (y + m) * W + m; var o = y * w
                for (x in 0 until w) {
                    var sum = 0f
                    for (k in 0 until n) sum += est[i - offs[k]]
                    ratio[i] = ob[o] / maxOf(sum * inv, 1e-3f)
                    i++; o++
                }
            }
            pad(ratio)
            for (y in 0 until h) {
                var i = (y + m) * W + m
                for (x in 0 until w) {
                    var sum = 0f
                    for (k in 0 until n) sum += ratio[i + offs[k]]
                    val c = sum * inv
                    val e = est[i] * (if (c < 0.5f) 0.5f else if (c > 2f) 2f else c)
                    est[i] = if (e < 0f) 0f else if (e > 255f) 255f else e
                    i++
                }
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) System.arraycopy(est, (y + m) * W + m, out, y * w, w)
        return out
    }

    /**
     * Imagen ARGB completa: corrige la LUMINANCIA por franjas EN PARALELO (todos los núcleos) y
     * suma la misma corrección a R, G y B (el color no cambia). Devuelve los píxeles nuevos.
     */
    fun deblurArgb(px: IntArray, w: Int, h: Int, kernel: List<IntArray>, iterations: Int = ITERATIONS,
                   threads: Int = Runtime.getRuntime().availableProcessors(), bandRows: Int = 192): IntArray {
        val out = IntArray(px.size)
        // Margen: lo que "ve" el núcleo en las iteraciones (con margen extra, el resultado es igual al de toda la imagen).
        val reach = kernel.maxOf { kotlin.math.abs(it[1]) }
        val margin = reach * iterations * 2 + 4
        val bands = (0 until h step bandRows).toList()
        // Con pocos núcleos (mientras se graba) los hilos van a prioridad baja: la cámara primero.
        val low = threads < Runtime.getRuntime().availableProcessors()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(maxOf(1, threads)) { r ->
            Thread(r, "nitidez").apply { isDaemon = true; if (low) priority = Thread.MIN_PRIORITY }
        }
        try {
            val futures = bands.map { y0 ->
                pool.submit {
                    val rows = minOf(bandRows, h - y0)
                    val a = maxOf(0, y0 - margin); val b = minOf(h, y0 + rows + margin)
                    val lum = FloatArray(w * (b - a))
                    for (y in a until b) for (x in 0 until w) {
                        val c = px[y * w + x]
                        lum[(y - a) * w + x] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                    }
                    val fixed = deconvolveFast(lum, w, b - a, kernel, iterations)
                    for (y in y0 until y0 + rows) for (x in 0 until w) {
                        val i = y * w + x; val c = px[i]
                        val R = (c shr 16) and 0xFF; val G = (c shr 8) and 0xFF; val B = c and 0xFF
                        val d = fixed[(y - a) * w + x] - lum[(y - a) * w + x]
                        out[i] = (c and (0xFF shl 24)) or ((R + d).toInt().coerceIn(0, 255) shl 16) or
                            ((G + d).toInt().coerceIn(0, 255) shl 8) or (B + d).toInt().coerceIn(0, 255)
                    }
                }
            }
            futures.forEach { it.get() }
        } finally { pool.shutdown() }
        return out
    }

    /** Iteraciones de Richardson-Lucy (medido: 6 da casi toda la mejora de 10 en la mitad de tiempo). */
    const val ITERATIONS = 6

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
