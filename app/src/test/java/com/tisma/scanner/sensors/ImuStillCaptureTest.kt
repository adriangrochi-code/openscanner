package com.tisma.scanner.sensors

import androidx.test.core.app.ApplicationProvider
import com.tisma.scanner.ins.StrapdownIns
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * La prueba de quietud (60 s) y la calibración base (minutos) comparten la captura del IMU. Empezar una con la otra en
 * curso vaciaba el búfer y, al terminar, lo cerraba: la calibración base se quedaba sin muestras y decía «el móvil se
 * movió». La segunda no empieza.
 */
@RunWith(RobolectricTestRunner::class)
class ImuStillCaptureTest {
    @Test fun aSecondMeasurementDoesNotTakeTheFirstOnesCapture() {
        val imu = ImuRecorder(ApplicationProvider.getApplicationContext(), StrapdownIns())
        assertTrue(imu.startStillCapture())
        assertFalse("ya hay una en curso", imu.startStillCapture())
        imu.stopStillCapture()
        assertTrue("terminada, se puede medir otra vez", imu.startStillCapture())
    }
}
