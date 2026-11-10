package com.tisma.scanner.session

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/** Un escaneo cortado mientras se grababa: los archivos quedan hasta la última línea completa. */
@RunWith(RobolectricTestRunner::class)  // org.json es el de Android
class RepairTruncatedTest {
    @Test fun halfLinesAreDropped() {
        val dir = Files.createTempDirectory("escaneo").toFile()
        File(dir, "frames.csv").writeText("frame,trigger,camera_id,t_ns\n1,1,0,100\n2,1,0,200\n3,1,0")      // a medias
        File(dir, "quality.csv").writeText("camera_id,t_ns,blurry\n0,100,0\n0,200\n")                       // campos de menos
        File(dir, "capture_meta.jsonl").writeText("{\"trigger\":1}\n{\"trigger\":2}\n{\"trig")               // JSON a medias
        File(dir, "imu.csv").writeText("t,ax\n1,0.1\n")                                                     // entera
        val removed = ScanSession.repairTruncated(dir)
        assertEquals("frame,trigger,camera_id,t_ns\n1,1,0,100\n2,1,0,200\n", File(dir, "frames.csv").readText())
        assertEquals("camera_id,t_ns,blurry\n0,100,0\n", File(dir, "quality.csv").readText())
        assertEquals("{\"trigger\":1}\n{\"trigger\":2}\n", File(dir, "capture_meta.jsonl").readText())
        assertEquals("t,ax\n1,0.1\n", File(dir, "imu.csv").readText())
        assertEquals(3, removed)
        dir.deleteRecursively()
    }
}
