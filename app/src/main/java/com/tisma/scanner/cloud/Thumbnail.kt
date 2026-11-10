package com.tisma.scanner.cloud

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * La miniatura de una pieza para la biblioteca de Tisma, dibujada en el teléfono a partir de su STL (el servidor no
 * dibuja STL). Sin Android: devuelve los píxeles ARGB; la app los pasa a WebP (≤ 64 KB) con Bitmap.
 *
 * Vista isométrica desde arriba y de costado, sombreado plano con una luz fija, fondo claro. Lo bastante para
 * reconocer la pieza en una lista; no pretende ser un render.
 */
object Thumbnail {
    const val BACKGROUND = 0xFFF2F2F5.toInt()

    /** Triángulos del STL (binario o de texto), 9 floats por triángulo (tres vértices). */
    fun readStl(file: File): FloatArray {
        val bytes = file.readBytes()
        val ascii = bytes.size > 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "solid" &&
            String(bytes, 0, minOf(bytes.size, 512), Charsets.US_ASCII).contains("facet")
        if (ascii) {
            val v = Regex("vertex\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)").findAll(String(bytes, Charsets.US_ASCII))
                .flatMap { m -> m.groupValues.drop(1).map { it.toFloat() } }.toList()
            return v.toFloatArray()
        }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.getInt(80)
        require(84L + 50L * n <= bytes.size) { "STL incompleto" }
        val out = FloatArray(n * 9)
        for (t in 0 until n) {
            val base = 84 + 50 * t + 12  // tras la normal
            for (k in 0 until 9) out[t * 9 + k] = b.getFloat(base + 4 * k)
        }
        return out
    }

    /** Píxeles ARGB (size × size) de la pieza. */
    fun render(tris: FloatArray, size: Int = 256): IntArray {
        val px = IntArray(size * size) { BACKGROUND }
        if (tris.size < 9) return px
        // Vista: girar 35° alrededor de Z y mirar 30° desde arriba (Z arriba en el STL de Tisma).
        val (ca, sa) = Math.cos(Math.toRadians(35.0)).toFloat() to Math.sin(Math.toRadians(35.0)).toFloat()
        val (ce, se) = Math.cos(Math.toRadians(60.0)).toFloat() to Math.sin(Math.toRadians(60.0)).toFloat()
        val n = tris.size / 3
        val sx = FloatArray(n); val sy = FloatArray(n); val sz = FloatArray(n)
        for (i in 0 until n) {
            val x = tris[i * 3]; val y = tris[i * 3 + 1]; val z = tris[i * 3 + 2]
            val xr = ca * x - sa * y; val yr = sa * x + ca * y
            sx[i] = xr                       // pantalla: x a la derecha
            sy[i] = -(ce * z - se * yr)      // y hacia abajo
            sz[i] = se * z + ce * yr         // profundidad: mayor = más lejos de la cámara
        }
        val minX = sx.min(); val maxX = sx.max(); val minY = sy.min(); val maxY = sy.max()
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-6f)
        val scale = size * 0.86f / span
        val ox = (size - (maxX - minX) * scale) / 2 - minX * scale
        val oy = (size - (maxY - minY) * scale) / 2 - minY * scale
        for (i in 0 until n) { sx[i] = sx[i] * scale + ox; sy[i] = sy[i] * scale + oy }
        val depth = FloatArray(size * size) { Float.MAX_VALUE }
        // Luz desde arriba a la izquierda, hacia la pieza (en coordenadas de vista).
        val lx = -0.4f; val ly = -0.6f; val lz = -0.7f
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        for (t in 0 until n / 3) {
            val a = t * 3; val b = a + 1; val c = a + 2
            // Normal en coordenadas de vista (para la luz); cara de frente o de espalda, se ilumina igual.
            val ux = sx[b] - sx[a]; val uy = sy[b] - sy[a]; val uz = (sz[b] - sz[a]) * scale
            val vx = sx[c] - sx[a]; val vy = sy[c] - sy[a]; val vz = (sz[c] - sz[a]) * scale
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl < 1e-9f) continue
            nx /= nl; ny /= nl; nz /= nl
            val lambert = abs(nx * lx + ny * ly + nz * lz) / ll
            val shade = (0.28f + 0.72f * lambert).coerceIn(0f, 1f)
            val colour = (0xFF shl 24) or (channel(0x6B, shade) shl 16) or (channel(0x4E, shade) shl 8) or channel(0xD6, shade)
            fill(sx[a], sy[a], sz[a], sx[b], sy[b], sz[b], sx[c], sy[c], sz[c], colour, px, depth, size)
        }
        return px
    }

    private fun channel(base: Int, shade: Float) = (base * shade + 30 * (1 - shade)).toInt().coerceIn(0, 255)

    private fun fill(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
                     colour: Int, px: IntArray, depth: FloatArray, size: Int) {
        val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if (abs(area) < 1e-9f) return
        val xa = max(0, min(x0, min(x1, x2)).toInt()); val xb = min(size - 1, max(x0, max(x1, x2)).toInt() + 1)
        val ya = max(0, min(y0, min(y1, y2)).toInt()); val yb = min(size - 1, max(y0, max(y1, y2)).toInt() + 1)
        for (y in ya..yb) {
            val py = y + 0.5f
            for (x in xa..xb) {
                val pxf = x + 0.5f
                val w0 = ((x1 - pxf) * (y2 - py) - (x2 - pxf) * (y1 - py)) / area
                val w1 = ((x2 - pxf) * (y0 - py) - (x0 - pxf) * (y2 - py)) / area
                val w2 = 1 - w0 - w1
                if (w0 < 0 || w1 < 0 || w2 < 0) continue
                val z = w0 * z0 + w1 * z1 + w2 * z2
                val i = y * size + x
                if (z < depth[i]) { depth[i] = z; px[i] = colour }
            }
        }
    }
}
