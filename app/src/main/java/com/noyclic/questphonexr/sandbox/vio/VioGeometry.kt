package com.noyclic.questphonexr.sandbox.vio

import kotlin.math.*

/** Small immutable vector types kept free of Android/OpenCV dependencies for deterministic tests. */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(scale: Double) = Vec2(x * scale, y * scale)
    fun length() = hypot(x, y)
    fun isFinite() = x.isFinite() && y.isFinite()
}

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
    operator fun div(scale: Double) = Vec3(x / scale, y / scale, z / scale)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3) = Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun lengthSquared() = dot(this)
    fun length() = sqrt(lengthSquared())
    fun normalized(epsilon: Double = 1e-12): Vec3 = if (length() <= epsilon) ZERO else this / length()
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UNIT_X = Vec3(1.0, 0.0, 0.0)
        val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        val UNIT_Z = Vec3(0.0, 0.0, 1.0)
    }
}

/** Quaternion uses Hamilton multiplication and maps local vectors into the reference frame. */
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(other: Quaternion) = Quaternion(
        w * other.w - x * other.x - y * other.y - z * other.z,
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w
    )

    fun conjugate() = Quaternion(w, -x, -y, -z)
    fun normSquared() = w * w + x * x + y * y + z * z
    fun normalized(): Quaternion {
        val norm = sqrt(normSquared())
        return if (!norm.isFinite() || norm < 1e-12) IDENTITY
        else Quaternion(w / norm, x / norm, y / norm, z / norm)
    }

    fun rotate(vector: Vec3): Vec3 {
        val q = normalized()
        val p = Quaternion(0.0, vector.x, vector.y, vector.z)
        val result = q * p * q.conjugate()
        return Vec3(result.x, result.y, result.z)
    }

    fun angularDistance(other: Quaternion): Double {
        val a = normalized()
        val b = other.normalized()
        val dot = abs(a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1.0, 1.0)
        return 2.0 * acos(dot)
    }

    fun slerp(target: Quaternion, amount: Double): Quaternion {
        val t = amount.coerceIn(0.0, 1.0)
        val a = normalized()
        var b = target.normalized()
        var dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
        if (dot < 0.0) {
            b = Quaternion(-b.w, -b.x, -b.y, -b.z)
            dot = -dot
        }
        if (dot > 0.9995) {
            return Quaternion(
                a.w + t * (b.w - a.w),
                a.x + t * (b.x - a.x),
                a.y + t * (b.y - a.y),
                a.z + t * (b.z - a.z)
            ).normalized()
        }
        val theta = acos(dot.coerceIn(-1.0, 1.0))
        val sinTheta = sin(theta)
        val left = sin((1.0 - t) * theta) / sinTheta
        val right = sin(t * theta) / sinTheta
        return Quaternion(
            a.w * left + b.w * right,
            a.x * left + b.x * right,
            a.y * left + b.y * right,
            a.z * left + b.z * right
        ).normalized()
    }

    companion object {
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

        fun fromAxisAngle(axis: Vec3, angleRadians: Double): Quaternion {
            if (!angleRadians.isFinite()) return IDENTITY
            val half = angleRadians * 0.5
            val direction = axis.normalized()
            val s = sin(half)
            return Quaternion(cos(half), direction.x * s, direction.y * s, direction.z * s).normalized()
        }

        fun fromAngularVelocity(omegaRadiansPerSecond: Vec3, deltaSeconds: Double): Quaternion {
            if (!omegaRadiansPerSecond.isFinite() || !deltaSeconds.isFinite() || deltaSeconds <= 0.0) {
                return IDENTITY
            }
            val angle = omegaRadiansPerSecond.length() * deltaSeconds
            if (angle < 1e-12) return IDENTITY
            return fromAxisAngle(omegaRadiansPerSecond.normalized(), angle)
        }

        /** Android rotation-vector convention: x/y/z are the vector part, w is optional. */
        fun fromAndroidRotationVector(values: FloatArray): Quaternion? {
            if (values.size < 3) return null
            val vx = values[0].toDouble()
            val vy = values[1].toDouble()
            val vz = values[2].toDouble()
            if (!vx.isFinite() || !vy.isFinite() || !vz.isFinite()) return null
            val vectorNorm = vx * vx + vy * vy + vz * vz
            val scalar = if (values.size >= 4) values[3].toDouble()
                else sqrt((1.0 - vectorNorm).coerceAtLeast(0.0))
            if (!scalar.isFinite()) return null
            return Quaternion(scalar, vx, vy, vz).normalized()
        }
    }
}

/** Translation is explicitly tagged with its scale source; visual-only estimates are not metres. */
enum class TranslationScale { UNKNOWN, RELATIVE_ONLY, METRIC }

data class VioPose(
    val timestampNanos: Long,
    val orientation: Quaternion,
    val position: Vec3,
    val translationScale: TranslationScale,
    val trackingQuality: Double,
    val isValid: Boolean,
    val diagnostic: String
) {
    fun sanitized(): VioPose = copy(
        orientation = orientation.normalized(),
        position = if (position.isFinite()) position else Vec3.ZERO,
        trackingQuality = trackingQuality.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
    )
}
