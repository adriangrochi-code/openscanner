package com.tisma.scanner.diag

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class DiagLogTest {
    @Test fun writesEventsAndPerSecondSummary() {
        val dir = Files.createTempDirectory("diag").toFile()
        DiagLog.start(dir)
        DiagLog.event("app", "Escaneo iniciado")
        repeat(30) { DiagLog.tick("pantalla"); DiagLog.tick("cam_frame") }
        Thread.sleep(1300)
        DiagLog.flush()
        val text = DiagLog.file!!.readText()
        println(text)
        assertTrue(text.contains("[app] Escaneo iniciado"))
        assertTrue("resumen por segundo", Regex("pantalla 30 fps").containsMatchIn(text))
        assertTrue(text.contains("cámara 30"))
    }
}
