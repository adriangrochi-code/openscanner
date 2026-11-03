package com.tisma.scanner.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as ACanvas
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.tisma.scanner.recon.LivePointCloud
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Animaciones continuas (giro automático). Las pruebas de captura de pantalla las apagan. */
val LocalAnimate = staticCompositionLocalOf { true }

/**
 * La pieza en vivo como SUPERFICIE: los trozos de [LivePointCloud.Fragment] dibujados como
 * triángulos, de atrás hacia delante, sin las caras que miran hacia el otro lado.
 *  - textured = false: gris "arcilla" con sombreado (se aprecia la forma).
 *  - textured = true: cada trozo con la imagen real de la vista desde la que se hizo.
 * Gira sola; con `interactive` se gira arrastrando (360° en horizontal).
 */
@Composable
fun PieceView(
    fragments: List<LivePointCloud.Fragment>,
    textured: Boolean,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
) {
    val spin = if (LocalAnimate.current) rememberInfiniteTransition(label = "giro").animateFloat(
        0f, 360f, infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "yaw").value else 35f
    var yawUser by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(28f) }
    var touched by remember { mutableStateOf(false) }
    // Centro y tamaño robustos de toda la pieza.
    val frame = remember(fragments) {
        val all = fragments.flatMap { f -> (0 until f.xyz.size / 3).map { i -> floatArrayOf(f.xyz[3 * i], f.xyz[3 * i + 1], f.xyz[3 * i + 2]) } }
        if (all.isEmpty()) null else {
            fun med(k: Int) = all.map { it[k] }.sorted()[all.size / 2]
            val c = floatArrayOf(med(0), med(1), med(2))
            val r = all.map { p -> sqrt((p[0] - c[0]) * (p[0] - c[0]) + (p[1] - c[1]) * (p[1] - c[1]) + (p[2] - c[2]) * (p[2] - c[2])) }
                .sorted()[(all.size * 95) / 100].coerceAtLeast(1e-4f)
            c to r
        }
    }
    val shaders = remember(fragments) {
        fragments.associate { f ->
            f.id to f.thumb?.let { BitmapShader(Bitmap.createBitmap(it, f.tw, f.th, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        }
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    // Lo que no cambia al girar se calcula UNA vez por malla: normal hacia fuera y centro de cada
    // triángulo. En cada fotograma sólo se proyecta, se descartan las caras de atrás y se ordena
    // (con arrays primitivos: nada de objetos por triángulo, que en el móvil frenaba el giro).
    val geo = remember(fragments) { TriGeometry.of(fragments) }
    val mod = if (interactive) modifier.pointerInput(Unit) {
        detectDragGestures { _, d ->
            touched = true
            yawUser += d.x * 0.4f
            pitch = (pitch + d.y * 0.3f).coerceIn(-85f, 85f)
        }
    } else modifier
    Canvas(mod) {
        val (c, radius) = frame ?: return@Canvas
        val yaw = Math.toRadians((yawUser + if (touched) 0f else spin).toDouble())
        val pt = Math.toRadians(pitch.toDouble())
        val cy = cos(yaw).toFloat(); val sy = sin(yaw).toFloat(); val cp = cos(pt).toFloat(); val sp = sin(pt).toFloat()
        val scale = size.minDimension * 0.42f / radius
        val ox = size.width / 2; val oy = size.height / 2
        // Proyección de los vértices de cada trozo.
        val proj = fragments.map { f ->
            val n = f.xyz.size / 3
            val out = FloatArray(n * 2)
            for (i in 0 until n) {
                val x = f.xyz[3 * i] - c[0]; val y = f.xyz[3 * i + 1] - c[1]; val z = f.xyz[3 * i + 2] - c[2]
                val xr = x * cy - y * sy; val yr = x * sy + y * cy
                out[2 * i] = ox + xr * scale
                out[2 * i + 1] = oy - (z * cp + yr * sp) * scale     // inclinación positiva = desde arriba
            }
            out
        }
        // Hacia el observador (contrario al gradiente de la profundidad = (x·sy + y·cy)·cp − z·sp).
        val tx = -sy * cp; val ty = -cy * cp; val tz = sp
        val g = geo
        var nVis = 0
        for (t in 0 until g.count) {
            val facing = g.nx[t] * tx + g.ny[t] * ty + g.nz[t] * tz
            if (facing <= 0.05f) { g.shade[t] = -1f; continue }       // cara de atrás
            g.shade[t] = 0.30f + 0.70f * facing
            val x = g.cx[t] - c[0]; val y = g.cy[t] - c[1]; val z = g.cz[t] - c[2]
            val depth = (x * sy + y * cy) * cp - z * sp
            // Clave ordenable: profundidad (de lejos a cerca) en los 32 bits altos, triángulo en los bajos.
            g.keys[nVis++] = (sortable(-depth).toLong() shl 32) or t.toLong()
        }
        java.util.Arrays.sort(g.keys, 0, nVis)
        drawIntoCanvas { canvas ->
            val nc: ACanvas = canvas.nativeCanvas
            var i = 0
            while (i < nVis) {
                val fi = g.frag[(g.keys[i] and 0xFFFFFFFFL).toInt()]
                var j = i
                while (j < nVis && g.frag[(g.keys[j] and 0xFFFFFFFFL).toInt()] == fi && j - i < BATCH) j++
                val f = fragments[fi]; val p = proj[fi]
                val cnt = j - i
                val textured0 = textured && f.vertexColors == null && shaders[f.id] != null
                val vcol = if (textured) f.vertexColors else null
                for (k in 0 until cnt) {
                    val t = (g.keys[i + k] and 0xFFFFFFFFL).toInt()
                    val base = 3 * g.tri[t]; val shade = g.shade[t]
                    for (e in 0..2) {
                        val v = f.tris[base + e]
                        g.verts[6 * k + 2 * e] = p[2 * v]; g.verts[6 * k + 2 * e + 1] = p[2 * v + 1]
                        if (textured0) { g.texs[6 * k + 2 * e] = f.uv[2 * v]; g.texs[6 * k + 2 * e + 1] = f.uv[2 * v + 1] }
                        else if (vcol != null) {
                            // Color de la foto con un poco de sombreado (se aprecia el relieve).
                            val col = vcol[v]; val sh = 0.55f + 0.45f * shade
                            g.cols[3 * k + e] = (0xFF shl 24) or ((((col shr 16) and 0xFF) * sh).toInt().coerceIn(0, 255) shl 16) or
                                ((((col shr 8) and 0xFF) * sh).toInt().coerceIn(0, 255) shl 8) or ((col and 0xFF) * sh).toInt().coerceIn(0, 255)
                        } else {
                            val gr = (shade * 225).toInt().coerceIn(0, 255)
                            g.cols[3 * k + e] = (0xFF shl 24) or (gr shl 16) or ((gr * 0.97f).toInt() shl 8) or (gr * 0.92f).toInt()
                        }
                    }
                }
                paint.shader = if (textured0) shaders[f.id] else null
                nc.drawVertices(ACanvas.VertexMode.TRIANGLES, cnt * 6, g.verts, 0, if (textured0) g.texs else null, 0,
                    if (textured0) null else g.cols, 0, null, 0, 0, paint)
                i = j
            }
        }
    }
}

private const val BATCH = 4000

/** Float -> int que conserva el orden (para ordenar por profundidad con un array de long). */
private fun sortable(f: Float): Int { val b = java.lang.Float.floatToRawIntBits(f); return if (b >= 0) b xor Int.MIN_VALUE else b.inv() }

/** Triángulos de todos los trozos con su normal hacia fuera y su centro (fijos al girar) y búferes reutilizables. */
private class TriGeometry(val count: Int) {
    val frag = IntArray(count); val tri = IntArray(count)
    val nx = FloatArray(count); val ny = FloatArray(count); val nz = FloatArray(count)
    val cx = FloatArray(count); val cy = FloatArray(count); val cz = FloatArray(count)
    val shade = FloatArray(count); val keys = LongArray(count)
    val verts = FloatArray(BATCH * 6); val texs = FloatArray(BATCH * 6); val cols = IntArray(BATCH * 3)

    companion object {
        fun of(fragments: List<LivePointCloud.Fragment>): TriGeometry {
            val g = TriGeometry(fragments.sumOf { it.tris.size / 3 })
            var t = 0
            for ((fi, f) in fragments.withIndex()) {
                val vd = f.viewDir
                for (k in 0 until f.tris.size / 3) {
                    val a = f.tris[3 * k]; val b = f.tris[3 * k + 1]; val d = f.tris[3 * k + 2]
                    val ax = f.xyz[3 * a]; val ay = f.xyz[3 * a + 1]; val az = f.xyz[3 * a + 2]
                    val ux = f.xyz[3 * b] - ax; val uy = f.xyz[3 * b + 1] - ay; val uz = f.xyz[3 * b + 2] - az
                    val wx = f.xyz[3 * d] - ax; val wy = f.xyz[3 * d + 1] - ay; val wz = f.xyz[3 * d + 2] - az
                    var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
                    val nl = sqrt(nx * nx + ny * ny + nz * nz)
                    if (nl > 1e-12f) { nx /= nl; ny /= nl; nz /= nl }
                    // Trozos de la vista en vivo: la cara de fuera es la que miraba la cámara.
                    // Malla cerrada (modelo local): ya viene orientada hacia fuera.
                    if (!f.outward && nx * vd.x + ny * vd.y + nz * vd.z > 0) { nx = -nx; ny = -ny; nz = -nz }
                    g.frag[t] = fi; g.tri[t] = k
                    g.nx[t] = nx; g.ny[t] = ny; g.nz[t] = nz
                    g.cx[t] = (ax + f.xyz[3 * b] + f.xyz[3 * d]) / 3; g.cy[t] = (ay + f.xyz[3 * b + 1] + f.xyz[3 * d + 1]) / 3
                    g.cz[t] = (az + f.xyz[3 * b + 2] + f.xyz[3 * d + 2]) / 3
                    t++
                }
            }
            return g
        }
    }
}

/** Nada que ver todavía: ¿cuántos triángulos hay? */
fun surfaceTriangles(fragments: List<LivePointCloud.Fragment>) = fragments.sumOf { it.tris.size / 3 }

