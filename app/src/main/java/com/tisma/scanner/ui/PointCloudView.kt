package com.tisma.scanner.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Visor de la nube de puntos en vivo. Gira sola despacio; arrastrando se gira a mano (360° en
 * horizontal, ±80° en vertical). Sin color: gris según la cercanía (se ve la forma); con color:
 * el de las fotos, y los puntos aún sin pintar en gris.
 */
@Composable
fun PointCloudView(
    xyz: FloatArray,
    colors: IntArray,
    showColor: Boolean,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    maxDrawn: Int = 15_000,
) {
    val spin = if (LocalAnimate.current) rememberInfiniteTransition(label = "giro").animateFloat(
        0f, 360f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "yaw").value else 30f
    var yawUser by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(25f) }
    var touched by remember { mutableStateOf(false) }
    val n = xyz.size / 3
    // Centro y escala robustos (mediana y percentil 90): un punto suelto no encoge todo.
    val frame = remember(xyz) {
        if (n == 0) null else {
            fun med(k: Int) = (0 until n).map { xyz[3 * it + k] }.sorted()[n / 2]
            val c = floatArrayOf(med(0), med(1), med(2))
            val r = (0 until n).map { i ->
                val dx = xyz[3 * i] - c[0]; val dy = xyz[3 * i + 1] - c[1]; val dz = xyz[3 * i + 2] - c[2]
                sqrt(dx * dx + dy * dy + dz * dz)
            }.sorted()[(n * 9) / 10].coerceAtLeast(1e-4f)
            c to r
        }
    }
    val mod = if (interactive) modifier.pointerInput(Unit) {
        detectDragGestures { _, d ->
            touched = true
            yawUser += d.x * 0.4f
            pitch = (pitch + d.y * 0.3f).coerceIn(-80f, 80f)
        }
    } else modifier
    Canvas(mod) {
        val (c, radius) = frame ?: return@Canvas
        val yaw = Math.toRadians((yawUser + if (touched) 0f else spin).toDouble())
        val pt = Math.toRadians(pitch.toDouble())
        val cy = cos(yaw).toFloat(); val sy = sin(yaw).toFloat(); val cp = cos(pt).toFloat(); val sp = sin(pt).toFloat()
        val scale = size.minDimension * 0.45f / radius
        val ox = size.width / 2; val oy = size.height / 2
        val stride = maxOf(1, n / maxDrawn)
        val dot = maxOf(1.5f, size.minDimension / 260f)
        // Proyección ortográfica: giro en Z (yaw) y luego inclinación (pitch). Z del mundo = arriba.
        var i = 0
        val items = ArrayList<FloatArray>(n / stride + 1)
        while (i < n) {
            val x = xyz[3 * i] - c[0]; val y = xyz[3 * i + 1] - c[1]; val z = xyz[3 * i + 2] - c[2]
            val xr = x * cy - y * sy; val yr = x * sy + y * cy
            val depth = yr * cp + z * sp            // hacia el fondo
            val up = z * cp - yr * sp
            items += floatArrayOf(ox + xr * scale, oy - up * scale, depth / radius, i.toFloat())
            i += stride
        }
        items.sortByDescending { it[2] }              // lo lejano primero
        for (it in items) {
            val near = (0.5f - it[2] * 0.35f).coerceIn(0.15f, 0.9f)
            val ci = colors.getOrElse(it[3].toInt()) { 0 }
            val col = if (showColor && ci != 0) Color(ci) else Color(near, near, near * 1.05f)
            drawRect(col, Offset(it[0] - dot / 2, it[1] - dot / 2), Size(dot, dot))
        }
    }
}
