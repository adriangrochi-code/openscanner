package com.tisma.scanner.ui

import com.tisma.scanner.quality.CoverageMap

/** Una indicación grande en pantalla durante la grabación. */
data class Instruction(val icon: String, val text: String, val tone: Tone) {
    enum class Tone { INFO, OK, WARN, DONE }
}

/**
 * Qué decirle al usuario ahora, en lenguaje llano y UNA cosa cada vez (como una app de escaneo).
 * Orden de prioridad: ir demasiado rápido > mirar desde muy abajo > girar > cambiar de altura > listo.
 *
 * Sentido de giro: el azimut crece cuando la cámara se desplaza hacia SU derecha alrededor de la
 * pieza (cámara mirando al centro, Z arriba), así que turn = +1 es "muévete a la derecha".
 */
fun captureInstruction(rings: List<CoverageMap.Ring>, guide: CoverageMap.Guidance?, frames: Int, tooFast: Boolean): Instruction {
    if (tooFast) return Instruction("‖", "Más despacio", Instruction.Tone.WARN)
    if (guide == null || frames == 0) return Instruction("↻", "Rodea la pieza despacio", Instruction.Tone.INFO)
    val ring = guide.ring ?: return Instruction("↑", "Sube el móvil: mira la pieza desde arriba", Instruction.Tone.WARN)
    return when {
        guide.turn > 0 -> Instruction("→", "Sigue hacia la derecha", Instruction.Tone.OK)
        guide.turn < 0 -> Instruction("←", "Sigue hacia la izquierda", Instruction.Tone.OK)
        guide.gapStartDeg != null -> Instruction("↻", "Bien, sigue así", Instruction.Tone.OK)
        guide.nextRing != null && guide.nextRing > ring -> Instruction("↑", "Vuelta completa · sube el móvil y da otra", Instruction.Tone.INFO)
        guide.nextRing != null -> Instruction("↓", "Vuelta completa · baja el móvil y da otra", Instruction.Tone.INFO)
        else -> Instruction("✓", "¡Listo! Toca detener", Instruction.Tone.DONE)
    }
}

/** Nombre corto de cada altura para la barra de progreso. */
fun ringName(i: Int, rings: List<CoverageMap.Ring>): String = when {
    rings[i].elMaxDeg >= 90 -> "Arriba"
    i == 0 -> "Baja"
    i == 1 -> "Media"
    else -> "Alta"
}
