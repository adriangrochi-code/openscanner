package com.tisma.scanner.ins

import kotlin.math.sqrt

/** Vector 3D inmutable y ligero. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 { val n = norm(); return if (n > 1e-12) this * (1.0 / n) else this }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        fun of(v: FloatArray) = Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
    }
}

/** Cuaternión unitario (w, x, y, z). Rota vectores del marco del dispositivo al marco mundo. */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(q: Quat) = Quat(
        w * q.w - x * q.x - y * q.y - z * q.z,
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
    )

    fun conj() = Quat(w, -x, -y, -z)

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n > 1e-12) Quat(w / n, x / n, y / n, z / n) else IDENTITY
    }

    /** Rota v (body -> world). */
    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = (u cross v) * 2.0
        return v + t * w + (u cross t)
    }

    /** Interpolación esférica. */
    fun slerp(o: Quat, t: Double): Quat {
        var d = w * o.w + x * o.x + y * o.y + z * o.z
        var b = o
        if (d < 0) { d = -d; b = Quat(-o.w, -o.x, -o.y, -o.z) }
        if (d > 0.9995) {
            return Quat(w + t * (b.w - w), x + t * (b.x - x), y + t * (b.y - y), z + t * (b.z - z)).normalized()
        }
        val theta0 = kotlin.math.acos(d)
        val theta = theta0 * t
        val s0 = kotlin.math.cos(theta) - d * kotlin.math.sin(theta) / kotlin.math.sin(theta0)
        val s1 = kotlin.math.sin(theta) / kotlin.math.sin(theta0)
        return Quat(s0 * w + s1 * b.w, s0 * x + s1 * b.x, s0 * y + s1 * b.y, s0 * z + s1 * b.z)
    }

    /** Ángulo (rad) entre dos orientaciones. */
    fun angleTo(o: Quat): Double {
        val d = kotlin.math.abs(w * o.w + x * o.x + y * o.y + z * o.z).coerceAtMost(1.0)
        return 2.0 * kotlin.math.acos(d)
    }

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        /** Cuaternión que rota el vector unitario `from` a `to`. */
        fun fromTwoVectors(from: Vec3, to: Vec3): Quat {
            val a = from.normalized(); val b = to.normalized()
            val d = a dot b
            if (d < -0.999999) {
                var axis = Vec3(1.0, 0.0, 0.0) cross a
                if (axis.norm() < 1e-6) axis = Vec3(0.0, 1.0, 0.0) cross a
                axis = axis.normalized()
                return Quat(0.0, axis.x, axis.y, axis.z)
            }
            val c = a cross b
            return Quat(1.0 + d, c.x, c.y, c.z).normalized()
        }

        /** Integra una velocidad angular (rad/s, marco body) durante dt. */
        fun fromRotationVector(r: Vec3): Quat {
            val angle = r.norm()
            if (angle < 1e-12) return Quat(1.0, r.x * 0.5, r.y * 0.5, r.z * 0.5).normalized()
            val s = kotlin.math.sin(angle / 2) / angle
            return Quat(kotlin.math.cos(angle / 2), r.x * s, r.y * s, r.z * s)
        }
    }
}
