package com.tisma.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El servicio en primer plano sigue mientras algún trabajo lo necesite (procesado, subida a la nube). */
class ProcessingServiceHoldersTest {
    @Test fun endingTheUploadKeepsTheProcessingProtected() {
        val h = ProcessingService.Holders()
        h.add(ProcessingService.PROCESSING)
        h.add(ProcessingService.CLOUD)
        assertFalse(h.started())
        assertFalse("el procesado sigue: no se para", h.remove(ProcessingService.CLOUD))
        assertTrue("ya nadie lo necesita: se para", h.remove(ProcessingService.PROCESSING))
    }

    @Test fun startingTwiceNeedsOneStop() {
        // stopScan y processScan lo piden los dos con el mismo nombre; processScan lo suelta una vez.
        val h = ProcessingService.Holders()
        h.add(ProcessingService.PROCESSING)
        h.add(ProcessingService.PROCESSING)
        h.started()
        assertTrue(h.remove(ProcessingService.PROCESSING))
        assertTrue(h.isEmpty())
    }

    @Test fun aStopBeforeTheServiceStartedLeavesItToTheService() {
        val h = ProcessingService.Holders()
        h.add(ProcessingService.PROCESSING)
        assertFalse("aún no está en primer plano: stopService tiraría la app", h.remove(ProcessingService.PROCESSING))
        assertTrue("al llegar a primer plano, nadie lo necesita: se para solo", h.started())
    }
}
