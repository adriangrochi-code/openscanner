package com.tisma.scanner.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Dos escaneos seguidos: el primero espera la red (o al servidor) y se manda el segundo. Antes, con un solo nombre
 * para todos los envíos y KEEP, el segundo se descartaba sin aviso y la app se quedaba en «Conectando…».
 */
@RunWith(RobolectricTestRunner::class)
class CloudSendQueueTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var wm: WorkManager
    private val a = File(ctx.filesDir, "scan_a.zip").apply { writeText("a") }
    private val b = File(ctx.filesDir, "scan_b.zip").apply { writeText("b") }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        wm = WorkManager.getInstance(ctx)   // sin red: los envíos quedan en cola
    }

    private fun sends(zip: File) = wm.getWorkInfosByTag(CloudSendWorker.tagFor(zip)).get()

    @Test fun aSecondScanIsQueuedWhileTheFirstWaits() {
        CloudSendWorker.enqueue(ctx, a, "http://127.0.0.1:1")
        CloudSendWorker.enqueue(ctx, b, "http://127.0.0.1:1")
        assertEquals(WorkInfo.State.ENQUEUED, sends(a).single().state)
        assertEquals("el segundo escaneo también va", WorkInfo.State.ENQUEUED, sends(b).single().state)
        // La app muestra el de su escaneo; recién abierta, el último enviado.
        val all = wm.getWorkInfosByTag(CloudSendWorker.UNIQUE).get()
        assertEquals(sends(b).single().id, CloudSendWorker.shown(all, b, null)?.id)
        assertEquals(sends(a).single().id, CloudSendWorker.shown(all, a, null)?.id)
        val last = ctx.getSharedPreferences("tisma", Context.MODE_PRIVATE).getString(CloudSendWorker.KEY_LAST_SENT, null)
        assertEquals(sends(b).single().id, CloudSendWorker.shown(all, null, last)?.id)
    }

    @Test fun theSameScanTwiceIsOneSend() {
        CloudSendWorker.enqueue(ctx, a, "http://127.0.0.1:1")
        CloudSendWorker.enqueue(ctx, a, "http://127.0.0.1:1")
        assertEquals("otro envío gastaría otro escaneo del plan", 1, sends(a).size)
    }

    @Test fun eachSendHasItsOwnNotification() {
        assertNotEquals(CloudSendWorker.notificationId(a), CloudSendWorker.notificationId(b))
    }
}
