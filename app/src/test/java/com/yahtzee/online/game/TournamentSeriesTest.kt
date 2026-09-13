package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures played as a series rather than a single game.
 *
 * The part that has to be right is counting: a game reported twice, or a fixture handed to
 * somebody who won one of three, is a tournament decided wrongly and no way to tell from the
 * bracket afterwards.
 */
class TournamentSeriesTest {

    private val seeds = mapOf("a" to 0, "b" to 1)
    private fun seedOf(id: String) = seeds[id] ?: Int.MAX_VALUE

    private fun draw() = mapOf(
        "r0s0" to Match(round = 0, slot = 0, aId = "a", bId = "b")
    )

    private fun settle(
        matches: Map<String, Match>,
        aScore: Int,
        bScore: Int,
        bestOf: Int,
        room: String
    ) = Tournament.settle(matches, "r0s0", aScore, bScore, bestOf, room, ::seedOf)

    @Test
    fun `one of one takes a single game fixture`() {
        assertEquals(1, Tournament.gamesToWin(1))
    }

    @Test
    fun `two of three takes a series`() {
        assertEquals(2, Tournament.gamesToWin(3))
    }

    /** Unchanged behaviour: a single-game tournament is decided by its one result. */
    @Test
    fun `a single game fixture is decided at once`() {
        val settled = settle(draw(), aScore = 200, bScore = 150, bestOf = 1, room = "R1")
        assertEquals("a", settled.getValue("r0s0").winnerId)
        assertTrue(settled.getValue("r0s0").decided)
    }

    @Test
    fun `one win does not take a best of three`() {
        val settled = settle(draw(), aScore = 200, bScore = 150, bestOf = 3, room = "R1")
        val match = settled.getValue("r0s0")
        assertFalse(match.decided)
        assertEquals(1, match.aWins)
        assertEquals(0, match.bWins)
    }

    @Test
    fun `two wins takes a best of three`() {
        var matches = settle(draw(), 200, 150, bestOf = 3, room = "R1")
        matches = settle(matches, 180, 170, bestOf = 3, room = "R2")
        val match = matches.getValue("r0s0")
        assertTrue(match.decided)
        assertEquals("a", match.winnerId)
        assertEquals(2, match.aWins)
    }

    /** A series that goes the distance is taken by whoever wins the third. */
    @Test
    fun `a decider settles it`() {
        var matches = settle(draw(), 200, 150, bestOf = 3, room = "R1")
        matches = settle(matches, 100, 300, bestOf = 3, room = "R2")
        assertFalse("one each is not decided", matches.getValue("r0s0").decided)

        matches = settle(matches, 90, 250, bestOf = 3, room = "R3")
        val match = matches.getValue("r0s0")
        assertEquals("b", match.winnerId)
        assertEquals(1, match.aWins)
        assertEquals(2, match.bWins)
    }

    /**
     * The case the whole design turns on.
     *
     * Both players report the same finished game, and in a series neither report finds the
     * fixture decided — so without keying on the room, one game would count twice and a fixture
     * could be taken by somebody who won a single game of three.
     */
    @Test
    fun `the same game reported twice counts once`() {
        var matches = settle(draw(), 200, 150, bestOf = 3, room = "R1")
        matches = settle(matches, 200, 150, bestOf = 3, room = "R1")

        val match = matches.getValue("r0s0")
        assertEquals("counted twice", 1, match.aWins)
        assertFalse(match.decided)
    }

    /** Two different games that happen to have identical scores are still two games. */
    @Test
    fun `identical scores in different rooms are different games`() {
        var matches = settle(draw(), 200, 150, bestOf = 3, room = "R1")
        matches = settle(matches, 200, 150, bestOf = 3, room = "R2")
        assertTrue(matches.getValue("r0s0").decided)
    }

    /**
     * Each game of a series is played in its own room, so the finished one must not be left on
     * the fixture — both players would be sent back to a board with no turns left in it.
     */
    @Test
    fun `an undecided series clears its room`() {
        val started = draw().mapValues { it.value.copy(roomCode = "R1") }
        val settled = settle(started, 200, 150, bestOf = 3, room = "R1")
        assertEquals("", settled.getValue("r0s0").roomCode)
        assertEquals(Tournament.MATCH_PENDING, settled.getValue("r0s0").status)
    }

    /** A drawn game still goes somewhere: the better seed takes it, per game. */
    @Test
    fun `a drawn game goes to the better seed`() {
        val settled = settle(draw(), 150, 150, bestOf = 3, room = "R1")
        assertEquals(1, settled.getValue("r0s0").aWins)
    }

    /** Nothing more is counted once a fixture has been won. */
    @Test
    fun `a decided fixture ignores further games`() {
        var matches = settle(draw(), 200, 150, bestOf = 1, room = "R1")
        matches = settle(matches, 10, 900, bestOf = 1, room = "R2")
        assertEquals("a", matches.getValue("r0s0").winnerId)
    }

    /** The running tally is what the bracket reads mid-series. */
    @Test
    fun `a series in progress reports itself as underway`() {
        val settled = settle(draw(), 200, 150, bestOf = 3, room = "R1")
        val match = settled.getValue("r0s0")
        assertTrue(match.seriesUnderway)
        assertEquals("1–0", match.seriesLine)
    }

    @Test
    fun `an untouched fixture is not underway`() {
        assertFalse(draw().getValue("r0s0").seriesUnderway)
    }
}
