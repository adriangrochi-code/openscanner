package com.tisma.scanner.ins

/**
 * Los sesgos guardados por la prueba de quietud («cal»: giróscopo xyz, acelerómetro xyz), como punto de partida de la INS.
 *
 * Con la calibración de 6 caras, el sesgo del acelerómetro ya está corregido en las muestras: el de la prueba de
 * quietud no se suma encima (lo que quede lo afina el Kalman). Una prueba de quietud hecha ANTES de la de 6 caras
 * guardó el sesgo crudo; al reabrir la app se volvía a cargar y el sesgo quedaba corregido dos veces.
 */
object BiasPriors {
    data class Priors(val gyro: Vec3, val accel: Vec3)

    fun parse(saved: String?, accelCalibrated: Boolean): Priors? {
        val v = saved?.split(",")?.mapNotNull { it.toDoubleOrNull() } ?: return null
        if (v.size != 6) return null
        return Priors(Vec3(v[0], v[1], v[2]), if (accelCalibrated) Vec3.ZERO else Vec3(v[3], v[4], v[5]))
    }
}
