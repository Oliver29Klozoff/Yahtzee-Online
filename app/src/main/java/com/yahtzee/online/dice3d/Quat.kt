package com.yahtzee.online.dice3d

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {

    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z
    )

    fun normalized(): Quat {
        val len = sqrt(x * x + y * y + z * z + w * w)
        return if (len < 1e-6f) IDENTITY else Quat(x / len, y / len, z / len, w / len)
    }

    fun rotate(v: Vec3): Vec3 {
        val qv = Vec3(x, y, z)
        val uv = qv.cross(v)
        val uuv = qv.cross(uv)
        return v + (uv * (2f * w)) + (uuv * 2f)
    }

    fun toMatrix4(): FloatArray {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return floatArrayOf(
            1f - 2f * (yy + zz), 2f * (xy + wz), 2f * (xz - wy), 0f,
            2f * (xy - wz), 1f - 2f * (xx + zz), 2f * (yz + wx), 0f,
            2f * (xz + wy), 2f * (yz - wx), 1f - 2f * (xx + yy), 0f,
            0f, 0f, 0f, 1f
        )
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        fun fromAxisAngle(axis: Vec3, angleRad: Float): Quat {
            val half = angleRad * 0.5f
            val s = sin(half)
            val n = axis.normalized()
            return Quat(n.x * s, n.y * s, n.z * s, cos(half))
        }

        fun fromAngularVelocity(omega: Vec3, dt: Float): Quat {
            val angle = omega.length() * dt
            return if (angle < 1e-6f) IDENTITY else fromAxisAngle(omega.normalized(), angle)
        }

        /** Spherical linear interpolation from [a] to [b], t in [0,1]. */
        fun slerp(a: Quat, b: Quat, t: Float): Quat {
            var bx = b.x; var by = b.y; var bz = b.z; var bw = b.w
            var dot = a.x * bx + a.y * by + a.z * bz + a.w * bw
            if (dot < 0f) {
                bx = -bx; by = -by; bz = -bz; bw = -bw
                dot = -dot
            }
            if (dot > 0.9995f) {
                return Quat(
                    a.x + (bx - a.x) * t,
                    a.y + (by - a.y) * t,
                    a.z + (bz - a.z) * t,
                    a.w + (bw - a.w) * t
                ).normalized()
            }
            val theta0 = kotlin.math.acos(dot.coerceIn(-1f, 1f))
            val theta = theta0 * t
            val sinTheta0 = sin(theta0)
            val sinTheta = sin(theta)
            val s0 = cos(theta) - dot * sinTheta / sinTheta0
            val s1 = sinTheta / sinTheta0
            return Quat(
                a.x * s0 + bx * s1,
                a.y * s0 + by * s1,
                a.z * s0 + bz * s1,
                a.w * s0 + bw * s1
            ).normalized()
        }
    }
}
