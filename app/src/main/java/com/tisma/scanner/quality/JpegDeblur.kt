package com.tisma.scanner.quality

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Quita el desenfoque de movimiento de un JPEG en el móvil: corrige la LUMINANCIA (donde está el
 * detalle que usa la fotogrametría) y suma la misma corrección a R, G y B, así el color no cambia.
 * Sobrescribe el archivo. Devuelve false si no se pudo.
 */
object JpegDeblur {
    fun apply(file: File, dx: Double, dy: Double, quality: Int = 95): Boolean {
        val len = kotlin.math.hypot(dx, dy)
        if (len < MotionDeblur.MIN_PX || len > MotionDeblur.MAX_PX) return false
        val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return false
        try {
            val w = bmp.width; val h = bmp.height
            val kernel = MotionDeblur.lineKernel(dx, dy)
            val row = IntArray(w)
            MotionDeblur.deconvolveBands(
                lum = { y0, rows ->
                    val out = FloatArray(w * rows)
                    for (r in 0 until rows) {
                        bmp.getPixels(row, 0, w, 0, y0 + r, w, 1)
                        for (x in 0 until w) {
                            val c = row[x]
                            out[r * w + x] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                        }
                    }
                    out
                },
                w = w, h = h, kernel = kernel,
                out = { y0, rows, band ->
                    for (r in 0 until rows) {
                        bmp.getPixels(row, 0, w, 0, y0 + r, w, 1)
                        for (x in 0 until w) {
                            val c = row[x]
                            val R = (c shr 16) and 0xFF; val G = (c shr 8) and 0xFF; val B = c and 0xFF
                            val d = band[r * w + x] - (0.299f * R + 0.587f * G + 0.114f * B)
                            row[x] = (0xFF shl 24) or ((R + d).toInt().coerceIn(0, 255) shl 16) or
                                ((G + d).toInt().coerceIn(0, 255) shl 8) or (B + d).toInt().coerceIn(0, 255)
                        }
                        bmp.setPixels(row, 0, w, 0, y0 + r, w, 1)
                    }
                },
            )
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            return tmp.renameTo(file)
        } finally {
            bmp.recycle()
        }
    }
}
