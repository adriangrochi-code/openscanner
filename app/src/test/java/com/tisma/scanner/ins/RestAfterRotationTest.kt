package com.tisma.scanner.ins

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.random.Random

/**
 * Reproduce lo visto en el Honor: tras girar el móvil, apoyarlo en la mesa hacía que la
 * posición se disparase (30 m) estando QUIETO. Un error de escala del giróscopo del 1 %
 * (típico: 0,5-2 %) deja ~0,9° de inclinación errónea tras un giro de 90°; esa inclinación
 * "filtra" gravedad (~0,15 m/s²) y el detector de reposo la tomaba por movimiento.
 */
class RestAfterRotationTest {
    private val g = MahonyFilter.GRAVITY

    private fun run(gyroScale: Double, noise: Double, magDisturbance: Vec3? = null): Pair<Double, Boolean> {
        val r = Random(11)
        val ins = StrapdownIns()
        var q = Quat.IDENTITY
        val rate = 400; val dt = 1.0 / rate
        var t = 0L
        var posAtRestStart = 0.0
        var maxAtRest = 0.0
        val plan = listOf(Vec3.ZERO to 2.0, Vec3(PI / 2, 0.0, 0.0) to 1.0, Vec3(0.0, PI / 2, 0.0) to 1.0, Vec3.ZERO to 10.0)
        for ((k, step) in plan.withIndex()) {
            val (turn, secs) = step
            val w = turn * (1.0 / secs)
            for (i in 0 until (secs * rate).toInt()) {
                q = (q * Quat.fromRotationVector(w * dt)).normalized()
                fun n() = Vec3(r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0)) * (noise * 1.73)
                val accel = q.conj().rotate(Vec3(0.0, 0.0, g)) + Vec3(0.0, 0.0, -0.045) + n()
                ins.onAccel(t, accel)
                if (magDisturbance != null && i % 4 == 0) {
                    // Campo terrestre (inclinación magnética ~-40°, como en Uruguay) + perturbación fija
                    // del entorno (mesa, PC, hierro del edificio) en coordenadas del MUNDO, que gira
                    // respecto al móvil: el filtro no la puede "aprender" como sesgo.
                    val earth = Vec3(0.0, 23.0, -19.0)
                    ins.onMag(t, q.conj().rotate(earth + magDisturbance) + n() * 10.0)
                }
                ins.onGyro(t + 1_000_000, w * gyroScale + n() * 0.003)   // giróscopo con error de escala
                t += (dt * 1e9).toLong()
                val p = ins.latest()?.position?.norm() ?: 0.0
                if (k == plan.lastIndex) {
                    if (i == 0) posAtRestStart = p
                    maxAtRest = maxOf(maxAtRest, kotlin.math.abs(p - posAtRestStart))
                }
            }
        }
        return maxAtRest to ins.latest()!!.stationary
    }

    @Test fun staysPutOnTheTableAfterRotating() {
        val (drift, still) = run(gyroScale = 1.01, noise = 0.024)
        assertTrue("apoyado en la mesa se movió %.3f m".format(drift), drift < 0.05)
        assertTrue("debe reconocer el reposo", still)
    }

    @Test fun staysPutWithIndoorMagneticDisturbance() {
        val (drift, still) = run(gyroScale = 1.01, noise = 0.024, magDisturbance = Vec3(8.0, -5.0, 12.0))
        println("deriva en reposo con brújula perturbada: %.4f m".format(drift))
        assertTrue("con el magnetómetro perturbado se movió %.3f m".format(drift), drift < 0.05)
        assertTrue("debe reconocer el reposo", still)
    }

    /**
     * La brújula sigue corrigiendo el RUMBO (lleva el eje X del mundo al norte magnético) pero ya
     * no toca la INCLINACIÓN, que sólo decide la gravedad.
     */
    @Test fun compassCorrectsHeadingButNeverTilt() {
        val ins = StrapdownIns(); var t = 0L
        val earth = Vec3(0.0, 23.0, -19.0)       // norte a +90° del rumbo inicial
        var maxTilt = 0.0
        for (i in 0 until 400 * 120) {
            ins.onAccel(t, Vec3(0.0, 0.0, g))
            if (i % 4 == 0) ins.onMag(t, earth)
            ins.onGyro(t + 1_000_000, Vec3.ZERO)
            t += 2_500_000
            ins.latest()?.let {
                val up = it.orientation.rotate(Vec3(0.0, 0.0, 1.0))
                maxTilt = maxOf(maxTilt, Math.toDegrees(kotlin.math.acos(up.z.coerceIn(-1.0, 1.0))))
            }
        }
        val m = ins.latest()!!.orientation.rotate(earth)
        val headingErr = Math.toDegrees(kotlin.math.atan2(m.y, m.x))
        println("tras 2 min: error de rumbo %.2f°, inclinación máx. %.3f°".format(headingErr, maxTilt))
        assertTrue("el rumbo debe ir al norte: $headingErr°", kotlin.math.abs(headingErr) < 5)
        assertTrue("la brújula no debe inclinar: $maxTilt°", maxTilt < 0.1)
    }
}
