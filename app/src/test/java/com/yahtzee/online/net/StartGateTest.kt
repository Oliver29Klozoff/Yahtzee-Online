package com.yahtzee.online.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A room cannot start until somebody is in it.
 *
 * The host used to be offered the start button the moment the room existed. Tapping it opened a
 * game of one: the roll-off had a single entrant, the host won it, and they played a whole
 * scorecard while the other person was still reading the invite.
 *
 * The threshold is pinned here because it is one number holding the whole thing up, and setting
 * it back to 1 would look like a harmless loosening rather than the return of a lost game.
 */
class StartGateTest {

    @Test
    fun `a room needs two players`() {
        assertEquals(2, GameRepository.MIN_PLAYERS_TO_START)
    }

    @Test
    fun `an empty or single seat room cannot start`() {
        assertFalse(0 >= GameRepository.MIN_PLAYERS_TO_START)
        assertFalse(1 >= GameRepository.MIN_PLAYERS_TO_START)
    }

    @Test
    fun `a full enough room can`() {
        assertTrue(2 >= GameRepository.MIN_PLAYERS_TO_START)
        assertTrue(6 >= GameRepository.MIN_PLAYERS_TO_START)
    }
}
