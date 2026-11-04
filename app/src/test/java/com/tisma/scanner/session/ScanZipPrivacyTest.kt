package com.tisma.scanner.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

/** El zip de un escaneo (el que se manda a Tisma Cloud o se comparte) no lleva la posición absoluta. */
class ScanZipPrivacyTest {
    @Test fun theZipHasNoAbsolutePosition() {
        val root = kotlin.io.path.createTempDirectory("scan_zip").toFile()
        try {
            val dir = java.io.File(root, "scan_1").apply { mkdirs() }
            java.io.File(dir, "session.json").writeText("{\"device\":{\"model\":\"x\"},\"cameras\":[]}")
            java.io.File(dir, "frames.csv").writeText("frame,file,px,py,pz\n0,images/0/1.jpg,0.01,0.02,0.03\n")
            java.io.File(dir, "gps.csv").writeText("t,provider,lat,lon,alt,acc\n1,gps,-34.9,-56.2,30,5\n")
            java.io.File(dir, "images/0").mkdirs()
            java.io.File(dir, "images/0/1.jpg").writeBytes(byteArrayOf(-1, -40, -1, -39))
            val names = ZipFile(ScanSession.zipDir(dir)).use { z -> z.entries().toList().map { it.name } }
            assertTrue(names.none { it.endsWith("gps.csv") })
            assertEquals(setOf("scan_1/session.json", "scan_1/frames.csv", "scan_1/images/0/1.jpg"), names.toSet())
        } finally {
            root.deleteRecursively()
        }
    }
}
