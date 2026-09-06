package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bots take a different name each time.
 *
 * Every place that seated one used to take the first name nobody in that room was using, which is
 * a fair-looking rule that produces Ada every single time — the room is empty when the first bot
 * sits down. The rotation is what makes the pool a pool rather than a list with one name at the
 * top of it.
 */
class BotNamesTest {

    @Test
    fun `the first bot is not always the same one`() {
        val (first, cursor) = BotNames.pick(0, 1)
        val (second, _) = BotNames.pick(cursor, 1)
        assertNotEquals("this is the whole complaint", first, second)
    }

    /** Successive games walk the pool rather than starting over. */
    @Test
    fun `the rotation walks the whole pool before repeating`() {
        var cursor = 0
        val seen = mutableListOf<String>()
        repeat(BotNames.POOL.size) {
            val (names, next) = BotNames.pick(cursor, 1)
            seen += names
            cursor = next
        }
        assertEquals("every name should come up once per lap", BotNames.POOL.size, seen.toSet().size)
    }

    @Test
    fun `it comes back round after a full lap`() {
        val (first, _) = BotNames.pick(0, 1)
        val (afterLap, _) = BotNames.pick(BotNames.POOL.size, 1)
        assertEquals(first, afterLap)
    }

    /** A table of bots gets a table of different names. */
    @Test
    fun `several at once are all different`() {
        val (names, _) = BotNames.pick(3, 4)
        assertEquals(4, names.size)
        assertEquals("no two opponents share a name", 4, names.toSet().size)
    }

    /** Somebody already seated is stepped over rather than duplicated. */
    @Test
    fun `names in use are skipped`() {
        val avoid = setOf(BotNames.POOL[0], BotNames.POOL[1])
        val (names, _) = BotNames.pick(0, 2, avoid)
        assertTrue(names.none { it in avoid })
        assertEquals(2, names.size)
    }

    /** Skipping the whole pool must not spin or return nothing usable. */
    @Test
    fun `asking when everything is taken still answers`() {
        val (names, _) = BotNames.pick(0, 1, BotNames.POOL.toSet())
        assertEquals(1, names.size)
    }

    @Test
    fun `asking for none takes none and moves nothing`() {
        val (names, cursor) = BotNames.pick(7, 0)
        assertTrue(names.isEmpty())
        assertEquals(7, cursor)
    }

    /** A cursor that has wrapped or gone negative must not throw. */
    @Test
    fun `an out of range cursor is handled`() {
        assertEquals(1, BotNames.pick(BotNames.POOL.size * 5 + 2, 1).first.size)
        assertEquals(1, BotNames.pick(-3, 1).first.size)
    }

    @Test
    fun `the pool has no duplicates`() {
        assertEquals(BotNames.POOL.size, BotNames.POOL.toSet().size)
        assertFalse(BotNames.POOL.any { it.isBlank() })
    }
}
