package com.tisma.scanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.tisma.scanner.ins.Vec3

/** Pose de la cámara en este instante, en el marco de la reconstrucción en vivo. */
data class ArPose(
    val xAxis: Vec3, val yAxis: Vec3, val zAxis: Vec3, val center: Vec3,
    /** Focal en píxeles de la imagen de referencia [refW]×[refH] (orientación del sensor). */
    val f: Double, val refW: Int, val refH: Int, val sensorOrientation: Int,
)

/** Una foto tomada, flotando donde se tomó (miniatura ya girada a vertical). */
class ArCard(val id: Int, val center: Vec3, val thumb: IntArray, val w: Int, val h: Int)

/**
 * Proyección a la pantalla: imagen del sensor (u a lo largo del lado largo) -> vista vertical
 * de tamaño W×H tal como la muestra la vista previa (girada según SENSOR_ORIENTATION).
 */
fun ArPose.toScreen(p: Vec3, W: Float, H: Float): Triple<Float, Float, Double>? {
    val d = p - center
    val z = d dot zAxis
    if (z <= 1e-3) return null
    val u = refW / 2.0 + f * (d dot xAxis) / z
    val v = refH / 2.0 + f * (d dot yAxis) / z
    val (x, y) = if (((sensorOrientation % 360) + 360) % 360 == 270) (v / refH) to (1 - u / refW)
        else (1 - v / refH) to (u / refW)
    return Triple((x * W).toFloat(), (y * H).toFloat(), z)
}

/** Rojo (poco visto) -> amarillo -> verde (bien cubierto). */
fun qualityColor(q: Float): Color {
    val t = q.coerceIn(0f, 1f)
    return if (t < 0.5f) Color(0.95f, 0.25f + 1.3f * t, 0.2f, 0.9f) else Color(0.95f - 1.5f * (t - 0.5f), 0.9f, 0.25f, 0.9f)
}

/**
 * Guía en realidad aumentada sobre la vista previa: los puntos de la pieza coloreados según lo
 * bien cubiertos que están y las fotos ya tomadas flotando en el sitio desde donde se tomaron.
 */
@Composable
fun ArOverlay(
    pose: ArPose?, xyz: FloatArray, quality: FloatArray, cards: List<ArCard>,
    showPoints: Boolean, showCards: Boolean, modifier: Modifier = Modifier,
) {
    val bitmaps = remember(cards) { cards.associate { c -> c.id to Bitmap.createBitmap(c.thumb, c.w, c.h, Bitmap.Config.ARGB_8888).asImageBitmap() } }
    Canvas(modifier) {
        val p = pose ?: return@Canvas
        val W = size.width; val H = size.height
        val pxPerRef = H / p.refW            // el lado largo del sensor va en vertical
        if (showCards) {
            // De lejos a cerca; las muy cercanas (detrás o junto a la cámara) no se dibujan.
            val vis = cards.mapNotNull { c -> p.toScreen(c.center, W, H)?.let { s -> Triple(c, s, s.third) } }
                .filter { it.third > 0.04 }.sortedByDescending { it.third }
            for ((c, s, depth) in vis) {
                val cardH = (p.f * 0.035 / depth * pxPerRef).toFloat().coerceIn(18f, H * 0.18f)
                val cardW = cardH * c.w / c.h
                val tl = Offset(s.first - cardW / 2, s.second - cardH / 2)
                bitmaps[c.id]?.let { bmp ->
                    drawImage(bmp, IntOffset.Zero, IntSize(c.w, c.h), IntOffset(tl.x.toInt(), tl.y.toInt()), IntSize(cardW.toInt(), cardH.toInt()))
                }
                drawRect(Color(0xFF5BD47A), tl, Size(cardW, cardH), style = Stroke(2f))
            }
        }
        if (showPoints) {
            val r = maxOf(2f, W / 260f)
            for (i in 0 until xyz.size / 3) {
                val s = p.toScreen(Vec3(xyz[3 * i].toDouble(), xyz[3 * i + 1].toDouble(), xyz[3 * i + 2].toDouble()), W, H) ?: continue
                if (s.first < 0 || s.first > W || s.second < 0 || s.second > H) continue
                drawCircle(qualityColor(quality.getOrElse(i) { 0f }), r, Offset(s.first, s.second))
            }
        }
    }
}
