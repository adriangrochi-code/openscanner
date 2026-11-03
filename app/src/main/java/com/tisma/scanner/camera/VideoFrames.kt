package com.tisma.scanner.camera

import android.media.Image
import android.util.Size

/**
 * Utilidades del modo "Vídeo 360": un único flujo YUV continuo del que se sacan
 * a la vez el velocímetro (fotograma pequeño en grises) y los fotogramas clave (JPEG).
 */
object VideoFrames {
    /**
     * Tamaños 4:3 a probar, de mayor a menor: el más grande que el móvil admita junto a la vista
     * previa en flujo continuo. Uno por escalón (≈12, 8, 5 y 3 MP) para no probar decenas.
     */
    fun candidateSizes(sizes: List<Size>, maxPixels: Long = 12_500_000L): List<Size> {
        val fourThree = sizes.filter { kotlin.math.abs(it.width * 3 - it.height * 4) < 8 && it.width >= 1280 }
            .sortedByDescending { it.width.toLong() * it.height }
        val steps = listOf(maxPixels, 8_500_000L, 5_100_000L, 3_000_000L)
        return steps.mapNotNull { cap -> fourThree.firstOrNull { it.width.toLong() * it.height <= cap } }.distinct()
    }

    /** YUV_420_888 -> NV21 (lo que entiende YuvImage). Camino rápido si el plano V ya está entrelazado. */
    fun toNv21(img: Image): ByteArray {
        val w = img.width; val h = img.height
        val out = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        val yb = y.buffer
        if (y.rowStride == w) { yb.position(0); yb.get(out, 0, w * h) }
        else for (r in 0 until h) { yb.position(r * y.rowStride); yb.get(out, r * w, w) }
        val ub = u.buffer; val vb = v.buffer
        val cw = w / 2; val ch = h / 2
        var o = w * h
        if (u.pixelStride == 2 && v.pixelStride == 2 && v.rowStride == w && vb.remaining() >= w * ch - 1) {
            // V empieza en V0 U0 V1 U1…: ya es NV21 salvo el último U.
            vb.position(0); vb.get(out, o, w * ch - 1)
            out[out.size - 1] = ub.get(ub.limit() - 1)
            return out
        }
        for (r in 0 until ch) for (c in 0 until cw) {
            out[o++] = vb.get(r * v.rowStride + c * v.pixelStride)
            out[o++] = ub.get(r * u.rowStride + c * u.pixelStride)
        }
        return out
    }

    /** Fotograma w×h en grises: 4×4 muestras por bloque (rápido aunque la imagen sea de 12 MP). */
    fun gray(img: Image, w: Int, h: Int): FloatArray {
        val p = img.planes[0]; val buf = p.buffer
        val bw = img.width / w; val bh = img.height / h
        val sx = maxOf(1, bw / 4); val sy = maxOf(1, bh / 4)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0; var n = 0
            var j = 0
            while (j < bh) {
                var i = 0
                while (i < bw) {
                    acc += buf.get((y * bh + j) * p.rowStride + (x * bw + i) * p.pixelStride).toInt() and 0xFF
                    n++; i += sx
                }
                j += sy
            }
            out[y * w + x] = acc.toFloat() / n
        }
        return out
    }

    /** Grises w×h desde NV21 (W×H), con media 2×2 para no tener aliasing. */
    fun grayFromNv21(nv21: ByteArray, W: Int, H: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val sx = W.toDouble() / w; val sy = H.toDouble() / h
        for (y in 0 until h) {
            val yy = ((y + 0.5) * sy).toInt().coerceIn(0, H - 2)
            for (x in 0 until w) {
                val xx = ((x + 0.5) * sx).toInt().coerceIn(0, W - 2)
                val i = yy * W + xx
                out[y * w + x] = ((nv21[i].toInt() and 0xFF) + (nv21[i + 1].toInt() and 0xFF) +
                    (nv21[i + W].toInt() and 0xFF) + (nv21[i + W + 1].toInt() and 0xFF)) / 4f
            }
        }
        return out
    }

    /** Miniatura ARGB w×h desde NV21 (BT.601, rango completo como el JPEG). */
    fun rgbFromNv21(nv21: ByteArray, W: Int, H: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        val sx = W.toDouble() / w; val sy = H.toDouble() / h
        for (y in 0 until h) for (x in 0 until w) {
            val xx = ((x + 0.5) * sx).toInt().coerceIn(0, W - 1); val yy = ((y + 0.5) * sy).toInt().coerceIn(0, H - 1)
            val yv = (nv21[yy * W + xx].toInt() and 0xFF).toDouble()
            val c = W * H + (yy / 2) * W + (xx / 2) * 2
            val v = (nv21[c].toInt() and 0xFF) - 128.0; val u = (nv21[c + 1].toInt() and 0xFF) - 128.0
            val r = (yv + 1.402 * v).toInt().coerceIn(0, 255)
            val g = (yv - 0.344 * u - 0.714 * v).toInt().coerceIn(0, 255)
            val b = (yv + 1.772 * u).toInt().coerceIn(0, 255)
            out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }
}
