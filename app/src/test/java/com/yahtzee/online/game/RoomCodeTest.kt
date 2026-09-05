package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What a room code may be, now that people can choose their own.
 *
 * The length cap is the part that matters most and is the least obvious: tournament matches
 * record the room they are played in, and the database rules cap that field at eight characters.
 * A longer code would write a room no bracket could point at, and nothing in the app would say
 * so — it would simply fail to be a tournament.
 */
class RoomCodeTest {

    @Test
    fun `the cap matches what a tournament match can store`() {
        assertEquals("longer codes break tournament games", 8, RoomCode.MAX_LENGTH)
    }

    @Test
    fun `ordinary codes are accepted`() {
        assertTrue(RoomCode.isValid("FAMILY"))
        assertTrue(RoomCode.isValid("PIZZA"))
        assertTrue(RoomCode.isValid("K7M2P"))
        assertTrue(RoomCode.isValid("A1B2C3D4"))
    }

    @Test
    fun `too short and too long are refused`() {
        assertFalse(RoomCode.isValid(""))
        assertFalse(RoomCode.isValid("AB"))
        assertFalse(RoomCode.isValid("TOOLONGCODE"))
        assertFalse(RoomCode.isValid("A".repeat(RoomCode.MAX_LENGTH + 1)))
    }

    /** Anything that would make a Firebase key ambiguous, or simply is not a code. */
    @Test
    fun `punctuation and spaces are refused`() {
        assertFalse(RoomCode.isValid("MY CODE"))
        assertFalse(RoomCode.isValid("A-B-C"))
        assertFalse(RoomCode.isValid("HOME/1"))
        assertFalse(RoomCode.isValid("a.b.c"))
    }

    /** Lower case is what somebody typed, not what they meant. */
    @Test
    fun `typed codes are normalised before being judged`() {
        assertEquals("FAMILY", RoomCode.normalise("family"))
        assertEquals("FAMILY", RoomCode.normalise("  Family  "))
        assertEquals("MYCODE", RoomCode.normalise("my code"))
    }

    /** Normalising must not rescue something that was never a code. */
    @Test
    fun `normalising does not make punctuation valid`() {
        assertFalse(RoomCode.isValid(RoomCode.normalise("a-b-c")))
    }

    @Test
    fun `generated codes are always valid`() {
        val random = Random(4242)
        repeat(500) {
            val code = RoomCode.random(random)
            assertTrue("generated an invalid code: $code", RoomCode.isValid(code))
        }
    }

    /**
     * The generated alphabet leaves out the characters that get read back wrong.
     *
     * A code is spoken across a table as often as it is typed, and I against 1 and O against 0
     * are the pairs that cost somebody three attempts to join.
     */
    @Test
    fun `generated codes avoid the confusable characters`() {
        val random = Random(99)
        val seen = (1..2000).map { RoomCode.random(random) }.joinToString("").toSet()
        listOf('I', 'O', '0', '1').forEach {
            assertFalse("generated codes should never contain $it", it in seen)
        }
    }
}
