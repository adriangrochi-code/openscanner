package com.tisma.scanner.ins

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interpolación entre muestras: acelerómetro al instante del giróscopo, integración de la
 * aceleración por trapecio y posición cúbica (Hermite) entre estados. Movimiento de la mano que arranca desde
 * el reposo, a(t) = C·sin ωt, con los dos sensores desfasados medio periodo, como en el móvil.
 */
class InterpolationTest {
    private val g = MahonyFilter.GRAVITY
    private val c = 1.5                   // m/s²
    private val w = 2 * PI * 1.0          // 1 Hz
    private fun x(t: Double) = c / w * (t - sin(w * t) / w)
    private fun ax(t: Double) = c * sin(w * t)

    @Test fun trapezoidIntegrationIsMoreAccurateThanEuler() {
        val rate = 100; val dt = 1.0 / rate
        val kf = TranslationKalman()
        var pE = 0.0; var vE = 0.0
        var errE = 0.0; var errT = 0.0
        for (i in 0..(2 * rate)) {
            val t = i * dt
            val a = ax(t)
            if (i > 0) {
                pE += vE * dt + a * dt * dt / 2; vE += a * dt          // Euler (lo de antes)
                kf.predict(Quat.IDENTITY, Vec3(a, 0.0, g), dt, 10.0)
                errE = maxOf(errE, kotlin.math.abs(pE - x(t))); errT = maxOf(errT, kotlin.math.abs(kf.p.x - x(t)))
            } else kf.predict(Quat.IDENTITY, Vec3(a, 0.0, g), 0.0, 10.0)
        }
        println("mano 1 Hz ±1,5 m/s² a 100 Hz, 2 s: Euler %.2f mm · trapecio %.3f mm".format(errE * 1000, errT * 1000))
        assertTrue("trapecio %.5f vs Euler %.5f".format(errT, errE), errT < errE / 10)
    }

    /**
     * Girar el móvil 90° en 1 s con los sensores desfasados medio periodo: tomar "la última"
     * muestra del acelerómetro empareja la actitud con una gravedad de hace 5 ms y ese desfase
     * se integra como aceleración. Interpolando al instante del giróscopo desaparece.
     */
    @Test fun accelInterpolatedToGyroInstantAvoidsGravityLeak() {
        fun run(stale: Boolean): Double {
            val ins = StrapdownIns(zuptEnabled = false)
            val rate = 100; val dt = 1.0 / rate; val rot = PI / 2
            var maxP = 0.0
            for (i in 0 until 3 * rate) {
                val tA = i * dt; val tG = tA + dt / 2
                fun angle(t: Double) = if (t < 1.0) 0.0 else if (t < 2.0) rot * (t - 1.0) else rot
                val wx = (angle(tG) - angle(tG - dt)) / dt      // giro medio del intervalo, como el sensor
                val q = Quat.fromRotationVector(Vec3(angle(tA), 0.0, 0.0))
                val acc = q.conj().rotate(Vec3(0.0, 0.0, g))
                // "stale": la muestra llega con el instante del giro, como si fuera la última (antes).
                ins.onAccel(((if (stale) tG - 1e-6 else tA) * 1e9).toLong(), acc)
                ins.onGyro((tG * 1e9).toLong(), Vec3(wx, 0.0, 0.0))
                ins.latest()?.let { maxP = maxOf(maxP, it.position.norm()) }
            }
            return maxP
        }
        val before = run(stale = true); val after = run(stale = false)
        println("giro de 90° con sensores desfasados: antes %.1f mm · ahora %.2f mm".format(before * 1000, after * 1000))
        assertTrue("ahora %.4f vs antes %.4f".format(after, before), after < before / 3)
    }

    @Test fun cubicInterpolationFollowsTheCurveBetweenSamples() {
        // Muestras espaciadas (20 Hz: p.ej. el sensor se salta eventos) y fotos entre medio.
        val states = ArrayList<Pair<Double, InsState>>()
        val ins = StrapdownIns(zuptEnabled = false)
        val rate = 400; val dt = 1.0 / rate
        for (i in 0 until rate) {
            val tA = i * dt; val tG = tA + dt / 2
            ins.onAccel((tA * 1e9).toLong(), Vec3(ax(tA), 0.0, g))
            ins.onGyro((tG * 1e9).toLong(), Vec3.ZERO)
        }
        // Historia completa a 400 Hz: la referencia "verdad" de la propia INS.
        val dense = (0 until 380).map { k -> 0.05 + k * 0.0025 }.associateWith { t -> ins.stateAt((t * 1e9).toLong())!! }
        // Nueva INS que sólo guarda un estado cada 50 ms.
        val sparse = StrapdownIns(zuptEnabled = false)
        var next = 0.0
        for (i in 0 until rate) {
            val tA = i * dt; val tG = tA + dt / 2
            sparse.onAccel((tA * 1e9).toLong(), Vec3(ax(tA), 0.0, g))
            sparse.onGyro((tG * 1e9).toLong(), Vec3.ZERO)
            val s = sparse.latest() ?: continue
            if (s.timestampNs * 1e-9 >= next) { states += s.timestampNs * 1e-9 to s; next += 0.05 }
        }
        var linErr = 0.0; var cubErr = 0.0
        for (k in 0 until states.size - 1) {
            val (t0, a) = states[k]; val (t1, b) = states[k + 1]
            val tm = (t0 + t1) / 2; val ref = dense.minByOrNull { kotlin.math.abs(it.key - tm) } ?: continue
            if (kotlin.math.abs(ref.key - tm) > 0.002) continue
            val f = (ref.key - t0) / (t1 - t0); val h = t1 - t0
            val lin = a.position.x + (b.position.x - a.position.x) * f
            val f2 = f * f; val f3 = f2 * f
            val cub = a.position.x * (2 * f3 - 3 * f2 + 1) + a.velocity.x * h * (f3 - 2 * f2 + f) +
                b.position.x * (-2 * f3 + 3 * f2) + b.velocity.x * h * (f3 - f2)
            linErr = maxOf(linErr, kotlin.math.abs(lin - ref.value.position.x))
            cubErr = maxOf(cubErr, kotlin.math.abs(cub - ref.value.position.x))
        }
        println("entre estados cada 50 ms: recta %.2f mm · cúbica %.3f mm".format(linErr * 1000, cubErr * 1000))
        assertTrue("cúbica %.5f vs recta %.5f".format(cubErr, linErr), cubErr < linErr / 5)
    }

    @Test fun stateAtUsesTheCubicCurve() {
        // stateAt entre dos estados separados 100 ms (hueco en el giróscopo) sigue la curva.
        val ins = StrapdownIns(zuptEnabled = false)
        val rate = 400; val dt = 1.0 / rate
        for (i in 0 until rate) {
            val tA = i * dt; val tG = tA + dt / 2
            ins.onAccel((tA * 1e9).toLong(), Vec3(ax(tA), 0.0, g))
            val inGap = tG in 0.40..0.50
            if (!inGap) ins.onGyro((tG * 1e9).toLong(), Vec3.ZERO)
        }
        val before = ins.stateAt((0.3999 * 1e9).toLong())!!
        val mid = ins.stateAt((0.45 * 1e9).toLong())!!
        val after = ins.stateAt((0.5012 * 1e9).toLong())!!
        // Verdad relativa: la INS sigue x(t) − x(origen) salvo un desfase constante.
        val off = before.position.x - x(0.3999)
        val err = kotlin.math.abs(mid.position.x - (x(0.45) + off))
        val lin = kotlin.math.abs((before.position.x + after.position.x) / 2 - (x(0.45) + off))
        println("hueco de 100 ms: recta %.2f mm · cúbica %.2f mm".format(lin * 1000, err * 1000))
        assertTrue("cúbica %.5f vs recta %.5f".format(err, lin), err < lin / 2)
    }
}
