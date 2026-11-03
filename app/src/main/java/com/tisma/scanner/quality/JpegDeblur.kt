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
    /** @param threads núcleos a usar (pocos mientras se graba, para no quitárselos a la cámara). */
    fun apply(file: File, dx: Double, dy: Double, quality: Int = 95,
              threads: Int = Runtime.getRuntime().availableProcessors()): Boolean {
        val len = kotlin.math.hypot(dx, dy)
        if (len > MotionDeblur.MAX_PX) return false
        val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return false
        try {
            val w = bmp.width; val h = bmp.height
            // Todos los píxeles a memoria, corrección en paralelo (todos los núcleos), de vuelta al bitmap.
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val out = MotionDeblur.deblurArgb(px, w, h, MotionDeblur.lineKernel(dx, dy), threads = threads)
            bmp.setPixels(out, 0, w, 0, 0, w, h)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            return tmp.renameTo(file)
        } finally {
            bmp.recycle()
        }
    }
}
