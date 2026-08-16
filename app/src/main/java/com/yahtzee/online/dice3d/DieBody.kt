package com.yahtzee.online.dice3d

import kotlin.random.Random

/**
 * A single die's physical state. Uses a sphere collision proxy for die-die and die-wall
 * collision (cheap, visually fine once dice settle). Face-up is read from `orientation`
 * once at rest.
 */
class DieBody(
    var position: Vec3,
    var velocity: Vec3 = Vec3.ZERO,
    var orientation: Quat = Quat.IDENTITY,
    var angularVelocity: Vec3 = Vec3.ZERO
) {
    var atRest = false
    private var restTimer = 0f

    companion object {
        const val HALF_SIZE = 0.5f
        const val COLLIDE_RADIUS = 0.62f

        private val FACE_NORMALS = mapOf(
            1 to Vec3(0f, 1f, 0f),
            6 to Vec3(0f, -1f, 0f),
            2 to Vec3(1f, 0f, 0f),
            5 to Vec3(-1f, 0f, 0f),
            3 to Vec3(0f, 0f, 1f),
            4 to Vec3(0f, 0f, -1f)
        )
    }

    fun throwWith(direction: Vec3, speed: Float, spin: Float, random: Random = Random.Default) {
        velocity = direction.normalized() * speed
        angularVelocity = Vec3(
            (random.nextFloat() - 0.5f) * spin,
            (random.nextFloat() - 0.5f) * spin,
            (random.nextFloat() - 0.5f) * spin
        )
        orientation = Quat.fromAxisAngle(
            Vec3(random.nextFloat(), random.nextFloat(), random.nextFloat()),
            random.nextFloat() * 6.28f
        )
        atRest = false
        restTimer = 0f
    }

    fun faceValueUp(): Int {
        var best = 1
        var bestDot = -2f
        for ((value, normal) in FACE_NORMALS) {
            val worldNormal = orientation.rotate(normal)
            val d = worldNormal.dot(Vec3.UP)
            if (d > bestDot) {
                bestDot = d
                best = value
            }
        }
        return best
    }

    fun snapToUpright(targetValue: Int) {
        val local = FACE_NORMALS[targetValue] ?: Vec3.UP
        val currentWorld = orientation.rotate(local).normalized()
        val target = Vec3.UP
        val axis = currentWorld.cross(target)
        val dot = currentWorld.dot(target).coerceIn(-1f, 1f)
        val angle = kotlin.math.acos(dot)
        val correction = if (axis.length() < 1e-4f) Quat.IDENTITY else Quat.fromAxisAngle(axis, angle)
        orientation = (correction * orientation).normalized()
        angularVelocity = Vec3.ZERO
    }

    fun markRestIfSettled(dt: Float): Boolean {
        val slow = velocity.length() < 0.05f && angularVelocity.length() < 0.15f
        if (slow) {
            restTimer += dt
        } else {
            restTimer = 0f
        }
        atRest = restTimer > 0.35f
        return atRest
    }
}
