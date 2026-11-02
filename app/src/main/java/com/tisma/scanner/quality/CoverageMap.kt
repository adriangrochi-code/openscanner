package com.tisma.scanner.quality

import com.tisma.scanner.ins.Quat
import com.tisma.scanner.ins.Vec3
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Mapa de cobertura sobre una cúpula alrededor de la pieza.
 *
 * Cada foto aporta la dirección *desde la pieza hacia la cámara* (lo contrario del eje óptico),
 * calculada con la orientación de la INS en el instante de la foto. Se asume que el usuario
 * apunta a la pieza, así que no hace falta la posición (que es lo que peor mide la INS):
 *  - Elevación: absoluta y precisa (la da la gravedad, ±0,5°).
 *  - Azimut: relativo a la primera foto; deriva 1-5°/min, de sobra para sectores de 15-30°.
 *
 * La cúpula se divide en anillos de elevación con distinto nº de sectores (menos cerca del polo).
 */
class CoverageMap(
    val rings: List<Ring> = DEFAULT_RINGS,
) {
    data class Ring(val elMinDeg: Double, val elMaxDeg: Double, val bins: Int) {
        val label get() = "${elMinDeg.toInt()}–${elMaxDeg.toInt()}°"
    }

    private val counts = rings.map { IntArray(it.bins) }
    private var azOrigin: Double? = null
    var below = 0; private set

    @Synchronized fun reset() { counts.forEach { it.fill(0) }; azOrigin = null; below = 0 }

    /** Origen de azimut (grados absolutos) o null si aún no hay fotos. */
    @Synchronized fun origin(): Double? = azOrigin

    /** Comparte el origen con otro mapa (otra pasada) para que "0°" signifique lo mismo. */
    @Synchronized fun adoptOrigin(o: Double) { if (azOrigin == null) azOrigin = o }

    /** Azimut relativo al punto de inicio, en [0, 360). */
    @Synchronized fun relativeAz(azDeg: Double): Double {
        val o = azOrigin ?: return 0.0
        return ((azDeg - o) % 360 + 360) % 360
    }

    /** Devuelve (anillo, sector) o null si está fuera de la cúpula (por debajo de la mesa). */
    @Synchronized fun binOf(relAzDeg: Double, elDeg: Double): Pair<Int, Int>? {
        val r = rings.indexOfFirst { elDeg >= it.elMinDeg && elDeg < it.elMaxDeg + if (it.elMaxDeg >= 90) 1e-9 else 0.0 }
        if (r < 0) return null
        val bins = rings[r].bins
        val b = ((relAzDeg / 360.0) * bins).toInt().coerceIn(0, bins - 1)
        return r to b
    }

    /** Registra una foto válida vista desde `dir`. Devuelve el sector rellenado (o null). */
    @Synchronized fun add(dir: Vec3): Pair<Int, Int>? {
        val (az, el) = azEl(dir)
        if (azOrigin == null) azOrigin = az - 360.0 / (2 * rings.first().bins) // centra el 1er sector
        val bin = binOf(relativeAz(az), el)
        if (bin == null) { below++; return null }
        counts[bin.first][bin.second]++
        return bin
    }

    @Synchronized fun snapshot(): List<IntArray> = counts.map { it.copyOf() }

    /** Fracción de sectores con al menos `minCount` fotos. */
    @Synchronized fun coverage(minCount: Int = 1): Double {
        val total = counts.sumOf { it.size }
        return counts.sumOf { c -> c.count { it >= minCount } }.toDouble() / total
    }

    @Synchronized fun ringCoverage(ring: Int, minCount: Int = 1): Double =
        counts[ring].count { it >= minCount }.toDouble() / counts[ring].size

    /** Consejos legibles sobre lo que falta, del más importante al menos. */
    @Synchronized fun hints(minCount: Int = 1): List<String> {
        if (azOrigin == null) return listOf("Empieza rodeando la pieza a media altura (~40°)")
        val out = mutableListOf<String>()
        for ((i, ring) in rings.withIndex()) {
            val c = counts[i]
            if (c.count { it >= minCount }.toDouble() / c.size >= 0.9) continue
            val gap = largestGap(c, minCount) ?: continue
            val step = 360.0 / c.size
            val from = (gap.first * step).toInt()
            val to = ((gap.first + gap.second) * step).toInt()
            out += if (gap.second == c.size) "Faltan fotos a ${ring.label} de altura (todo el anillo)"
            else "A ${ring.label} de altura: falta el sector ${from}°–${to}°"
        }
        if (below > 0 && out.isEmpty()) out += "Para la cara inferior, da la vuelta a la pieza y haz otro escaneo"
        return out
    }

    /** Hueco circular más largo de sectores vacíos: (inicio, longitud). */
    private fun largestGap(c: IntArray, minCount: Int): Pair<Int, Int>? {
        val n = c.size
        if (c.all { it < minCount }) return 0 to n
        var best: Pair<Int, Int>? = null
        for (start in 0 until n) {
            if (c[start] >= minCount || c[(start - 1 + n) % n] < minCount) continue
            var len = 0
            while (len < n && c[(start + len) % n] < minCount) len++
            if (best == null || len > best.second) best = start to len
        }
        return best
    }

    companion object {
        val DEFAULT_RINGS = listOf(
            Ring(10.0, 30.0, 24),   // vistas rasantes: cantos y laterales
            Ring(30.0, 50.0, 24),
            Ring(50.0, 70.0, 16),
            Ring(70.0, 90.0, 6),    // casquete superior
        )

        /**
         * Dirección pieza→cámara en el marco mundo (Z arriba).
         * Eje óptico en el marco del dispositivo Android: trasera = −Z (sale por la espalda),
         * frontal = +Z (sale por la pantalla).
         */
        fun viewDirection(q: Quat, backFacing: Boolean): Vec3 {
            val axisDevice = if (backFacing) Vec3(0.0, 0.0, -1.0) else Vec3(0.0, 0.0, 1.0)
            return -(q.rotate(axisDevice))
        }

        /** (azimut, elevación) en grados de un vector dirección. */
        fun azEl(dir: Vec3): Pair<Double, Double> {
            val d = dir.normalized()
            return Math.toDegrees(atan2(d.y, d.x)) to Math.toDegrees(asin(d.z.coerceIn(-1.0, 1.0)))
        }
    }
}
