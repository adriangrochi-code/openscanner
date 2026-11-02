package com.tisma.scanner.camera

import com.tisma.scanner.ins.Quat
import com.tisma.scanner.ins.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class PreviewStabilizerTest {
    private fun q(x: Double, y: Double, z: Double) = Quat.fromRotationVector(Vec3(x, y, z))

    @Test fun compensatesHandTremorButFollowsIntentionalPan() {
        val st = PreviewStabilizer()
        val view = 1080 to 1440
        // Temblor de 8 Hz y ±0,3° alrededor del eje Y: la corrección debe oponerse al temblor.
        var corrOpposes = 0; var n = 0
        for (i in 0 until 300) {
            val tremor = Math.toRadians(0.3) * sin(2 * Math.PI * 8 * i / 30.0)
            val tr = st.update(q(0.0, tremor, 0.0), view.first, view.second, 72.0)
            if (i > 30 && abs(tremor) > Math.toRadians(0.1)) { n++; if (tr.dxPx * tremor < 0) corrOpposes++ }
        }
        assertTrue("la corrección se opone al temblor ($corrOpposes/$n)", corrOpposes > n * 0.9)

        // Barrido intencionado lento (10°/s): tras un rato la corrección queda acotada (lo sigue).
        val pan = PreviewStabilizer()
        var last = PreviewStabilizer.Transform(0f, 0f, 0f, 1f)
        for (i in 0 until 90) last = pan.update(q(0.0, Math.toRadians(10.0) * i / 30.0, 0.0), 1080, 1440, 72.0)
        assertTrue("corrección acotada al margen: ${last.dxPx}", abs(last.dxPx) <= (pan.margin - 1) / 2 * 1080 + 1)
    }

    @Test fun signsMatchCameraGeometry() {
        val st = PreviewStabilizer(alpha = 0.0)   // sin suavizado: referencia fija en el 1er fotograma
        st.update(Quat.IDENTITY, 1080, 1440, 72.0)
        val right = st.update(q(0.0, Math.toRadians(0.5), 0.0), 1080, 1440, 72.0)
        assertTrue("giro +Y -> corregir a la izquierda", right.dxPx < 0)
        val up = PreviewStabilizer(alpha = 0.0).apply { update(Quat.IDENTITY, 1080, 1440, 72.0) }
            .update(q(Math.toRadians(0.5), 0.0, 0.0), 1080, 1440, 72.0)
        assertTrue("giro +X -> corregir hacia arriba", up.dyPx < 0)
        assertEquals(1.10f, up.scale, 1e-6f)
    }

    /** Nunca se ve el borde: miles de correcciones aleatorias (desplazamiento + giro) siempre cubren. */
    @Test fun neverShowsTheImageEdge() {
        val r = kotlin.random.Random(1)
        repeat(5000) {
            val raw = PreviewStabilizer.Transform(r.nextFloat() * 400 - 200, r.nextFloat() * 400 - 200, r.nextFloat() * 20 - 10, 1.10f)
            val fit = PreviewStabilizer.fitToCover(raw, 1080, 1440)
            assertTrue("destapa el borde: $raw -> $fit", PreviewStabilizer.covers(fit, 1080, 1440))
        }
        // Una corrección pequeña no se toca.
        val small = PreviewStabilizer.Transform(10f, -8f, 0.5f, 1.10f)
        assertEquals(small, PreviewStabilizer.fitToCover(small, 1080, 1440))
        // El caso que antes fallaba: desplazamiento al límite + 5° de giro.
        assertTrue(!PreviewStabilizer.covers(PreviewStabilizer.Transform(54f, 72f, 5f, 1.10f), 1080, 1440))
    }

    @Test fun trackerOutputAlwaysCovers() {
        val st = PreviewStabilizer()
        for (i in 0 until 600) {
            val shake = Math.toRadians(1.5) * sin(2 * Math.PI * 9 * i / 30.0)
            val tr = st.update(q(shake, -shake * 0.7, shake * 2), 1080, 1440, 72.0)
            assertTrue("fotograma $i: $tr", PreviewStabilizer.covers(tr, 1080, 1440))
        }
    }

    /** Que no se note: sin saltos entre fotogramas aunque el temblor sea grande. */
    @Test fun noVisibleJumps() {
        val st = PreviewStabilizer()
        var prev: PreviewStabilizer.Transform? = null
        var maxJump = 0f
        for (i in 0 until 900) {
            val shake = Math.toRadians(2.0) * sin(2 * Math.PI * 8 * i / 30.0) + Math.toRadians(0.3) * i / 30.0
            val tr = st.update(q(shake, shake * 0.5, 0.0), 1080, 1440, 72.0)
            prev?.let { maxJump = maxOf(maxJump, kotlin.math.hypot(tr.dxPx - it.dxPx, tr.dyPx - it.dyPx)) }
            prev = tr
        }
        // Un temblor de ±2° a 8 Hz mueve la imagen ~±50 px por fotograma: la corrección debe
        // variar suave (sin saltos mayores que ese propio movimiento).
        assertTrue("salto máximo $maxJump px", maxJump < 80f)
    }
}
