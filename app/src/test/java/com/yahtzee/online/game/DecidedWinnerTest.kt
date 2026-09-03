package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Who the game says won.
 *
 * These exist because of "Winner: ?" — a finished game, two full scorecards, 331 against 193, and
 * a dialog that could not name the winner. The room recorded one; the screen simply asked before
 * it had been written, and the dialog only fires once so it never asked again. Believing the
 * recorded id is right, but depending on it is not, because the scorecards already hold the answer.
 */
class DecidedWinnerTest {

    private fun player(id: String, ones: Int) = Player(
        id = id,
        name = id.uppercase(),
        scores = mapOf(ScoreKey.of(0, Category.ONES) to ones)
    )

    private fun room(winnerId: String, vararg people: Player) = GameState(
        status = GameState.STATUS_FINISHED,
        winnerId = winnerId,
        cardCount = 1,
        playerOrder = people.map { it.id },
        players = people.associateBy { it.id }
    )

    @Test
    fun `the recorded winner is used when it names somebody`() {
        val state = room("b", player("a", 5), player("b", 1))
        assertEquals("B", state.decidedWinner()?.name)
    }

    /** The bug: status said finished, the winner id had not landed yet. */
    @Test
    fun `an empty winner id falls back to the scorecards`() {
        val state = room("", player("a", 5), player("b", 1))
        assertEquals("A", state.decidedWinner()?.name)
    }

    /** And an id naming somebody who is not in the room is no better than an empty one. */
    @Test
    fun `a winner id nobody matches falls back too`() {
        val state = room("ghost", player("a", 1), player("b", 5))
        assertEquals("B", state.decidedWinner()?.name)
    }

    @Test
    fun `an empty room has no winner to name`() {
        assertNull(room("").decidedWinner())
    }

    /** Multi-card games are decided on everything, not on one card. */
    @Test
    fun `the fallback totals every card`() {
        val behindOnCardOne = Player(
            id = "a", name = "A",
            scores = mapOf(
                ScoreKey.of(0, Category.ONES) to 1,
                ScoreKey.of(1, Category.YAHTZEE) to 50
            )
        )
        val aheadOnCardOne = Player(
            id = "b", name = "B",
            scores = mapOf(
                ScoreKey.of(0, Category.ONES) to 5,
                ScoreKey.of(1, Category.ONES) to 2
            )
        )
        val state = GameState(
            status = GameState.STATUS_FINISHED,
            winnerId = "",
            cardCount = 2,
            playerOrder = listOf("a", "b"),
            players = mapOf("a" to behindOnCardOne, "b" to aheadOnCardOne)
        )
        assertEquals("A", state.decidedWinner()?.name)
    }
}
