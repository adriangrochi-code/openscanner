package com.tisma.scanner.ins

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/** Una fila por segundo de la calibración base: estado de la INS (y del GPS si lo hay). */
data class DriftRow(
    val tS: Double,
    val p: Vec3,
    val v: Vec3,
    /** Error de posición que estima el propio Kalman (1σ, m). */
    val std: Double,
    val stationary: Boolean,
    val yawDeg: Double,
    val gyroBias: Vec3,
    val accelBias: Vec3,
    /** Posición GPS en el marco INS (m) respecto al primer fix, y su precisión. */
    val gps: Vec3? = null,
    val gpsAcc: Double? = null,
) {
    fun csv(): String = buildString {
        append("%.1f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%.3f".format(java.util.Locale.US,
            tS, p.x, p.y, p.z, v.x, v.y, v.z, std, if (stationary) 1 else 0, yawDeg))
        append(",%.6f,%.6f,%.6f,%.5f,%.5f,%.5f".format(java.util.Locale.US,
            gyroBias.x, gyroBias.y, gyroBias.z, accelBias.x, accelBias.y, accelBias.z))
        append(if (gps != null) ",%.3f,%.3f,%.3f,%.1f".format(java.util.Locale.US, gps.x, gps.y, gps.z, gpsAcc ?: 0.0) else ",,,,")
    }

    companion object {
        const val CSV_HEADER = "t_s,px,py,pz,vx,vy,vz,std_m,quieto,rumbo_deg,gb_x,gb_y,gb_z,ab_x,ab_y,ab_z,gps_x,gps_y,gps_z,gps_acc_m"
    }
}

/** Resumen de una calibración base: deriva a 30 s, 1, 2, 5, 10 min, máximos y rumbo. */
object DriftLog {
    val CHECKPOINTS = listOf(30.0, 60.0, 120.0, 300.0, 600.0)

    private fun horiz(v: Vec3) = sqrt(v.x * v.x + v.y * v.y)

    private fun fmt(m: Double) = when {
        m < 0.01 -> "%.1f mm".format(m * 1000)
        m < 1.0 -> "%.1f cm".format(m * 100)
        else -> "%.2f m".format(m)
    }

    private fun wrap(d: Double): Double { var x = d % 360; if (x > 180) x -= 360; if (x < -180) x += 360; return x }

    fun lines(rows: List<DriftRow>): List<String> {
        if (rows.size < 2) return listOf("Calibración sin datos suficientes")
        val dur = rows.last().tS - rows.first().tS
        val p0 = rows.first().p
        val out = mutableListOf<String>()
        out += "Calibración base: %.0f s · %d muestras · %.0f %% del tiempo quieto".format(
            dur, rows.size, 100.0 * rows.count { it.stationary } / rows.size)
        val cps = CHECKPOINTS.filter { it <= dur + 0.5 }.mapNotNull { c ->
            rows.firstOrNull { it.tS - rows.first().tS >= c - 0.5 }?.let { c to it }
        }
        if (cps.isNotEmpty()) out += "Deriva INS (horizontal): " + cps.joinToString(" · ") { (c, r) ->
            "${if (c < 60) "${c.toInt()} s" else "${(c / 60).toInt()} min"} → ${fmt(horiz(r.p - p0))}"
        }
        val maxH = rows.maxOf { horiz(it.p - p0) }
        val maxV = rows.maxOf { kotlin.math.abs(it.p.z - p0.z) }
        out += "Máximo: horizontal ${fmt(maxH)} · vertical ${fmt(maxV)} · error estimado final ±${fmt(rows.last().std)}"
        val yaw = wrap(rows.last().yawDeg - rows.first().yawDeg)
        out += "Rumbo: %+.2f° en total (%.2f °/min)".format(yaw, yaw / dur * 60)
        val gb = rows.last().gyroBias; val ab = rows.last().accelBias
        out += "Sesgos finales: giróscopo %.3f °/s · acelerómetro %.3f m/s²".format(Math.toDegrees(gb.norm()), ab.norm())
        val withGps = rows.filter { it.gps != null }
        if (withGps.size >= 5) {
            val d = withGps.map { horiz(it.p - p0 - it.gps!!) }
            out += "INS vs GPS: media ${fmt(d.average())} · final ${fmt(d.last())} (GPS ±%.0f m)".format(withGps.last().gpsAcc ?: 0.0)
        }
        return out
    }

    fun json(rows: List<DriftRow>): JSONObject = JSONObject().apply {
        if (rows.isEmpty()) return@apply
        val p0 = rows.first().p
        put("duration_s", rows.last().tS - rows.first().tS)
        put("drift_horizontal_m", JSONObject().apply {
            for (c in CHECKPOINTS) rows.firstOrNull { it.tS - rows.first().tS >= c - 0.5 }?.let { put("${c.toInt()}s", horiz(it.p - p0)) }
        })
        put("max_horizontal_m", rows.maxOf { horiz(it.p - p0) })
        put("max_vertical_m", rows.maxOf { kotlin.math.abs(it.p.z - p0.z) })
        put("heading_change_deg", wrap(rows.last().yawDeg - rows.first().yawDeg))
        put("stationary_fraction", rows.count { it.stationary }.toDouble() / rows.size)
        put("final_std_m", rows.last().std)
        put("lines", JSONArray(lines(rows)))
    }
}
