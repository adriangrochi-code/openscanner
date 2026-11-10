package com.tisma.scanner.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cada envío lleva su zip en una etiqueta: el resultado de un escaneo anterior no se muestra como el del actual
 * (antes, tras un escaneo nuevo, «Tu pieza» ofrecía la pieza, «Imprimir» y «Publicar» del anterior).
 */
class CloudSendWorkerTagTest {
    private val first = File("/scans/scan_1.zip")
    private val second = File("/scans/scan_2.zip")

    @Test fun aResultBelongsOnlyToItsScan() {
        val tags = setOf("com.tisma.scanner.cloud.CloudSendWorker", CloudSendWorker.tagFor(first))
        assertTrue(CloudSendWorker.isFor(tags, first))
        assertFalse("el del escaneo anterior no es el de éste", CloudSendWorker.isFor(tags, second))
    }

    @Test fun withNoScanOpenTheLastSendIsShown() {
        // La app recién abierta todavía no tiene escaneo: se muestra el último envío (su pieza para compartir).
        assertTrue(CloudSendWorker.isFor(setOf(CloudSendWorker.tagFor(first)), null))
        assertTrue(CloudSendWorker.isFor(emptySet(), null))
        // Uno sin etiqueta (versión anterior) no se toma por el del escaneo abierto.
        assertFalse(CloudSendWorker.isFor(emptySet(), second))
    }
}
