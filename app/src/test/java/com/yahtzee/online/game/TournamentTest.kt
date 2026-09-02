package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draw and the walk through it.
 *
 * A bracket is the kind of thing that looks right until the field is not a power of two, and then
 * quietly strands somebody in a fixture that can never be played. These pin the awkward sizes as
 * hard as the tidy ones.
 */
class TournamentTest {

    private fun field(n: Int) = (0 until n).map {
        Entrant(id = "p$it", name = "P$it", seed = it)
    }

    private fun seedOf(entrants: List<Entrant>): (String) -> Int =
        { id -> entrants.firstOrNull { it.id == id }?.seed ?: Int.MAX_VALUE }

    @Test
    fun `a bracket is the next power of two`() {
        assertEquals(2, Tournament.bracketSize(2))
        assertEquals(4, Tournament.bracketSize(3))
        assertEquals(8, Tournament.bracketSize(5))
        assertEquals(16, Tournament.bracketSize(16))
    }

    @Test
    fun `rounds are the times you halve the bracket`() {
        assertEquals(1, Tournament.roundCount(2))
        assertEquals(2, Tournament.roundCount(4))
        assertEquals(3, Tournament.roundCount(5))
        assertEquals(4, Tournament.roundCount(16))
    }

    /** Top against bottom, so the two strongest cannot meet before the final. */
    @Test
    fun `the first round pairs the top seed with the bottom one`() {
        val matches = Tournament.draw(field(8))
        val first = matches[Tournament.matchId(0, 0)]!!
        assertEquals("p0", first.aId)
        assertEquals("p7", first.bId)
    }

    @Test
    fun `a full bracket is drawn in advance, empty rounds and all`() {
        val matches = Tournament.draw(field(8))
        assertEquals(4, matches.values.count { it.round == 0 })
        assertEquals(2, matches.values.count { it.round == 1 })
        assertEquals(1, matches.values.count { it.round == 2 })
    }

    /**
     * The case that strands people.
     *
     * Five entrants go into a bracket of eight, so three seats are empty. The three players drawn
     * against an empty seat must be through to round two the moment the draw is made, not waiting
     * on a fixture with nobody on the other side of it.
     */
    @Test
    fun `an odd field gives byes, and the byes are already through`() {
        val entrants = field(5)
        val matches = Tournament.draw(entrants)

        val byes = matches.values.filter { it.round == 0 && it.decided }
        assertEquals(3, byes.size)
        byes.forEach { assertTrue("a bye must have nobody to play", it.bId.isEmpty()) }

        val secondRound = matches.values.filter { it.round == 1 }
        val placed = secondRound.flatMap { listOf(it.aId, it.bId) }.filter { it.isNotEmpty() }
        byes.forEach {
            assertTrue("${it.aId} had a bye and was not moved up", placed.contains(it.aId))
        }
    }

    /** Two players is one match and no rounds beyond it. */
    @Test
    fun `the smallest tournament is a single final`() {
        val matches = Tournament.draw(field(2))
        assertEquals(1, matches.size)
        assertEquals(0, matches.values.first().round)
        assertTrue(matches.values.first().ready)
    }

    @Test
    fun `one player is not a tournament`() {
        assertTrue(Tournament.draw(field(1)).isEmpty())
    }

    @Test
    fun `a result sends the winner up to the next round`() {
        val entrants = field(4)
        val drawn = Tournament.draw(entrants)
        val settled = Tournament.settle(
            drawn, Tournament.matchId(0, 0), aScore = 250, bScore = 190, seedOf = seedOf(entrants)
        )
        assertEquals("p0", settled[Tournament.matchId(0, 0)]!!.winnerId)
        assertEquals("p0", settled[Tournament.matchId(1, 0)]!!.aId)
    }

    /** Odd slots feed the bottom seat, or two winners land on top of each other. */
    @Test
    fun `the second match of a pair feeds the other seat`() {
        val entrants = field(4)
        var matches = Tournament.draw(entrants)
        matches = Tournament.settle(matches, Tournament.matchId(0, 0), 250, 190, seedOf(entrants))
        matches = Tournament.settle(matches, Tournament.matchId(0, 1), 150, 300, seedOf(entrants))
        val final = matches[Tournament.matchId(1, 0)]!!
        assertEquals("p0", final.aId)
        assertEquals("p2", final.bId)
        assertTrue(final.ready)
    }

    /** A tie cannot leave the seat empty, so it goes to the higher seed. */
    @Test
    fun `a drawn match is decided on seed`() {
        val entrants = field(4)
        val drawn = Tournament.draw(entrants)
        val settled = Tournament.settle(
            drawn, Tournament.matchId(0, 0), aScore = 220, bScore = 220, seedOf = seedOf(entrants)
        )
        assertEquals("p0", settled[Tournament.matchId(0, 0)]!!.winnerId)
    }

    /** The same result arriving twice must not walk somebody two rounds up the draw. */
    @Test
    fun `settling the same match twice changes nothing the second time`() {
        val entrants = field(4)
        val drawn = Tournament.draw(entrants)
        val once = Tournament.settle(drawn, Tournament.matchId(0, 0), 250, 190, seedOf(entrants))
        val twice = Tournament.settle(once, Tournament.matchId(0, 0), 250, 190, seedOf(entrants))
        assertEquals(once, twice)
    }

    @Test
    fun `an unplayable match cannot be settled`() {
        val entrants = field(4)
        val drawn = Tournament.draw(entrants)
        // The final has nobody in it yet.
        val settled = Tournament.settle(drawn, Tournament.matchId(1, 0), 250, 190, seedOf(entrants))
        assertFalse(settled[Tournament.matchId(1, 0)]!!.decided)
    }

    @Test
    fun `the winner of the last round is the champion`() {
        val entrants = field(4)
        var matches = Tournament.draw(entrants)
        matches = Tournament.settle(matches, Tournament.matchId(0, 0), 250, 190, seedOf(entrants))
        matches = Tournament.settle(matches, Tournament.matchId(0, 1), 150, 300, seedOf(entrants))
        matches = Tournament.settle(matches, Tournament.matchId(1, 0), 400, 100, seedOf(entrants))
        val state = TournamentState(matches = matches)
        assertEquals("p0", state.champion)
    }

    /** What the bracket screen asks it every time it draws. */
    @Test
    fun `your next match is the earliest one you have not settled`() {
        val entrants = field(4)
        var matches = Tournament.draw(entrants)
        matches = Tournament.settle(matches, Tournament.matchId(0, 0), 250, 190, seedOf(entrants))
        val state = TournamentState(matches = matches)
        val next = state.nextMatchFor("p0")!!
        assertEquals(1, next.round)
    }
}
