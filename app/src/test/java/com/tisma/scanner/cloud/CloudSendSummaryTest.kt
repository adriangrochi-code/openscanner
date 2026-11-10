package com.tisma.scanner.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale

/** Las medidas que acompañan a la pieza, como las prometen las guías: tamaño, área y, si está cerrada, volumen. */
@RunWith(RobolectricTestRunner::class)  // org.json es el de Android
class CloudSendSummaryTest {
    private fun summary(json: String): String {
        val old = Locale.getDefault()
        Locale.setDefault(Locale.ROOT)
        try {
            return CloudSend.summaryOf(JSONObject(json), File("no-existe.json"))
        } finally { Locale.setDefault(old) }
    }

    @Test fun sizeAreaAndVolumeWhenClosed() {
        assertEquals("40.20 × 30.20 × 20.30 mm · área 52.3 cm² · cerrada · volumen 24.6 cm³",
            summary("""{"dimensions_mm_xyz":[40.2,30.2,20.3],"area_mm2":5226.4,"volume_mm3":24646.9,"watertight":true}"""))
    }

    @Test fun anOpenPartSaysSoAndHasNoVolume() {
        assertEquals("40.20 × 30.20 × 20.30 mm · área 33.5 cm² · abierta",
            summary("""{"dimensions_mm_xyz":[40.2,30.2,20.3],"area_mm2":3348.5,"volume_mm3":null,"watertight":false}"""))
    }

    @Test fun withoutMeasuresNothing() {
        assertEquals("", summary("""{}"""))
    }
}
