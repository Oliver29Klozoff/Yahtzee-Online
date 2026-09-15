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
     * Nothing generated contains a digit, which is what disposes of the confusable pairs.
     *
     * The old character generator left out I, O, 0 and 1 because they are misheard against each
     * other when a code is spelled out. A name is said rather than spelled, and with no digits
     * anywhere in the pool there is nothing for a letter to be mistaken for — so the exclusion is
     * no longer needed and the letters are free to spell words.
     */
    @Test
    fun `generated codes carry no digits`() {
        val random = Random(99)
        val seen = (1..2000).map { RoomCode.random(random) }.joinToString("").toSet()
        assertTrue("a generated code should be letters only", seen.all { it in 'A'..'Z' })
    }

    /** Every name has to be a code, or the one that is not will fail on the day it is drawn. */
    @Test
    fun `every name is a usable code`() {
        RoomCode.NAMES.forEach {
            assertTrue("$it is not a valid room code", RoomCode.isValid(it))
        }
    }

    @Test
    fun `no name is repeated`() {
        assertEquals(RoomCode.NAMES.size, RoomCode.NAMES.toSet().size)
    }

    /**
     * Enough of them that drawing again on a clash stays rare.
     *
     * The repositories retry a handful of times before giving up on making a room at all, so a
     * thin pool would not merely repeat itself — it would start refusing to open rooms.
     */
    @Test
    fun `the pool is big enough to draw from`() {
        assertTrue("pool of ${RoomCode.NAMES.size} is too thin", RoomCode.NAMES.size >= 60)
    }
}
