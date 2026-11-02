package com.tisma.scanner.camera

import kotlin.math.ceil

/**
 * Cálculos ópticos para decidir cómo fotografiar a cada distancia.
 *
 * Trabajar en dioptrías (1/m) simplifica todo: la profundidad de campo total en dioptrías
 * es casi constante para un objetivo dado, ΔD ≈ 2·N·c / f², sea cual sea la distancia.
 * Ejemplo (principal del móvil, f ≈ 5,6 mm, f/1,75, salida 12 MP -> c = 2 px ≈ 3,7 µm):
 * ΔD ≈ 0,41 D, es decir
 *  - a 10 cm (10 D): ~4 mm enfocados   → una pieza de 3 cm necesita ~9 planos
 *  - a 30 cm (3,3 D): ~3,7 cm enfocados → basta 1 plano
 */
object Optics {

    /** Profundidad de campo total en dioptrías. */
    fun dofDiopters(focalMm: Double, fNumber: Double, cocMm: Double): Double =
        2.0 * fNumber * cocMm / (focalMm * focalMm) * 1000.0

    /** Círculo de confusión ≈ `pixels` píxeles del sensor a la resolución de salida. */
    fun cocMm(sensorWidthMm: Double, imageWidthPx: Int, pixels: Double = 2.0): Double =
        sensorWidthMm / imageWidthPx * pixels

    /** Focal en píxeles de la imagen de salida. */
    fun fxPixels(focalMm: Double, sensorWidthMm: Double, imageWidthPx: Int): Double =
        focalMm / sensorWidthMm * imageWidthPx

    /** Tamaño en la pieza de un píxel (mm/px) a una distancia dada. */
    fun gsdMm(distanceM: Double, fxPx: Double): Double = distanceM * 1000.0 / fxPx

    /** Distancia (m) a partir del enfoque en dioptrías; null si es infinito o desconocido. */
    fun distanceM(diopters: Float): Double? = if (diopters > 0.05f) 1.0 / diopters else null

    /**
     * Planos de enfoque (dioptrías) para tener nítida toda la profundidad de la pieza alrededor
     * del enfoque actual. Los planos se solapan un 20 % para no dejar franjas borrosas.
     *
     * @param centerD enfoque actual (dioptrías) = distancia a la pieza
     * @param partDepthM profundidad de la pieza vista desde la cámara
     * @param maxSteps límite de fotos por apilado (memoria/tiempo)
     */
    fun focusStack(centerD: Double, partDepthM: Double, dofD: Double, minFocusD: Double, maxSteps: Int = 9): List<Float> {
        // No se puede enfocar más cerca que el mínimo del objetivo.
        val c = minOf(centerD, minFocusD)
        if (c <= 0.05 || dofD <= 0) return listOf(c.toFloat())
        val d = 1.0 / c
        val nearD = minOf(1.0 / maxOf(d - partDepthM / 2, 0.01), minFocusD)
        val farD = minOf(1.0 / (d + partDepthM / 2), nearD)
        val span = (nearD - farD).coerceAtLeast(0.0)
        // Un plano centrado cubre ±ΔD/2: basta si la pieza cabe (con margen) en la profundidad de campo.
        if (span <= dofD * 0.9) return listOf(c.toFloat())
        // Planos en los extremos y separación ≤ 0,8·ΔD para que se solapen (sin franjas borrosas).
        val steps = (ceil(span / (dofD * 0.8)).toInt() + 1).coerceIn(2, maxSteps)
        return (0 until steps).map { i -> (nearD + (farD - nearD) * i / (steps - 1)).toFloat() }
    }
}
