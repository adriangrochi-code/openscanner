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
        // Triángulos visibles: (profundidad, trozo, índice).
        class T(val depth: Float, val f: Int, val t: Int, val shade: Float)
        val proj = fragments.map { f ->
            val n = f.xyz.size / 3
            val out = FloatArray(n * 3)
            for (i in 0 until n) {
                val x = f.xyz[3 * i] - c[0]; val y = f.xyz[3 * i + 1] - c[1]; val z = f.xyz[3 * i + 2] - c[2]
                val xr = x * cy - y * sy; val yr = x * sy + y * cy
                out[3 * i] = ox + xr * scale
                // Inclinación positiva = mirar desde arriba.
                out[3 * i + 1] = oy - (z * cp + yr * sp) * scale
                out[3 * i + 2] = yr * cp - z * sp          // hacia el fondo
            }
            out
        }
        val list = ArrayList<T>()
        for ((fi, f) in fragments.withIndex()) {
            val p = proj[fi]
            // Normal hacia la cámara que hizo el trozo (−viewDir) para saber qué cara es la de fuera.
            val vd = f.viewDir
            for (t in 0 until f.tris.size / 3) {
                val a = f.tris[3 * t]; val b = f.tris[3 * t + 1]; val d = f.tris[3 * t + 2]
                val ax = f.xyz[3 * a]; val ay = f.xyz[3 * a + 1]; val az = f.xyz[3 * a + 2]
                val ux = f.xyz[3 * b] - ax; val uy = f.xyz[3 * b + 1] - ay; val uz = f.xyz[3 * b + 2] - az
                val wx = f.xyz[3 * d] - ax; val wy = f.xyz[3 * d + 1] - ay; val wz = f.xyz[3 * d + 2] - az
                var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
                val nl = sqrt(nx * nx + ny * ny + nz * nz); if (nl < 1e-12f) continue
                nx /= nl; ny /= nl; nz /= nl
                if (nx * vd.x + ny * vd.y + nz * vd.z > 0) { nx = -nx; ny = -ny; nz = -nz }
                // Dirección hacia el observador en el mundo: contraria al gradiente de la profundidad
                // (depth = (x·sy + y·cy)·cp − z·sp).
                val tx = -sy * cp; val ty = -cy * cp; val tz = sp
                val facing = nx * tx + ny * ty + nz * tz
                if (facing <= 0.05f) continue          // cara de atrás
                val depth = (p[3 * a + 2] + p[3 * b + 2] + p[3 * d + 2]) / 3
                list += T(depth, fi, t, 0.30f + 0.70f * facing)
            }
        }
        list.sortByDescending { it.depth }
        drawIntoCanvas { canvas ->
            val nc: ACanvas = canvas.nativeCanvas
            // Se agrupan triángulos seguidos del mismo trozo en una sola llamada.
            var i = 0
            while (i < list.size) {
                val fi = list[i].f
                var j = i
                while (j < list.size && list[j].f == fi && j - i < 200) j++
                val f = fragments[fi]; val p = proj[fi]
                val cnt = j - i
                val verts = FloatArray(cnt * 6)
                val texs = if (textured && shaders[f.id] != null) FloatArray(cnt * 6) else null
                val cols = if (texs == null) IntArray(cnt * 3) else null
                for (k in 0 until cnt) {
                    val tr = list[i + k]
                    for (e in 0..2) {
                        val v = f.tris[3 * tr.t + e]
                        verts[6 * k + 2 * e] = p[3 * v]; verts[6 * k + 2 * e + 1] = p[3 * v + 1]
                        if (texs != null) { texs[6 * k + 2 * e] = f.uv[2 * v]; texs[6 * k + 2 * e + 1] = f.uv[2 * v + 1] }
                        if (cols != null) {
                            val g = (tr.shade * 225).toInt().coerceIn(0, 255)
                            cols[3 * k + e] = (0xFF shl 24) or (g shl 16) or ((g * 0.97f).toInt() shl 8) or (g * 0.92f).toInt()
                        }
                    }
                }
                paint.shader = if (texs != null) shaders[f.id] else null
                nc.drawVertices(ACanvas.VertexMode.TRIANGLES, verts.size, verts, 0, texs, 0, cols, 0, null, 0, 0, paint)
                i = j
            }
        }
    }
}

/** Nada que ver todavía: ¿cuántos triángulos hay? */
fun surfaceTriangles(fragments: List<LivePointCloud.Fragment>) = fragments.sumOf { it.tris.size / 3 }

