package com.tisma.scanner.recon

import com.tisma.scanner.ins.Vec3
import com.tisma.scanner.session.ScanSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class ReconStateTest {
    private fun view(k: Double) = LivePointCloud.View(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(k, 2 * k, 3 * k), 280.0, 160.0, 120.0)

    @Test fun savedReconstructionLoadsBackIdentical() {
        val dir = Files.createTempDirectory("recon").toFile()
        val frs = listOf(
            LivePointCloud.Fragment(3, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f), floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f),
                intArrayOf(0, 1, 2), IntArray(4) { it * 1000 }, 2, 2, Vec3(0.0, 0.0, -1.0), view(0.1), 320, 240),
            LivePointCloud.Fragment(4, floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 0f), intArrayOf(), null, 0, 0, Vec3(1.0, 0.0, 0.0), null, 0, 0),
        )
        val shots = listOf(DepthShot(view(0.2), byteArrayOf(1, 2, 3, 4), listOf(Triple(1.0, 2.0, 0.25), Triple(3.0, 4.0, 0.3))))
        val f = java.io.File(dir, "recon_state.bin")
        ReconState.save(f, frs, shots)
        val (frs2, shots2) = ReconState.load(f)!!
        assertEquals(2, frs2.size)
        assertArrayEquals(frs[0].xyz, frs2[0].xyz, 0f); assertArrayEquals(frs[0].uv, frs2[0].uv, 0f)
        assertArrayEquals(frs[0].tris, frs2[0].tris); assertArrayEquals(frs[0].thumb, frs2[0].thumb)
        assertEquals(frs[0].view!!.center, frs2[0].view!!.center); assertEquals(320, frs2[0].gw)
        assertEquals(null, frs2[1].thumb); assertEquals(null, frs2[1].view)
        assertArrayEquals(shots[0].jpeg, shots2[0].jpeg); assertEquals(shots[0].samples, shots2[0].samples)
        assertEquals(shots[0].view.center, shots2[0].view.center)
    }

    @Test fun zipLeavesOutTheInternalResumeFiles() {
        val base = Files.createTempDirectory("scans").toFile()
        val dir = java.io.File(base, "scan_x").apply { mkdirs() }
        java.io.File(dir, "images/0").mkdirs()
        java.io.File(dir, "images/0/1.jpg").writeText("foto")
        java.io.File(dir, "frames.csv").writeText("a")
        for (n in listOf("recon_state.bin", "pendiente.json", "deblur_pendiente.csv", "algo.tmp")) java.io.File(dir, n).writeText("x")
        val names = ZipFile(ScanSession.zipDir(dir)).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.containsAll(listOf("scan_x/images/0/1.jpg", "scan_x/frames.csv")))
        assertTrue(names.none { it.endsWith("recon_state.bin") || it.endsWith("pendiente.json") || it.endsWith("deblur_pendiente.csv") || it.endsWith(".tmp") })
    }
}
