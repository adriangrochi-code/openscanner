package com.tisma.scanner.camera

import com.tisma.scanner.ins.Quat
import com.tisma.scanner.ins.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Estabilización electrónica (por software) de la VISTA PREVIA con el giróscopo.
 *
 * Para cada fotograma se toma la orientación del móvil en el instante exacto de ese fotograma
 * (timestamp del sensor, misma base que la INS) y se compara con una orientación suavizada
 * (lo que el usuario "quiere" apuntar). La diferencia es temblor: se compensa desplazando y
 * girando la imagen, que se amplía un `margin` para que no se vean los bordes.
 *
 * Sólo afecta a lo que se ve en pantalla: las fotos se guardan completas y sin recortar, porque
 * un recorte distinto en cada foto cambiaría la calibración de la cámara foto a foto.
 */
class PreviewStabilizer(
    /**
     * Fracción de seguimiento por fotograma (0,2 ≈ 0,15 s a 30 fps): quita el temblor de la mano
     * (8-10 Hz, ~88 %) pero sigue enseguida los movimientos intencionados, así la estabilización
     * no se nota (ni retraso, ni imagen "flotando").
     */
    private val alpha: Double = 0.2,
    /** Ampliación para tener margen de recorte (1,10 = 10 %: apenas se nota el zoom). */
    val margin: Double = 1.10,
) {
    data class Transform(val dxPx: Float, val dyPx: Float, val rotDeg: Float, val scale: Float)

    private var smooth: Quat? = null
    private var followBoost = alpha

    fun reset() { smooth = null; followBoost = alpha }

    /**
     * @param frameQ orientación del dispositivo (dispositivo→mundo) en el instante del fotograma
     * @param viewW, viewH tamaño de la vista en píxeles (vertical: la altura es el lado largo del sensor)
     * @param hfovDeg campo de visión a lo largo del lado largo del sensor
     */
    fun update(frameQ: Quat, viewW: Int, viewH: Int, hfovDeg: Double): Transform {
        val s = smooth?.slerp(frameQ, followBoost) ?: frameQ
        smooth = s
        // Giro del dispositivo desde la orientación suavizada a la actual, en ejes del dispositivo.
        val d = (s.conj() * frameQ).normalized()
        val rv = rotationVector(d)
        // En vertical, la altura de la vista corresponde al lado largo del sensor (hfov).
        val f = viewH / (2 * tan(Math.toRadians(hfovDeg) / 2))
        // Cámara trasera mirando a -Z del dispositivo:
        //  giro +Y -> la escena se desplaza a la derecha  => corregir hacia la izquierda;
        //  giro +X -> la escena baja en pantalla           => corregir hacia arriba;
        //  giro +Z (antihorario) -> la escena gira horario => girar la imagen antihorario.
        val maxX = ((margin - 1) / 2 * viewW).toFloat()
        val maxY = ((margin - 1) / 2 * viewH).toFloat()
        val raw = Transform((-rv.y * f).toFloat(), (-rv.x * f).toFloat(), (-Math.toDegrees(rv.z)).toFloat(), margin.toFloat())
        // Nunca enseñar el borde de la imagen: se reduce la corrección lo justo para que la
        // vista quede cubierta (antes sólo se limitaba el desplazamiento y el giro destapaba esquinas).
        val fitted = fitToCover(raw, viewW, viewH)
        val dx = fitted.dxPx; val dy = fitted.dyPx; val rot = fitted.rotDeg
        // Si la corrección se acerca al límite, seguir más deprisa el movimiento real, de forma
        // gradual (antes se saltaba de golpe y se notaba).
        val used = maxOf(abs(raw.dxPx) / maxX, abs(raw.dyPx) / maxY).toDouble()
        followBoost = alpha + (1 - alpha) * ((used - 0.6) / 0.8).coerceIn(0.0, 1.0)
        return Transform(dx, dy, rot, margin.toFloat())
    }

    companion object {
        /**
         * ¿La imagen, escalada `scale`, girada `rotDeg` y desplazada (dx, dy) alrededor del centro,
         * sigue cubriendo toda la vista w×h? Se comprueba que las 4 esquinas de la vista, llevadas
         * al sistema de la imagen original, caen dentro de ella.
         */
        fun covers(t: Transform, w: Int, h: Int): Boolean {
            val cx = w / 2.0; val cy = h / 2.0
            val a = Math.toRadians(-t.rotDeg.toDouble())
            val c = kotlin.math.cos(a); val s = kotlin.math.sin(a)
            for ((px, py) in listOf(0.0 to 0.0, w.toDouble() to 0.0, 0.0 to h.toDouble(), w.toDouble() to h.toDouble())) {
                // Inverso de: escalar en el centro, girar en el centro, desplazar.
                val x = px - t.dxPx - cx; val y = py - t.dyPx - cy
                val xr = (c * x - s * y) / t.scale + cx
                val yr = (s * x + c * y) / t.scale + cy
                if (xr < -1e-3 || xr > w + 1e-3 || yr < -1e-3 || yr > h + 1e-3) return false
            }
            return true
        }

        /** La mayor fracción (0..1) de la corrección que mantiene la vista cubierta. */
        fun fitToCover(t: Transform, w: Int, h: Int): Transform {
            if (covers(t, w, h)) return t
            var lo = 0.0; var hi = 1.0
            repeat(20) {
                val m = (lo + hi) / 2
                if (covers(Transform((t.dxPx * m).toFloat(), (t.dyPx * m).toFloat(), (t.rotDeg * m).toFloat(), t.scale), w, h)) lo = m else hi = m
            }
            return Transform((t.dxPx * lo).toFloat(), (t.dyPx * lo).toFloat(), (t.rotDeg * lo).toFloat(), t.scale)
        }
    }

    private fun rotationVector(q: Quat): Vec3 {
        val qq = if (q.w < 0) Quat(-q.w, -q.x, -q.y, -q.z) else q
        val angle = 2 * acos(qq.w.coerceIn(-1.0, 1.0))
        val sinHalf = sin(angle / 2)
        if (sinHalf < 1e-9) return Vec3(qq.x * 2, qq.y * 2, qq.z * 2)
        return Vec3(qq.x, qq.y, qq.z) * (angle / sinHalf)
    }
}
