package com.tisma.scanner.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ThumbnailTest {
    /** Una caja de 40 × 30 × 20 mm: 12 triángulos. */
    private fun box(): FloatArray {
        val (w, d, h) = Triple(40f, 30f, 20f)
        val v = arrayOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(w, 0f, 0f), floatArrayOf(w, d, 0f), floatArrayOf(0f, d, 0f),
            floatArrayOf(0f, 0f, h), floatArrayOf(w, 0f, h), floatArrayOf(w, d, h), floatArrayOf(0f, d, h))
        val faces = listOf(0 to 1 to 2, 0 to 2 to 3, 4 to 6 to 5, 4 to 7 to 6, 0 to 5 to 1, 0 to 4 to 5,
            1 to 6 to 2, 1 to 5 to 6, 2 to 7 to 3, 2 to 6 to 7, 3 to 4 to 0, 3 to 7 to 4)
        return faces.flatMap { (ab, c) -> listOf(ab.first, ab.second, c).flatMap { v[it].toList() } }.toFloatArray()
    }

    private fun binaryStl(tris: FloatArray, file: File) {
        val n = tris.size / 9
        val b = ByteBuffer.allocate(84 + 50 * n).order(ByteOrder.LITTLE_ENDIAN)
        b.position(80); b.putInt(n)
        for (t in 0 until n) { b.putFloat(0f); b.putFloat(0f); b.putFloat(0f); for (k in 0 until 9) b.putFloat(tris[t * 9 + k]); b.putShort(0) }
        file.writeBytes(b.array())
    }

    @Test fun readsBinaryAndAsciiStl() {
        val tris = box()
        val bin = File.createTempFile("pieza", ".stl").also { binaryStl(tris, it) }
        assertArrayEquals(tris, Thumbnail.readStl(bin), 0f)
        val ascii = File.createTempFile("pieza", ".stl").apply {
            writeText("solid pieza\n" + (0 until tris.size / 9).joinToString("") { t ->
                "facet normal 0 0 0\nouter loop\n" + (0 until 3).joinToString("") { k ->
                    "vertex ${tris[t * 9 + k * 3]} ${tris[t * 9 + k * 3 + 1]} ${tris[t * 9 + k * 3 + 2]}\n" } + "endloop\nendfacet\n" } + "endsolid pieza\n")
        }
        assertArrayEquals(tris, Thumbnail.readStl(ascii), 0f)
    }

    @Test fun drawsThePieceCentredWithShadedFaces() {
        val px = Thumbnail.render(box(), 128)
        assertEquals(128 * 128, px.size)
        val piece = px.count { it != Thumbnail.BACKGROUND }
        assertTrue("la pieza ocupa buena parte de la miniatura: $piece", piece in 3000..12000)
        assertTrue("tres caras visibles, tres tonos", px.filter { it != Thumbnail.BACKGROUND }.distinct().size >= 3)
        assertEquals(Thumbnail.BACKGROUND, px[0])  // esquina vacía
        assertTrue(px[64 * 128 + 64] != Thumbnail.BACKGROUND)  // centro con pieza
    }
}
