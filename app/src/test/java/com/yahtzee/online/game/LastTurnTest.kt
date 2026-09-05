package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holding a finished turn on screen until the next one begins.
 *
 * The window is "a turn has ended and nobody has rolled since", which the room states exactly:
 * scoring resets `rollsUsed` to zero, and the next roll takes it off zero again.
 */
class LastTurnTest {

    private fun room(
        rollsUsed: Int,
        status: String = GameState.STATUS_PLAYING,
        scores: Map<String, Int> = emptyMap(),
        theirs: Map<String, Int> = emptyMap()
    ) = GameState(
        roomCode = "TESTR",
        hostId = "me",
        status = status,
        playerOrder = listOf("me", "them"),
        players = mapOf(
            "me" to Player(id = "me", name = "You", scores = scores),
            "them" to Player(id = "them", name = "Ada", scores = theirs)
        ),
        rollsUsed = rollsUsed
    )

    @Test
    fun `between turns is a turn ended with nobody rolled since`() {
        assertTrue(LastTurn.isHandover(room(rollsUsed = 0)))
    }

    @Test
    fun `a turn under way is not a hand-over`() {
        assertFalse(LastTurn.isHandover(room(rollsUsed = 1)))
        assertFalse(LastTurn.isHandover(room(rollsUsed = 3)))
    }

    /** A finished game is not waiting on anybody's roll. */
    @Test
    fun `a finished game is not a hand-over`() {
        assertFalse(LastTurn.isHandover(room(rollsUsed = 0, status = GameState.STATUS_FINISHED)))
        assertFalse(LastTurn.isHandover(room(rollsUsed = 0, status = GameState.STATUS_LOBBY)))
    }

    @Test
    fun `a newly filled box is picked up`() {
        val before = room(rollsUsed = 3)
        val after = room(rollsUsed = 0, theirs = mapOf(ScoreKey.of(0, Category.SIXES) to 24))

        val scored = LastTurn.detect(before, after)
        assertEquals("them", scored?.playerId)
        assertEquals("Ada", scored?.playerName)
        assertEquals(24, scored?.points)
    }

    /** Your own finished turn counts here, unlike the announcement to other players. */
    @Test
    fun `your own turn is reported too`() {
        val before = room(rollsUsed = 3)
        val after = room(rollsUsed = 0, scores = mapOf(ScoreKey.of(0, Category.FIVES) to 15))

        assertEquals("me", LastTurn.detect(before, after)?.playerId)
    }

    /**
     * The case that decides whether any of this works.
     *
     * A score is written as several separate values — the scorecard, then the turn index, then
     * the roll count back to zero — so a snapshot exists carrying the new score while `rollsUsed`
     * still reads three. Detection must not depend on the roll count having caught up, or the
     * hand-over would have nothing to show by the time it started.
     */
    @Test
    fun `a score is detected before the roll count catches up`() {
        val before = room(rollsUsed = 3)
        val midWrite = room(rollsUsed = 3, theirs = mapOf(ScoreKey.of(0, Category.ONES) to 2))

        assertEquals(2, LastTurn.detect(before, midWrite)?.points)
    }

    @Test
    fun `nothing changing reports nothing`() {
        val same = room(rollsUsed = 1, theirs = mapOf(ScoreKey.of(0, Category.SIXES) to 24))
        assertNull(LastTurn.detect(same, same))
    }

    /** The first snapshot has nothing to compare against. */
    @Test
    fun `the first look reports nothing`() {
        assertNull(LastTurn.detect(null, room(rollsUsed = 0)))
    }

    /** A client catching up sees several at once; the biggest is the one worth showing. */
    @Test
    fun `the largest of several is reported`() {
        val before = room(rollsUsed = 3)
        val after = room(
            rollsUsed = 0,
            theirs = mapOf(
                ScoreKey.of(0, Category.ONES) to 3,
                ScoreKey.of(0, Category.YAHTZEE) to 50
            )
        )
        assertEquals(50, LastTurn.detect(before, after)?.points)
    }

    /** A zero is a real score — throwing a roll away is exactly what people want to see. */
    @Test
    fun `taking a zero is still a finished turn`() {
        val before = room(rollsUsed = 3)
        val after = room(rollsUsed = 0, theirs = mapOf(ScoreKey.of(0, Category.YAHTZEE) to 0))

        val scored = LastTurn.detect(before, after)
        assertEquals("Ada", scored?.playerName)
        assertEquals(0, scored?.points)
    }
}
