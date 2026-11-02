package com.tisma.scanner.ins

/**
 * Decide cuándo disparar las cámaras usando la INS:
 *  - Dispara cuando el dispositivo ha girado `minAngleDeg` o se ha desplazado `minTranslationM`
 *    desde el último disparo (buena cobertura sin fotos redundantes).
 *  - Bloquea el disparo si la velocidad angular es alta (evita desenfoque de movimiento).
 *  - Fuerza un disparo cada `maxIntervalMs` aunque no haya movimiento.
 */
class KeyframeSelector(
    var minAngleDeg: Double = 8.0,
    var minTranslationM: Double = 0.03,
    var maxGyroRadS: Double = 0.6,
    var minIntervalMs: Long = 250,
    var maxIntervalMs: Long = 3000,
) {
    private var lastQ: Quat? = null
    private var lastP: Vec3? = null
    private var lastT = 0L

    @Synchronized fun reset() { lastQ = null; lastP = null; lastT = 0 }

    @Synchronized fun shouldTrigger(state: InsState, gyroRate: Double): Boolean {
        val t = state.timestampNs
        val elapsedMs = (t - lastT) / 1_000_000
        if (lastQ == null) return true
        if (elapsedMs < minIntervalMs) return false
        if (gyroRate > maxGyroRadS) return false
        val angle = Math.toDegrees(lastQ!!.angleTo(state.orientation))
        val trans = (state.position - lastP!!).norm()
        return angle >= minAngleDeg || trans >= minTranslationM || elapsedMs >= maxIntervalMs
    }

    @Synchronized fun markTriggered(state: InsState) {
        lastQ = state.orientation; lastP = state.position; lastT = state.timestampNs
    }
}
