package com.tisma.scanner.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import com.tisma.scanner.quality.CoverageMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Anillo de captura del modo vídeo 360, al estilo de las apps de escaneo: rodea la pieza en la
 * vista previa y muestra los sectores de la altura actual (gris = falta, amarillo = una vista,
 * verde = bien cubierto), dónde está la cámara, el hueco más cercano (parpadea) y hacia dónde
 * girar. El centro queda libre para ver la pieza.
 *
 * Azimut 0 arriba y creciendo en sentido horario, igual que la cúpula pequeña.
 */
@Composable
fun OrbitRing(
    rings: List<CoverageMap.Ring>,
    counts: List<IntArray>,
    guide: CoverageMap.Guidance?,
    liveAzDeg: Double,
    frames: Int,
    modifier: Modifier = Modifier,
) {
    val pulse = if (LocalAnimate.current) rememberInfiniteTransition(label = "hueco").animateFloat(
        0.25f, 0.9f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha").value else 0.8f
    // Destello del marcador cada vez que se guarda un fotograma.
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(frames) { if (frames > 0) { flash = true; kotlinx.coroutines.delay(140); flash = false } }

    val ringIdx = guide?.ring ?: 1.coerceAtMost(rings.size - 1)
    val ring = rings[ringIdx]
    val ringCounts = counts.getOrNull(ringIdx)
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension * 0.42f
        val thick = size.minDimension * 0.035f
        val step = 360f / ring.bins
        // Pista y sectores.
        drawCircle(Color(0x55000000), r, c, style = Stroke(thick * 1.6f))
        for (b in 0 until ring.bins) {
            val n = ringCounts?.getOrNull(b) ?: 0
            val col = when {
                n >= 2 -> Color(0xE643A047)
                n == 1 -> Color(0xE6FDD835)
                else -> Color(0x55FFFFFF)
            }
            drawArc(col, -90f + b * step + step * 0.08f, step * 0.84f, false,
                Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(thick, cap = StrokeCap.Butt))
        }
        // Hueco objetivo: arco por fuera que parpadea.
        val gs = guide?.gapStartDeg; val ge = guide?.gapEndDeg
        if (gs != null && ge != null) {
            val ro = r + thick * 1.4f
            drawArc(Color(0xFFB57BFF).copy(alpha = pulse), -90f + gs.toFloat(), (ge - gs).toFloat(), false,
                Offset(c.x - ro, c.y - ro), Size(ro * 2, ro * 2), style = Stroke(thick * 0.45f, cap = StrokeCap.Round))
        }
        // Cámara (marcador) y flecha hacia el hueco.
        val a = Math.toRadians(liveAzDeg - 90.0)
        val m = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        val turn = guide?.turn ?: 0
        if (turn != 0) {
            // Tangente en el sentido de giro (horario = azimut creciente).
            val tx = (-sin(a) * turn).toFloat(); val ty = (cos(a) * turn).toFloat()
            val nx = cos(a).toFloat(); val ny = sin(a).toFloat()
            val tip = Offset(m.x + tx * thick * 3.2f, m.y + ty * thick * 3.2f)
            val base = Offset(m.x + tx * thick * 1.6f, m.y + ty * thick * 1.6f)
            val w = thick * 0.9f
            val p = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(base.x + nx * w, base.y + ny * w)
                lineTo(base.x - nx * w, base.y - ny * w)
                close()
            }
            drawPath(p, Color.White)
        }
        drawCircle(if (flash) Color(0xFF66BB6A) else Color.White, thick * (if (flash) 1.25f else 0.95f), m)
        drawCircle(Color(0xFF1E1E22), thick * 0.45f, m)
    }
}

/** Animaciones continuas (parpadeo, giro automático). Las pruebas de captura las apagan. */
val LocalAnimate = androidx.compose.runtime.staticCompositionLocalOf { true }

/** Texto de guía para el modo vídeo 360 (lo que haría decir una app de escaneo). */
fun orbitHint(rings: List<CoverageMap.Ring>, guide: CoverageMap.Guidance?, frames: Int): String {
    if (guide == null || frames == 0) return "Rodea la pieza despacio, a media altura (~40°)"
    val r = guide.ring ?: return "Inclina el móvil hacia la pieza: míralo desde más arriba"
    val pct = (guide.ringCoverage * 100).toInt()
    val label = rings[r].label
    val next = guide.nextRing
    return when {
        guide.gapStartDeg != null && guide.turn != 0 -> {
            val missing = ((guide.gapEndDeg ?: 0.0) - guide.gapStartDeg).toInt()
            "Sigue rodeando · altura $label al $pct % · hueco de $missing°"
        }
        guide.gapStartDeg != null -> "Bien, sigue a este ritmo · altura $label al $pct %"
        next != null && next > r -> "Altura $label completa ✓ · sube a ${rings[next].label}"
        next != null -> "Altura $label completa ✓ · baja a ${rings[next].label}"
        else -> "✓ Vuelta completa en todas las alturas: puedes terminar"
    }
}
