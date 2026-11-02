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
    /** Fracción de corrección del suavizado por fotograma (0,08 ≈ 0,4 s a 30 fps). */
    private val alpha: Double = 0.08,
    /** Ampliación para tener margen de recorte (1,15 = 15 %). */
    val margin: Double = 1.15,
) {
    data class Transform(val dxPx: Float, val dyPx: Float, val rotDeg: Float, val scale: Float)

    private var smooth: Quat? = null

    fun reset() { smooth = null }

    /**
     * @param frameQ orientación del dispositivo (dispositivo→mundo) en el instante del fotograma
     * @param viewW, viewH tamaño de la vista en píxeles (vertical: la altura es el lado largo del sensor)
     * @param hfovDeg campo de visión a lo largo del lado largo del sensor
     */
    fun update(frameQ: Quat, viewW: Int, viewH: Int, hfovDeg: Double): Transform {
        val s = smooth?.slerp(frameQ, alpha) ?: frameQ
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
        val dx = (-rv.y * f).toFloat().coerceIn(-maxX, maxX)
        val dy = (-rv.x * f).toFloat().coerceIn(-maxY, maxY)
        val rot = (-Math.toDegrees(rv.z)).toFloat().coerceIn(-5f, 5f)
        // Si el temblor supera el margen, el suavizado alcanza antes al movimiento real.
        if (abs(rv.x * f) > maxY * 1.5 || abs(rv.y * f) > maxX * 1.5) smooth = smooth!!.slerp(frameQ, 0.5)
        return Transform(dx, dy, rot, margin.toFloat())
    }

    private fun rotationVector(q: Quat): Vec3 {
        val qq = if (q.w < 0) Quat(-q.w, -q.x, -q.y, -q.z) else q
        val angle = 2 * acos(qq.w.coerceIn(-1.0, 1.0))
        val sinHalf = sin(angle / 2)
        if (sinHalf < 1e-9) return Vec3(qq.x * 2, qq.y * 2, qq.z * 2)
        return Vec3(qq.x, qq.y, qq.z) * (angle / sinHalf)
    }
}
