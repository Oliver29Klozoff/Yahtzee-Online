package com.yahtzee.online.dice3d

import kotlin.random.Random

/**
 * A single die's physical state. Uses a sphere collision proxy (radius = half the
 * cube's diagonal-ish extent) for die-die and die-wall collision, which is cheap and
 * visually fine once dice settle. Face-up is read from `orientation` once at rest.
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
        const val MASS = 1f
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

    /**
     * Rigs a throw so the die's rotation is guaranteed to land exactly on [targetValue],
     * instead of landing on a random face and being corrected afterward. Rotation for a
     * rigged throw is driven entirely by [updateRig] on a fixed timeline (not by the
     * angular-velocity/damping integrator), tumbling several whole turns around a random
     * axis and decelerating into the precomputed target orientation.
     */
    fun throwToward(
        targetValue: Int,
        direction: Vec3,
        speed: Float,
        random: Random = Random.Default,
        durationScale: Float = 1f
    ) {
        val travelDir = direction.normalized()
        velocity = travelDir * speed
        angularVelocity = Vec3.ZERO

        // Roll around an axis perpendicular to the direction of travel (like a wheel), so the
        // tumble visually reads as rolling forward instead of spinning in place. Mix in a small
        // random tilt so it's not a perfectly clean, robotic roll.
        val rollAxis = Vec3.UP.cross(travelDir).normalized()
        val wobble = Vec3(
            (random.nextFloat() - 0.5f) * 0.15f,
            (random.nextFloat() - 0.5f) * 0.15f,
            (random.nextFloat() - 0.5f) * 0.15f
        )
        val axis = (rollAxis + wobble).normalized()

        val extraTurns = 3 + random.nextInt(2)
        val totalAngle = extraTurns * 2f * Math.PI.toFloat()

        val finalUpright = faceForValue[targetValue.coerceIn(1, 6)] ?: Vec3.UP
        rigTargetOrientation = orientationFacingUp(finalUpright)
        rigAxis = axis
        rigTotalAngle = totalAngle
        // Start orientation: target rotated backward by the full spin, computed via
        // axis+angle directly (NOT via a quaternion round trip, which can't represent
        // multiple full turns — two quaternions differing by whole 360s are numerically
        // identical, so slerping between them produces no visible spin at all).
        orientation = (Quat.fromAxisAngle(axis, -totalAngle) * rigTargetOrientation!!).normalized()

        atRest = false
        restTimer = 0f
        rigElapsed = 0f
        rigDuration = (1.1f + random.nextFloat() * 0.3f) * durationScale.coerceAtLeast(0.05f)
        rigActive = true
    }

    private var rigTargetOrientation: Quat? = null
    private var rigAxis: Vec3 = Vec3.UP
    private var rigTotalAngle: Float = 0f
    private var rigElapsed: Float = 0f
    private var rigDuration: Float = 1f
    private var rigActive: Boolean = false

    /** True while a [throwToward] rig is actively driving rotation on its fixed timeline. */
    fun isRigged(): Boolean = rigActive

    /**
     * Advances the rigged spin by [dt] directly via axis+angle (tracking how much of the
     * total spin has unwound so far), rather than slerping between two quaternion endpoints
     * — slerp has no notion of "spin N times then arrive," since a quaternion only encodes
     * a net rotation with no memory of how many full turns produced it.
     */
    fun updateRig(dt: Float): Boolean {
        val to = rigTargetOrientation ?: return false
        rigElapsed += dt
        val rawT = (rigElapsed / rigDuration).coerceIn(0f, 1f)

        // Smooth, continuous ease-out across the whole throw (quadratic) instead of a flat
        // speed followed by a late hard brake — reads as a die naturally losing momentum to
        // friction the whole time it's tumbling, rather than "fast then sudden stop."
        val easedT = 1f - (1f - rawT) * (1f - rawT)

        val remainingAngle = rigTotalAngle * (1f - easedT)
        orientation = (Quat.fromAxisAngle(rigAxis, -remainingAngle) * to).normalized()

        if (rawT >= 1f) {
            orientation = to
            rigTargetOrientation = null
            rigActive = false
            return false
        }
        return true
    }

    private fun orientationFacingUp(localFaceNormal: Vec3): Quat {
        val target = Vec3.UP
        val axis = localFaceNormal.cross(target)
        val dot = localFaceNormal.dot(target).coerceIn(-1f, 1f)
        val angle = kotlin.math.acos(dot)
        return if (axis.length() < 1e-4f) {
            if (dot > 0f) Quat.IDENTITY else Quat.fromAxisAngle(Vec3(1f, 0f, 0f), Math.PI.toFloat())
        } else {
            Quat.fromAxisAngle(axis, angle)
        }
    }

    /** World-space normal of each local face, indexed by die pip value 1..6. */
    fun faceValueUp(): Int {
        val localFaces = mapOf(
            1 to Vec3(0f, 1f, 0f),
            6 to Vec3(0f, -1f, 0f),
            2 to Vec3(1f, 0f, 0f),
            5 to Vec3(-1f, 0f, 0f),
            3 to Vec3(0f, 0f, 1f),
            4 to Vec3(0f, 0f, -1f)
        )
        var best = 1
        var bestDot = -2f
        for ((value, normal) in localFaces) {
            val worldNormal = orientation.rotate(normal)
            val d = worldNormal.dot(Vec3.UP)
            if (d > bestDot) {
                bestDot = d
                best = value
            }
        }
        return best
    }

    private val faceForValue = mapOf(
        1 to Vec3(0f, 1f, 0f),
        6 to Vec3(0f, -1f, 0f),
        2 to Vec3(1f, 0f, 0f),
        5 to Vec3(-1f, 0f, 0f),
        3 to Vec3(0f, 0f, 1f),
        4 to Vec3(0f, 0f, -1f)
    )

    private fun uprightOrientationFor(targetValue: Int): Quat {
        val local = faceForValue[targetValue] ?: Vec3.UP
        val currentWorld = orientation.rotate(local).normalized()
        val target = Vec3.UP
        val axis = currentWorld.cross(target)
        val dot = currentWorld.dot(target).coerceIn(-1f, 1f)
        val angle = kotlin.math.acos(dot)
        val correction = if (axis.length() < 1e-4f) Quat.IDENTITY else Quat.fromAxisAngle(axis, angle)
        return (correction * orientation).normalized()
    }

    /** Instantly forces this die upright on [targetValue] (used for held dice, no roll happening). */
    fun snapToUpright(targetValue: Int) {
        orientation = uprightOrientationFor(targetValue)
        angularVelocity = Vec3.ZERO
    }

    fun markRestIfSettled(dt: Float): Boolean {
        if (isRigged()) {
            restTimer = 0f
            atRest = false
            return false
        }
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
