package com.yahtzee.online.dice3d

/**
 * Simple fixed-step rigid body simulation for N dice on a bounded table.
 * Not a general-purpose physics engine: tuned specifically for dice-in-a-tray feel.
 */
class DicePhysicsWorld(
    val tableHalfWidth: Float = 3.2f,
    val tableHalfDepth: Float = 2.2f,
    val groundY: Float = 0f
) {
    val dice = mutableListOf<DieBody>()

    companion object {
        private const val GRAVITY = -14f
        private const val RESTITUTION = 0.32f
        private const val FRICTION = 0.6f
        private const val ANGULAR_DAMPING = 0.94f
        private const val LINEAR_DAMPING = 0.992f
    }

    fun step(dt: Float) {
        for (die in dice) {
            if (die.atRest) continue
            integrate(die, dt)
            resolveGroundCollision(die)
            resolveWallCollisions(die)
        }
        resolveDieDieCollisions()
        for (die in dice) {
            if (!die.atRest) die.markRestIfSettled(dt)
        }
    }

    private fun integrate(die: DieBody, dt: Float) {
        die.velocity = die.velocity + Vec3(0f, GRAVITY * dt, 0f)
        die.velocity = die.velocity * LINEAR_DAMPING
        die.position = die.position + die.velocity * dt

        die.angularVelocity = die.angularVelocity * ANGULAR_DAMPING
        val spin = Quat.fromAngularVelocity(die.angularVelocity, dt)
        die.orientation = (spin * die.orientation).normalized()
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
                    val push = normal * (overlap * 0.5f)
                    if (!a.atRest) a.position = a.position - push
                    if (!b.atRest) b.position = b.position + push

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
}
