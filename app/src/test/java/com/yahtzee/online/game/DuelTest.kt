package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The duel's whole promise is that both players faced the same dice. If that is not exactly true
 * the feature is not merely buggy, it is dishonest — it would present a comparison between two
 * scores that were never comparable, and nothing on screen would give it away.
 */
class DuelTest {

    private fun allRolls(tape: DiceTape): List<List<Int>> =
        (0 until DailyChallenge.TURNS).flatMap { turn ->
            (0 until MAX_ROLLS_PER_TURN).map { roll -> tape.valuesAt(turn, roll) }
        }

    @Test
    fun `same code deals the same dice every time`() {
        val first = allRolls(Duel.tapeFor("QK7MP"))
        val second = allRolls(Duel.tapeFor("QK7MP"))
        assertEquals(first, second)
    }

    @Test
    fun `different codes deal different dice`() {
        assertNotEquals(allRolls(Duel.tapeFor("QK7MP")), allRolls(Duel.tapeFor("QK7MQ")))
    }

    /**
     * Neighbouring codes differ by one character, so a weak hash would hand them near-identical
     * tapes — two duels running side by side would visibly be the same puzzle.
     */
    @Test
    fun `codes one character apart are not near-identical`() {
        val a = allRolls(Duel.tapeFor("AAAAA")).flatten()
        val b = allRolls(Duel.tapeFor("AAAAB")).flatten()
        val shared = a.zip(b).count { (x, y) -> x == y }
        // Two unrelated tapes agree on about a sixth of their dice by chance.
        assertTrue("tapes are suspiciously alike: $shared of ${a.size}", shared < a.size / 3)
    }

    /**
     * The reason a duel does not simply reuse the day's tape: anyone who had already played the
     * daily would know the dice in advance.
     */
    @Test
    fun `a duel never deals the same dice as a daily challenge of the same name`() {
        val day = "2026-08-25"
        assertNotEquals(
            allRolls(DailyChallenge.tapeFor(day)),
            allRolls(Duel.tapeFor(day))
        )
    }

    @Test
    fun `generated codes are the right shape and avoid ambiguous characters`() {
        repeat(200) {
            val code = Duel.generateCode()
            assertEquals(5, code.length)
            code.forEach { character ->
                assertFalse("ambiguous character in $code", character in "IO01")
                assertTrue("unexpected character in $code", character.isLetterOrDigit())
            }
        }
    }

    private fun player(id: String, score: Int?) =
        DuelPlayer(id = id, name = id, score = score, finishedAt = 0L)

    @Test
    fun `no winner while anyone is still playing`() {
        val state = DuelState("ABCDE", "a", listOf(player("a", 210), player("b", null)))
        assertNull(state.winner)
        assertFalse(state.isSettled)
        assertEquals(listOf("a"), state.standings.map { it.id })
        assertEquals(listOf("b"), state.waiting.map { it.id })
    }

    /** On a duel of two, the first to finish would otherwise always be shown as winning. */
    @Test
    fun `a lone finisher is not a winner`() {
        val state = DuelState("ABCDE", "a", listOf(player("a", 210)))
        assertNull(state.winner)
    }

    @Test
    fun `highest score wins once everyone has played`() {
        val state = DuelState(
            "ABCDE", "a",
            listOf(player("a", 188), player("b", 244), player("c", 201))
        )
        assertTrue(state.isSettled)
        assertEquals("b", state.winner?.id)
        assertEquals(listOf("b", "c", "a"), state.standings.map { it.id })
    }

    /** A zero is a real result, and must not read as "has not played yet". */
    @Test
    fun `a score of zero counts as having played`() {
        val state = DuelState("ABCDE", "a", listOf(player("a", 0), player("b", 5)))
        assertTrue(state.isSettled)
        assertTrue(state.waiting.isEmpty())
        assertEquals("b", state.winner?.id)
    }

    @Test
    fun `a draw leaves more than one player on the top score`() {
        val state = DuelState("ABCDE", "a", listOf(player("a", 200), player("b", 200)))
        val winner = state.winner
        assertEquals(200, winner?.score)
        assertEquals(2, state.standings.count { it.score == winner?.score })
    }
}
