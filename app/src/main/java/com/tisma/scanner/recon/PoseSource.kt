package com.tisma.scanner.recon

/**
 * De dónde sale la posición de la cámara para la reconstrucción en vivo.
 *
 * - INS: posición de la INS corregida por la odometría visual-inercial (MSCKF). Es la buena si
 *   la INS "ve" el desplazamiento.
 * - ORBIT: la cámara mira a la pieza a la distancia del enfoque, centro = −distancia·eje óptico.
 *   Sólo necesita la orientación (giróscopo, muy precisa) y funciona al rodear la pieza.
 *
 * Si al rodear despacio la INS cree que el móvil está quieto (ZUPT), su posición apenas se mueve:
 * todos los rayos salen del mismo punto, no se puede triangular nada y la vista previa queda vacía.
 * Se detecta así: tras unas cuantas vistas con puntos bien seguidos y giro suficiente, casi no hay
 * puntos triangulados. Entonces se pasa a ORBIT (una sola vez, para todo el escaneo).
 */
class PoseSource(
    private val minKeyframes: Int = 20,
    private val minPoints: Int = 40,
    private val minTurnDeg: Double = 20.0,
) {
    enum class Mode { INS, ORBIT }

    @Volatile var mode = Mode.INS; private set
    private var decided = false

    fun reset() { mode = Mode.INS; decided = false }

    /**
     * Tras cada vista: nº de vistas, puntos triangulados y giro acumulado desde el inicio.
     * Devuelve true si hay que cambiar a ORBIT ahora (y reiniciar la reconstrucción).
     */
    fun update(keyframes: Int, points: Int, turnedDeg: Double): Boolean {
        if (decided || mode != Mode.INS) return false
        if (keyframes < minKeyframes || turnedDeg < minTurnDeg) return false
        decided = true
        if (points >= minPoints) return false
        mode = Mode.ORBIT
        return true
    }
}
