package com.tisma.scanner.ins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Valida el "velocímetro" de la cámara con imágenes sintéticas: una superficie con textura a
 * 25 cm y una cámara trasera montada a 90° (como el Honor) que se mueve y gira cantidades
 * conocidas entre dos fotogramas (1/30 s).
 */
class OpticalVelocityTest {
    private val w = 160; private val h = 120; private val f = 150.0
    private val depth = 0.25; private val dt = 1.0 / 30

    // Textura: suma de senos de varias escalas (8-50 mm de longitud de onda).
    private val waves = Random(3).let { r -> List(12) { doubleArrayOf(r.nextDouble(-1.0, 1.0) * 700, r.nextDouble(-1.0, 1.0) * 700, r.nextDouble(0.0, 6.28)) } }
    private fun tex(x: Double, y: Double) = 128 + waves.sumOf { 9 * sin(it[0] * x + it[1] * y + it[2]) }

    /** Rotación (cámara) como matriz 3×3 a partir de un vector de rotación pequeño. */
    private fun rot(r: Vec3): Quat = Quat.fromRotationVector(r)

    /** Imagen vista por una cámara con orientación `rc` y posición `t` (en ejes de la cámara 1). */
    private fun render(rc: Quat, t: Vec3): OpticalFlow.Image {
        val px = FloatArray(w * h)
        for (v in 0 until h) for (u in 0 until w) {
            val d = rc.rotate(Vec3((u - w / 2.0) / f, (v - h / 2.0) / f, 1.0))
            val s = (depth - t.z) / d.z
            px[v * w + u] = tex(t.x + s * d.x, t.y + s * d.y).toFloat()
        }
        return OpticalFlow.Image(w, h, px)
    }

    private fun check(rCam: Vec3, tCam: Vec3) {
        val (cx, cy, cz) = OpticalVelocity.cameraAxesInDevice(90)
        // Matriz cámara->dispositivo y giro equivalente del dispositivo.
        fun camToDev(v: Vec3) = cx * v.x + cy * v.y + cz * v.z
        val q0 = Quat.IDENTITY
        val q1 = rot(camToDev(rCam))
        val a = render(Quat.IDENTITY, Vec3.ZERO)
        val b = render(rot(rCam), tCam)
        val flow = OpticalFlow.track(a, b)
        val m = OpticalVelocity.estimate(flow, f, depth, dt, q0, q1, 90)
        assertNotNull("sin medida (puntos ${flow.tracked}, dispersión ${flow.spread})", m); m!!
        val vWorld = q0.rotate(camToDev(tCam)) * (1 / dt)
        val e1 = vWorld dot m.dirWorld1; val e2 = vWorld dot m.dirWorld2
        println("r=$rCam t=$tCam -> medido (%.4f, %.4f) real (%.4f, %.4f) m/s".format(m.v1, m.v2, e1, e2))
        val tol = 0.15 * maxOf(abs(e1), abs(e2)) + 0.01
        assertEquals(e1, m.v1, tol); assertEquals(e2, m.v2, tol)
    }

    @Test fun pureTranslation() = check(Vec3.ZERO, Vec3(0.003, -0.002, 0.0))          // 9 cm/s, 6 cm/s

    @Test fun pureRotationGivesNoVelocity() = check(Vec3(0.01, -0.008, 0.003), Vec3.ZERO) // ~1,5°/fotograma

    @Test fun translationWhileRotating() = check(Vec3(-0.006, 0.009, 0.0), Vec3(-0.002, 0.003, 0.0005))

    @Test fun flowAloneRecoversShift() {
        val a = render(Quat.IDENTITY, Vec3.ZERO)
        val b = render(Quat.IDENTITY, Vec3(0.0025, 0.0, 0.0))   // 1,5 px a la izquierda
        val r = OpticalFlow.track(a, b)
        assertTrue("puntos ${r.tracked}", r.tracked >= 20)
        assertEquals(-0.0025 * f / depth, r.dx, 0.1)
        assertEquals(0.0, r.dy, 0.1)
    }
}
