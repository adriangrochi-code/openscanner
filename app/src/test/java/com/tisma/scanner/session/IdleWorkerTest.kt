package com.tisma.scanner.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

class IdleWorkerTest {
    @Test fun runsMostUsefulWorkFirst() {
        val w = IdleWorker(background = false)
        val order = Collections.synchronizedList(ArrayList<String>())
        val gate = CountDownLatch(1)
        w.submit(IdleWorker.Priority.HIGH) { gate.await() }          // ocupa el hilo mientras se encola
        w.submit(IdleWorker.Priority.LOW) { order += "nitidez1" }
        w.submit(IdleWorker.Priority.NORMAL) { order += "malla" }
        w.submit(IdleWorker.Priority.LOW) { order += "nitidez2" }
        w.submit(IdleWorker.Priority.HIGH) { order += "ia" }
        gate.countDown()
        Thread.sleep(300)
        assertEquals(listOf("ia", "malla", "nitidez1", "nitidez2"), order)
        w.shutdown()
    }

    @Test fun closingWaitsForTheRunningJobAndDropsTheRest() {
        val w = IdleWorker(background = false)
        val done = Collections.synchronizedList(ArrayList<String>())
        val started = CountDownLatch(1)
        w.submit(IdleWorker.Priority.LOW) { started.countDown(); Thread.sleep(200); done += "en marcha" }
        w.submit(IdleWorker.Priority.LOW) { done += "encolada" }
        started.await()
        w.closeAndDrain()
        assertEquals(listOf("en marcha"), done)                     // la que estaba en marcha terminó; la otra no corrió
        w.submit(IdleWorker.Priority.HIGH) { done += "tras cerrar" }
        Thread.sleep(100)
        assertTrue("tras cerrar" !in done)
        w.open(); w.submit(IdleWorker.Priority.HIGH) { done += "reabierto" }
        Thread.sleep(100)
        assertTrue("reabierto" in done)
        w.shutdown()
    }
}
