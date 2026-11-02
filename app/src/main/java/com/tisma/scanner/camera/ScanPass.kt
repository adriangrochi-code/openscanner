package com.tisma.scanner.camera

import android.hardware.camera2.CameraMetadata

/**
 * Pasadas de escaneo. Cada una tiene su propia cobertura, ajustes y cámaras:
 *  - LEJOS: la pieza entera en el encuadre (≈2-3× su tamaño). Da la geometría global y une todo.
 *    Ultra gran angular + principal; HDR permitido.
 *  - CERCA: detalle (≈10-20 cm). La profundidad de campo es de pocos mm, así que cada disparo
 *    es un apilado de enfoques. Principal + macro; sin HDR (serían demasiadas fotos).
 */
enum class ScanPass(val label: String, val minAngleDeg: Double, val hdr: Boolean, val focusStack: Boolean) {
    FAR("Lejos (global)", 10.0, hdr = true, focusStack = false),
    CLOSE("Cerca (detalle)", 6.0, hdr = false, focusStack = true);

    /**
     * Cámaras a usar en esta pasada cuando hay que rotar entre ellas (no se pueden abrir a la vez).
     * Heurística: la macro es la trasera de menor resolución (< 3 MP); la ultra gran angular,
     * la de mayor campo de visión.
     */
    fun pickCameras(cameras: Collection<CameraInfo>): List<String> {
        val back = cameras.filter { it.facing == CameraMetadata.LENS_FACING_BACK }
        if (back.size <= 1) return back.map { it.id }
        val macro = back.filter { (it.jpegSizes.firstOrNull()?.let { s -> s.width.toLong() * s.height } ?: Long.MAX_VALUE) < 3_000_000 }
        val widest = back.filter { it !in macro }.maxByOrNull { it.hfovDeg ?: 0.0 }
        val chosen = when (this) {
            FAR -> back.filter { it !in macro }
            CLOSE -> back.filter { it != widest || back.size == 1 }
        }
        return chosen.ifEmpty { back }.map { it.id }
    }
}
