package com.tisma.scanner.quality

import android.graphics.BitmapFactory

/** Decodifica una miniatura del JPEG (submuestreo en el decodificador: rápido) y la analiza. */
object JpegAnalyzer {
    fun analyze(jpeg: ByteArray, srcWidth: Int, minWidth: Int = 480): QualityReport? {
        var sample = 1
        while (srcWidth / (sample * 2) >= minWidth) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
            inSampleSize = sample
        }) ?: return null
        try {
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) {
                val c = px[i]
                // Luma BT.601 en enteros.
                px[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
            }
            return ImageQuality.analyzeGray(px, w, h)
        } finally {
            bmp.recycle()
        }
    }
}
