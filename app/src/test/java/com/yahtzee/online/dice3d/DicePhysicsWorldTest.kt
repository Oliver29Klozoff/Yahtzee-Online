package com.yahtzee.online.dice3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The dice have to come to rest. Not for the look of it — the game screen keeps the keep/reroll
 * tiles hidden until the roll reports itself landed, so a throw that never settles takes the turn
 * away from the player entirely, with no way out but leaving the game and coming back.
 *
 * These are the arrangements that used to prevent it.
 */
class DicePhysicsWorldTest {

    private companion object {
        const val DT = 1f / 60f
        /** Generous: a real throw is down in well under two seconds. */
        const val MAX_FRAMES = 60 * 8
        const val MIN_SEPARATION = DieBody.COLLIDE_RADIUS * 2f
    }

    private fun settle(world: DicePhysicsWorld): Int {
        repeat(MAX_FRAMES) { frame ->
            world.step(DT)
            if (world.allAtRest()) return frame
        }
        return -1
    }

    private fun assertSettles(world: DicePhysicsWorld, what: String) {
        val frames = settle(world)
        assertTrue("$what never came to rest in ${MAX_FRAMES / 60} seconds", frames >= 0)
    }

    private fun assertApart(world: DicePhysicsWorld) {
        for (i in world.dice.indices) {
            for (j in i + 1 until world.dice.size) {
                val separation = (world.dice[j].position - world.dice[i].position).length()
                assertTrue(
                    "dice $i and $j ended up $separation apart, inside $MIN_SEPARATION",
                    separation >= MIN_SEPARATION - 1e-3f
                )
            }
        }
    }

    /**
     * The exact reported failure: one die on top of another. Separating along the true contact
     * normal lifted the upper die straight up, gravity dropped it back, and the pair shivered
     * for ever without ever reporting the roll as landed.
     */
    @Test
    fun `a die resting on top of another settles instead of hovering`() {
        val world = DicePhysicsWorld()
        world.dice += DieBody(position = Vec3(0f, DieBody.HALF_SIZE, 0f))
        world.dice += DieBody(position = Vec3(0f, DieBody.HALF_SIZE + 1f, 0f))

        assertSettles(world, "a stacked pair")
        assertApart(world)

        // And it is on the table, not balanced in mid-air.
        world.dice.forEach { die ->
            assertEquals(DieBody.HALF_SIZE, die.position.y, 0.05f)
        }
    }

    /**
     * Two dice that come to rest touching. Any contact at all used to clear `atRest` on both, and
     * settling again demanded a further stretch of quiet that the next frame took away.
     */
    @Test
    fun `two dice touching at rest do not keep waking each other`() {
        val world = DicePhysicsWorld()
        world.dice += DieBody(position = Vec3(-0.5f, DieBody.HALF_SIZE, 0f))
        world.dice += DieBody(position = Vec3(0.5f, DieBody.HALF_SIZE, 0f))

        assertSettles(world, "a touching pair")
        assertApart(world)
    }

    /** Five dice dropped down the same column — the worst case a throw can produce. */
    @Test
    fun `a column of five dice settles and spreads out`() {
        val world = DicePhysicsWorld()
        repeat(5) { i ->
            world.dice += DieBody(position = Vec3(0f, DieBody.HALF_SIZE + i * 0.9f, 0f))
        }

        assertSettles(world, "a column of five")
        assertApart(world)
        world.dice.forEach { die ->
            assertEquals(DieBody.HALF_SIZE, die.position.y, 0.05f)
        }
    }

    /** Perfectly coincident dice have no direction to be pushed apart in; one has to be invented. */
    @Test
    fun `dice at the very same point are still separated`() {
        val world = DicePhysicsWorld()
        world.dice += DieBody(position = Vec3(0f, DieBody.HALF_SIZE, 0f))
        world.dice += DieBody(position = Vec3(0f, DieBody.HALF_SIZE, 0f))

        assertSettles(world, "coincident dice")
        assertApart(world)
    }

    /** Nothing may be shoved off the felt while being separated. */
    @Test
    fun `dice stay on the table`() {
        val world = DicePhysicsWorld()
        // Jammed into a corner, where separation has nowhere easy to push them.
        repeat(5) { i ->
            world.dice += DieBody(
                position = Vec3(
                    world.tableHalfWidth - DieBody.HALF_SIZE - i * 0.05f,
                    DieBody.HALF_SIZE + i * 0.4f,
                    world.tableHalfDepth - DieBody.HALF_SIZE
                )
            )
        }

        assertSettles(world, "a corner pile")
        world.dice.forEach { die ->
            assertTrue(
                "die left the table at ${die.position.x}, ${die.position.z}",
                abs(die.position.x) <= world.tableHalfWidth - DieBody.HALF_SIZE + 1e-3f &&
                    abs(die.position.z) <= world.tableHalfDepth - DieBody.HALF_SIZE + 1e-3f
            )
        }
    }

    /** A normal throw must still behave — the fix must not have frozen the dice where they land. */
    @Test
    fun `a thrown die still travels and then rests`() {
        val world = DicePhysicsWorld()
        val die = DieBody(position = Vec3(-2f, 1.5f, -1.5f))
        world.dice += die
        die.throwWith(direction = Vec3(1f, -0.3f, 1f), speed = 6f, spin = 12f)
        val start = die.position

        assertSettles(world, "a thrown die")
        assertTrue(
            "the die barely moved from where it was thrown",
            (die.position - start).length() > 1f
        )
        assertEquals(DieBody.HALF_SIZE, die.position.y, 0.05f)
    }
}
