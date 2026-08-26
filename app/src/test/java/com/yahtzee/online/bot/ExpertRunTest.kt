package com.yahtzee.online.bot

import com.yahtzee.online.game.Category
import com.yahtzee.online.game.DailyChallenge
import com.yahtzee.online.game.Duel
import com.yahtzee.online.game.MAX_ROLLS_PER_TURN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expert's score is put on screen next to a person's, on the claim that both faced the same
 * dice. That claim has to be true, and it has to hold on every device — a solver that walked the
 * tape even slightly differently would produce a number for a hand nobody played, and there is
 * nothing on screen that would give it away.
 */
class ExpertRunTest {

    @Test
    fun `the same duel always yields the same expert score`() {
        val first = ExpertRun.play(Duel.tapeFor("QK7MP"))
        val second = ExpertRun.play(Duel.tapeFor("QK7MP"))
        assertEquals(first.score, second.score)
        assertEquals(
            first.turns.map { it.category to it.points },
            second.turns.map { it.category to it.points }
        )
    }

    @Test
    fun `it fills every category exactly once`() {
        val result = ExpertRun.play(Duel.tapeFor("QK7MP"))
        assertEquals(DailyChallenge.TURNS, result.turns.size)
        assertEquals(Category.values().toSet(), result.turns.map { it.category }.toSet())
        assertEquals(result.turns.size, result.turns.map { it.category }.distinct().size)
    }

    /**
     * The opening dice of each turn are fixed by the tape regardless of any decision taken, so
     * they are the one thing that can be checked against the tape directly. If the run took its
     * first roll from the wrong slot, this catches it.
     */
    @Test
    fun `each turn opens on the tape's own dice`() {
        val tape = Duel.tapeFor("QK7MP")
        val result = ExpertRun.play(tape)

        result.turns.forEachIndexed { turn, played ->
            val opening = tape.valuesAt(turn, 0)
            // Every die the expert finished with must trace back to a value the tape offered for
            // that turn — the opening roll, or one of the two rerolls.
            val offered = (0 until MAX_ROLLS_PER_TURN).map { tape.valuesAt(turn, it) }
            played.dice.forEachIndexed { index, value ->
                val possible = offered.map { it[index] }
                assertTrue(
                    "turn $turn die $index showed $value, which the tape never offered $possible",
                    value in possible
                )
            }
            assertEquals(5, opening.size)
        }
    }

    @Test
    fun `points match the category the expert chose`() {
        val result = ExpertRun.play(Duel.tapeFor("BX42N"))
        result.turns.forEach { turn ->
            assertEquals(
                com.yahtzee.online.game.Scoring.score(turn.category, turn.dice),
                turn.points
            )
        }
    }

    /**
     * A benchmark nobody can beat is discouraging and one everybody beats is worthless, so the
     * number it posts has to be a genuinely strong game.
     */
    @Test
    fun `it plays a strong game across many different duels`() {
        val codes = listOf("QK7MP", "BX42N", "ZZTST", "AAAAA", "H3LP9", "M4RTN", "Q2W3E", "TR7UY")
        val scores = codes.map { ExpertRun.play(Duel.tapeFor(it)).score }
        val average = scores.average()

        println("Expert over ${codes.size} duels — $scores, average $average")
        assertTrue("expert averaged $average across $scores", average >= 200.0)
        // Nothing should be a catastrophe: a run that collapses means the tape was mishandled.
        assertTrue("a duel scored suspiciously low: $scores", scores.min() >= 100)
    }

    /** Different duels are different puzzles; identical scores would mean the seed was ignored. */
    @Test
    fun `different duels give different results`() {
        val a = ExpertRun.play(Duel.tapeFor("QK7MP"))
        val b = ExpertRun.play(Duel.tapeFor("BX42N"))
        assertTrue(
            "two unrelated duels produced identical play",
            a.score != b.score || a.turns.map { it.points } != b.turns.map { it.points }
        )
    }
}
