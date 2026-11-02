package com.tisma.scanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tisma.scanner.quality.CoverageMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cúpula vista desde arriba: el centro es la vista cenital y el borde, las vistas rasantes.
 * Cada sector se colorea según cuántas fotos nítidas lo cubren. El punto blanco es hacia
 * dónde apunta ahora la cámara; "arriba" en el dibujo = donde empezó el escaneo.
 */
@Composable
fun CoverageDome(
    rings: List<CoverageMap.Ring>,
    counts: List<IntArray>,
    liveAzDeg: Double,
    liveElDeg: Double,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
) {
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val rMax = this.size.minDimension / 2 * 0.95f
        fun radius(elDeg: Double) = ((90.0 - elDeg) / 90.0 * rMax).toFloat()

        drawCircle(Color(0x88000000), rMax, c)
        for ((i, ring) in rings.withIndex()) {
            val outer = radius(ring.elMinDeg)
            val inner = radius(ring.elMaxDeg)
            val mid = (outer + inner) / 2
            val width = (outer - inner) * 0.85f
            val step = 360f / ring.bins
            val ringCounts = counts.getOrNull(i)
            for (b in 0 until ring.bins) {
                val n = ringCounts?.getOrNull(b) ?: 0
                val color = when {
                    n >= 2 -> Color(0xCC43A047)   // bien cubierto
                    n == 1 -> Color(0xCCFDD835)   // cubierto una vez
                    else -> Color(0x40FFFFFF)     // falta
                }
                // Azimut 0 arriba, creciendo en sentido horario.
                drawArc(
                    color = color,
                    startAngle = -90f + b * step + step * 0.06f,
                    sweepAngle = step * 0.88f,
                    useCenter = false,
                    topLeft = Offset(c.x - mid, c.y - mid),
                    size = Size(mid * 2, mid * 2),
                    style = Stroke(width = if (ring.elMaxDeg >= 90) inner.coerceAtLeast(width) else width),
                )
            }
        }
        // Posición actual de la cámara.
        if (liveElDeg > 0) {
            val r = radius(liveElDeg.coerceAtMost(90.0))
            val a = Math.toRadians(liveAzDeg - 90.0)
            drawCircle(Color.White, 6f, Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()))
        }
    }
}
