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
    }
}
