package com.tisma.scanner.camera

/**
 * Pasadas de escaneo. Cada una tiene su propia cobertura, ajustes y cámaras:
 *  - LEJOS: la pieza entera en el encuadre (≈2-3× su tamaño). Da la geometría global y une todo.
 *    Vista: principal; dispara también la ultra gran angular (o la tele si no hay). HDR permitido.
 *  - CERCA: detalle (≈4-20 cm). Vista: sólo la macro; dispara también la principal, que apila
 *    enfoques porque la profundidad de campo es de pocos mm. Sin HDR (serían demasiadas fotos).
 * Ver PassRigPlanner.
 */
enum class ScanPass(val label: String, val minAngleDeg: Double, val hdr: Boolean, val focusStack: Boolean) {
    FAR("Lejos (global)", 10.0, hdr = true, focusStack = false),
    CLOSE("Cerca (detalle)", 6.0, hdr = false, focusStack = true);
}
