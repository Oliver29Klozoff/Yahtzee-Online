package com.yahtzee.online.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a room stops taking new players.
 *
 * Once everyone in it has had a turn. Before that a newcomer is a box or two behind and the game
 * is still a game; after it they would be sitting down to a full card's deficit against people
 * who cannot be caught.
 */
class ClosedRoomTest {

    private fun room(
        status: String,
        vararg filled: Int
    ): GameState {
        val players = filled.mapIndexed { index, boxes ->
            val id = "p$index"
            id to Player(
                id = id,
                name = "P$index",
                scores = (0 until boxes).associate { ScoreKey.of(0, Category.values()[it]) to 5 }
            )
        }.toMap()
        return GameState(
            roomCode = "TESTR",
            hostId = "p0",
            status = status,
            playerOrder = players.keys.toList(),
            players = players
        )
    }

    /** A lobby is the one place anybody may always walk into. */
    @Test
    fun `a lobby is open`() {
        assertFalse(room(GameState.STATUS_LOBBY, 0, 0).isClosedToNewPlayers())
    }

    /** The roll-off is before the game rather than during it. */
    @Test
    fun `the roll-off is open`() {
        assertFalse(room(GameState.STATUS_ROLL_OFF, 0, 0).isClosedToNewPlayers())
    }

    @Test
    fun `part way through the first lap is still open`() {
        assertFalse("only one player has been", room(GameState.STATUS_PLAYING, 1, 0).isClosedToNewPlayers())
        assertFalse(room(GameState.STATUS_PLAYING, 1, 1, 0).isClosedToNewPlayers())
    }

    @Test
    fun `once everyone has been it is closed`() {
        assertTrue(room(GameState.STATUS_PLAYING, 1, 1).isClosedToNewPlayers())
        assertTrue(room(GameState.STATUS_PLAYING, 3, 2, 2).isClosedToNewPlayers())
    }

    @Test
    fun `a finished game is closed`() {
        assertTrue(room(GameState.STATUS_FINISHED, 13, 13).isClosedToNewPlayers())
    }

    /**
     * A room with nobody in it must not read as closed.
     *
     * "Everyone has had a turn" is vacuously true of an empty room, and a television opens
     * exactly that — a room with no players at all. Reading it as closed would put a TV on the
     * table showing a code that admits nobody.
     */
    @Test
    fun `an empty room is not closed`() {
        assertFalse(room(GameState.STATUS_PLAYING).isClosedToNewPlayers())
    }

    /**
     * Closing is one-way.
     *
     * Nobody with an empty card can join once it is shut, so the condition cannot be un-made by
     * a later arrival — a room that closed mid-game stays closed rather than flickering open on
     * the next snapshot.
     */
    @Test
    fun `closing does not undo itself`() {
        val closed = room(GameState.STATUS_PLAYING, 2, 2)
        assertTrue(closed.isClosedToNewPlayers())
        // The same room a turn later: scores only ever accumulate.
        assertTrue(room(GameState.STATUS_PLAYING, 3, 2).isClosedToNewPlayers())
    }
}
