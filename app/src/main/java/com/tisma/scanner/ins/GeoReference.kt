package com.tisma.scanner.ins

import kotlin.math.cos

/**
 * Proyección local ENU (Este-Norte-Arriba) alrededor del primer fix GNSS.
 * Aproximación plana: error < 1 cm para distancias < 1 km.
 */
class GeoReference(val lat0: Double, val lon0: Double, val alt0: Double) {
    private val mPerDegLat = 111_132.92 - 559.82 * cos(2 * Math.toRadians(lat0))
    private val mPerDegLon = 111_412.84 * cos(Math.toRadians(lat0))

    /** Devuelve (este, norte, arriba) en metros. */
    fun toEnu(lat: Double, lon: Double, alt: Double) =
        Vec3((lon - lon0) * mPerDegLon, (lat - lat0) * mPerDegLat, alt - alt0)
}
