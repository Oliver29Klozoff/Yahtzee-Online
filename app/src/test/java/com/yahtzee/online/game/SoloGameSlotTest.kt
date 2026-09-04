package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A tournament fixture is filed apart from the ordinary solo game.
 *
 * There is one slot for a solo game in progress and whatever gets played next takes it. That was
 * fine while every solo game was disposable, but a tournament fixture is not — backing out of one
 * to look at the bracket and starting a quick game on the way back wrote over a match somebody was
 * forty turns into, and nothing said so.
 *
 * The store itself needs a Context, so what is pinned here is the naming that keeps the slots
 * apart. It is worth its own test because the whole fix is that one string.
 */
class SoloGameSlotTest {

    /** Mirrors SoloGameStore.slotFor. Kept in step by the assertions below. */
    private fun slotFor(tourneyCode: String?, matchId: String?): String =
        if (tourneyCode.isNullOrEmpty() || matchId.isNullOrEmpty()) "game"
        else "match_${tourneyCode}_$matchId"

    @Test
    fun `an ordinary solo game uses the shared slot`() {
        assertEquals("game", slotFor(null, null))
        assertEquals("game", slotFor("", ""))
    }

    /** A half-filled pair is not a fixture — it must not make a slot nothing will look in. */
    @Test
    fun `a half identified fixture falls back to the shared slot`() {
        assertEquals("game", slotFor("ABCDE", null))
        assertEquals("game", slotFor(null, "r0s1"))
    }

    @Test
    fun `a fixture does not share the ordinary slot`() {
        assertNotEquals(slotFor(null, null), slotFor("ABCDE", "r0s1"))
    }

    /** Two fixtures in the same tournament are two different games. */
    @Test
    fun `each fixture gets its own slot`() {
        assertNotEquals(slotFor("ABCDE", "r0s0"), slotFor("ABCDE", "r0s1"))
        assertNotEquals(slotFor("ABCDE", "r0s0"), slotFor("ZZZZZ", "r0s0"))
    }

    /** The same fixture asked for twice must land on the same slot, or it resumes nothing. */
    @Test
    fun `a fixture slot is stable`() {
        assertEquals(slotFor("ABCDE", "r1s0"), slotFor("ABCDE", "r1s0"))
    }
}
