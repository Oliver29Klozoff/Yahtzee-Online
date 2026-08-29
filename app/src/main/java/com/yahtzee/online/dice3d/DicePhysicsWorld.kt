package com.yahtzee.online.dice3d

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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

        /**
         * Closing speed a contact must carry before it counts as a collision and wakes the dice.
         *
         * Comfortably above twice [DieBody.REST_SPEED], so two dice that are each slow enough to
         * be settling cannot, between them, produce a contact fast enough to wake either.
         */
        private const val WAKE_SPEED = 0.12f

        /**
         * How many times the overlap constraints are relaxed per step. Four resolves a five-die
         * heap; one leaves it interpenetrating.
         */
        private const val SEPARATION_PASSES = 4

        /**
         * How far inside each other two dice may be and still count as merely touching.
         *
         * Separation converges on exactly the contact distance, so without a little slack the
         * last fraction of a unit of floating-point error would keep dice awake for ever.
         */
        private const val OVERLAP_TOLERANCE = 0.02f

        /** Radians. Successive multiples never repeat a heading, so pairs fan out evenly. */
        private const val GOLDEN_ANGLE = 2.39996f
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
        // A die still inside another one is not settled, however still it is holding.
        //
        // Resting was decided purely on how slowly a die was moving, and separation moves dice by
        // repositioning them rather than by giving them velocity — so a heap could go to sleep
        // mid-separation, still visibly interpenetrating, and the roll would be reported as landed
        // with dice sitting inside each other. That is the stacking players were looking at. Held
        // awake, they finish sliding apart first and settle a few frames later, properly spread.
        for (i in dice.indices) {
            val die = dice[i]
            if (isOverlapping(i)) {
                die.wake()
                continue
            }
            if (!die.atRest) die.markRestIfSettled(dt)
        }
    }

    /** Whether die [index] is inside another by more than floating-point slop. */
    private fun isOverlapping(index: Int): Boolean {
        val die = dice[index]
        val minDist = DieBody.COLLIDE_RADIUS * 2f - OVERLAP_TOLERANCE
        for (other in dice.indices) {
            if (other == index) continue
            if ((dice[other].position - die.position).length() < minDist) return true
        }
        return false
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

    /**
     * Pushes overlapping dice apart and bounces the ones that genuinely collided.
     *
     * The separation runs several times per step. One pass is enough for a single pair but not
     * for a pile: with five dice heaped together, correcting each pair in turn undoes much of
     * what the previous pair's correction achieved, and the heap was still interpenetrating by
     * the time everything in it had gone to sleep — which is precisely the "dice landed on top of
     * each other" the player sees. Relaxing the same constraints a few times over converges
     * instead, and costs nothing at ten pairs.
     */
    private fun resolveDieDieCollisions() {
        repeat(SEPARATION_PASSES) { pass ->
            separationPass(applyImpulses = pass == 0)
        }
    }

    private fun separationPass(applyImpulses: Boolean) {
        for (i in dice.indices) {
            for (j in i + 1 until dice.size) {
                val a = dice[i]
                val b = dice[j]
                val delta = b.position - a.position
                val dist = delta.length()
                val minDist = DieBody.COLLIDE_RADIUS * 2f
                if (dist < minDist) {
                    // Dice sitting at exactly the same point have no normal to derive, and the
                    // old guard skipped them for having none — so the one arrangement most in
                    // need of separating was the one arrangement never separated at all. They
                    // now get an invented direction like any other coincident pair.
                    val normal = if (dist > 1e-4f) delta * (1f / dist) else Vec3(1f, 0f, 0f)
                    val overlap = minDist - dist

                    // Overlap is always corrected, whether or not the dice are at rest. Skipping
                    // resting dice left any die that settled against another permanently
                    // overlapping it, since nothing would ever push them apart again — and two
                    // dice that both came to rest touching stayed that way for the whole turn.
                    // A resting die holds its ground and the moving one takes the whole push;
                    // if both are resting they share it.
                    // Dice are pushed apart ACROSS the table, never upwards.
                    //
                    // Separating along the true contact normal lifts a die that landed on top of
                    // another straight up into the air, where gravity drops it back onto the same
                    // spot for the next frame to lift again. The pair stays stacked and shivering
                    // indefinitely, which is the state players were seeing. Flattening the push
                    // lets the upper die slide off and come to rest beside its neighbour instead
                    // — which is also what real dice do, since one balanced on another is not a
                    // throw anybody would accept either.
                    val separation = horizontalPush(normal, delta, i * dice.size + j)
                    when {
                        a.atRest && !b.atRest -> b.position = b.position + separation * overlap
                        b.atRest && !a.atRest -> a.position = a.position - separation * overlap
                        else -> {
                            val push = separation * (overlap * 0.5f)
                            a.position = a.position - push
                            b.position = b.position + push
                        }
                    }

                    val relVel = b.velocity - a.velocity
                    val speedAlongNormal = relVel.dot(normal)

                    // Only a real collision wakes anything.
                    //
                    // This used to fire on `speedAlongNormal < 0f` — any approach at all, however
                    // infinitesimal — and then cleared `atRest` on both dice unconditionally. Two
                    // dice that came to rest against each other could never get out of that: the
                    // positional correction above holds them exactly touching, the faintest
                    // numerical drift counts as approaching, both get woken, and settling again
                    // demands a further [DieBody.REST_SECONDS] of quiet that the next frame takes
                    // away. They jittered against each other for ever.
                    //
                    // That was not just an animation that would not finish. Nothing reports the
                    // roll as landed until every die is at rest, and the game screen keeps the
                    // keep/reroll tiles hidden until it hears that — so a pair of dice touching
                    // left the player unable to select anything at all, with no way out but to
                    // leave the game and come back.
                    //
                    // The threshold sits above the speed at which a die is considered slow enough
                    // to rest, so two dice that are both settling cannot wake one another no
                    // matter how they are touching. Anything gentler than this is left to the
                    // positional correction, which pushes them apart without disturbing sleep.
                    if (applyImpulses && speedAlongNormal < -WAKE_SPEED) {
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

    /**
     * The contact normal with its vertical component removed, so a push separates dice sideways.
     *
     * Two dice stacked exactly on top of one another have no horizontal component to keep, and
     * some direction still has to be chosen. It is taken from whatever sideways offset the pair
     * already has, however slight, and only falls back to a fixed axis when they are perfectly
     * aligned — which keeps the shove pointing the way they were already drifting rather than
     * jerking them somewhere arbitrary.
     */
    private fun horizontalPush(normal: Vec3, delta: Vec3, pairIndex: Int): Vec3 {
        val flat = Vec3(normal.x, 0f, normal.z)
        val length = flat.length()
        if (length > 1e-3f) return flat * (1f / length)

        val drift = Vec3(delta.x, 0f, delta.z)
        val driftLength = drift.length()
        if (driftLength > 1e-5f) return drift * (1f / driftLength)

        // Stacked dead straight, with no sideways offset to take a hint from. Each pair is fanned
        // to a different heading rather than all being shoved the same way: pushing every pair
        // along one axis makes a heap try to open out into a line, and five dice in a line need
        // more width than the table has, so it can never finish separating and never settles.
        // Spread around the circle they open into a cluster, which fits comfortably.
        val angle = pairIndex * GOLDEN_ANGLE
        return Vec3(cos(angle), 0f, sin(angle))
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
