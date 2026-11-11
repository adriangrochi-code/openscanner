package com.tisma.scanner

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Un trabajo rápido (rescatar un escaneo chico al abrir la app) pide el servicio y lo suelta antes de que llegue a
 * onStartCommand. Con stopService en ese momento Android cierra la app (ForegroundServiceDidNotStartInTimeException,
 * visto en el emulador); el servicio tiene que llegar a primer plano y pararse solo.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessingServiceQuickJobTest {
    @Test fun aJobDoneBeforeTheServiceStartsDoesNotStopItEarly() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ProcessingService.start(app, "Retomando el escaneo…")
        ProcessingService.stop(app)
        assertNull("no stopService antes de startForeground", shadowOf(app).nextStoppedService)

        val started = shadowOf(app).nextStartedService
        val controller = Robolectric.buildService(ProcessingService::class.java, started ?: Intent()).create()
        controller.startCommand(0, 1)
        val service = controller.get()
        assertTrue("llegó a primer plano", shadowOf(service).lastForegroundNotification != null)
        assertTrue("y se paró solo: nadie lo necesita", shadowOf(service).isStoppedBySelf)
        controller.destroy()
        assertFalse(ProcessingService.running)
    }
}
