package com.yahtzee.online.dice3d

import kotlin.math.abs

/**
 * Simple fixed-step rigid body simulation for N dice on a bounded table.
 * Not a general-purpose physics engine: tuned specifically for dice-in-a-tray feel.
 */
/**
 * Table size is limited by the camera, not by taste: it sits at a fixed height and angle with a
 * 45° vertical field of view, so the far corners leave the frame if the surface grows past what
 * the narrowest dice view (the lobby's, roughly 1.4:1) can show. These bounds are the largest
 * that keep every die fully visible there without pulling the camera back, which would have
 * shrunk the dice on screen.
 */
class DicePhysicsWorld(
    val tableHalfWidth: Float = 2.75f,
    val tableHalfDepth: Float = 2f,
    val groundY: Float = 0f
) {
    val dice = mutableListOf<DieBody>()

    companion object {
        private const val GRAVITY = -9.8f
        private const val RESTITUTION = 0.4f
        private const val FRICTION = 0.78f
        private const val ANGULAR_DAMPING = 0.985f
        private const val LINEAR_DAMPING = 0.998f
    }

    fun step(dt: Float) {
        for (die in dice) {
            if (die.atRest) continue
            integrate(die, dt)
            resolveGroundCollision(die)
            resolveWallCollisions(die)
        }
        resolveDieDieCollisions()
        // Separating overlapping dice can nudge a resting one past a wall, and resting dice skip
        // the wall pass above, so the bounds are re-imposed on everything afterwards.
        clampToBounds()
        for (die in dice) {
            if (!die.atRest) die.markRestIfSettled(dt)
        }
    }

    private fun integrate(die: DieBody, dt: Float) {
        die.velocity = die.velocity + Vec3(0f, GRAVITY * dt, 0f)
        die.velocity = die.velocity * LINEAR_DAMPING
        die.position = die.position + die.velocity * dt

        if (die.isRigged()) {
            die.updateRig(dt)
        } else {
            die.angularVelocity = die.angularVelocity * ANGULAR_DAMPING
            val spin = Quat.fromAngularVelocity(die.angularVelocity, dt)
            die.orientation = (spin * die.orientation).normalized()
        }
    }

    private fun resolveGroundCollision(die: DieBody) {
        val floor = groundY + DieBody.HALF_SIZE
        if (die.position.y < floor) {
            die.position = Vec3(die.position.x, floor, die.position.z)
            if (die.velocity.y < 0f) {
                die.velocity = Vec3(
                    die.velocity.x * FRICTION,
                    -die.velocity.y * RESTITUTION,
                    die.velocity.z * FRICTION
                )
                die.angularVelocity = die.angularVelocity * FRICTION
            }
        }
    }

    private fun resolveWallCollisions(die: DieBody) {
        val minX = -tableHalfWidth + DieBody.HALF_SIZE
        val maxX = tableHalfWidth - DieBody.HALF_SIZE
        val minZ = -tableHalfDepth + DieBody.HALF_SIZE
        val maxZ = tableHalfDepth - DieBody.HALF_SIZE

        if (die.position.x < minX) {
            die.position = Vec3(minX, die.position.y, die.position.z)
            die.velocity = Vec3(-die.velocity.x * RESTITUTION, die.velocity.y, die.velocity.z)
        } else if (die.position.x > maxX) {
            die.position = Vec3(maxX, die.position.y, die.position.z)
            die.velocity = Vec3(-die.velocity.x * RESTITUTION, die.velocity.y, die.velocity.z)
        }

        if (die.position.z < minZ) {
            die.position = Vec3(die.position.x, die.position.y, minZ)
            die.velocity = Vec3(die.velocity.x, die.velocity.y, -die.velocity.z * RESTITUTION)
        } else if (die.position.z > maxZ) {
            die.position = Vec3(die.position.x, die.position.y, maxZ)
            die.velocity = Vec3(die.velocity.x, die.velocity.y, -die.velocity.z * RESTITUTION)
        }
    }

    private fun resolveDieDieCollisions() {
        for (i in dice.indices) {
            for (j in i + 1 until dice.size) {
                val a = dice[i]
                val b = dice[j]
                val delta = b.position - a.position
                val dist = delta.length()
                val minDist = DieBody.COLLIDE_RADIUS * 2f
                if (dist > 1e-4f && dist < minDist) {
                    val normal = delta * (1f / dist)
                    val overlap = minDist - dist

                    // Overlap is always corrected, whether or not the dice are at rest. Skipping
                    // resting dice left any die that settled against another permanently
                    // overlapping it, since nothing would ever push them apart again — and two
                    // dice that both came to rest touching stayed that way for the whole turn.
                    // A resting die holds its ground and the moving one takes the whole push;
                    // if both are resting they share it.
                    when {
                        a.atRest && !b.atRest -> b.position = b.position + normal * overlap
                        b.atRest && !a.atRest -> a.position = a.position - normal * overlap
                        else -> {
                            val push = normal * (overlap * 0.5f)
                            a.position = a.position - push
                            b.position = b.position + push
                        }
                    }

                    val relVel = b.velocity - a.velocity
                    val speedAlongNormal = relVel.dot(normal)
                    if (speedAlongNormal < 0f) {
                        val impulse = normal * (-speedAlongNormal * RESTITUTION)
                        if (!a.atRest) a.velocity = a.velocity - impulse
                        if (!b.atRest) b.velocity = b.velocity + impulse
                        a.atRest = false
                        b.atRest = false
                    }
                }
            }
        }
    }

    fun allAtRest(): Boolean = dice.isNotEmpty() && dice.all { it.atRest }

    fun clampToBounds() {
        for (die in dice) {
            val x = die.position.x.coerceIn(-tableHalfWidth + DieBody.HALF_SIZE, tableHalfWidth - DieBody.HALF_SIZE)
            val z = die.position.z.coerceIn(-tableHalfDepth + DieBody.HALF_SIZE, tableHalfDepth - DieBody.HALF_SIZE)
            if (abs(x - die.position.x) > 1e-4f || abs(z - die.position.z) > 1e-4f) {
                die.position = Vec3(x, die.position.y, z)
            }
        }
    }
}
