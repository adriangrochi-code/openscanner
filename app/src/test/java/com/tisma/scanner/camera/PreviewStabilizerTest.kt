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
        assertEquals(1.15f, up.scale, 1e-6f)
    }
}
