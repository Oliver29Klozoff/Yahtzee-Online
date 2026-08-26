package com.yahtzee.online.bot

import com.yahtzee.online.game.Category
import com.yahtzee.online.game.DailyChallenge
import com.yahtzee.online.game.DiceTape
import com.yahtzee.online.game.MAX_ROLLS_PER_TURN
import com.yahtzee.online.game.Scoring

/**
 * Plays a whole card against a fixed tape with [ExpertStrategy], and reports what it made of it.
 *
 * This is what turns the solver into an opponent. Every duel can carry the score a perfect player
 * would have posted on the very same dice, which is a far more useful number than a leaderboard
 * position: it says whether 243 was a good game or a wasted hand, and no amount of ranking against
 * other people can answer that.
 *
 * ## It has to consume the tape exactly as the player does
 *
 * The whole claim being made on screen is "same dice". If this walked the tape differently — took
 * its rolls from the wrong slots, or skipped one — it would be scoring a hand nobody played, and
 * the comparison would be a fabrication that looks entirely convincing. So the loop below mirrors
 * [com.yahtzee.online.bot.LocalGameEngine] precisely: a turn's rolls come from slots 0, 1 and 2 of
 * that turn, dice are addressed by position, and holding one keeps the value it already had.
 *
 * Standing pat is safe, for the same reason: the tape is positional rather than sequential, so
 * declining a reroll leaves everything after it exactly where it was.
 */
object ExpertRun {

    /** The result of one perfect run, and the turn-by-turn detail behind it. */
    data class Result(val score: Int, val turns: List<Turn>)

    data class Turn(val dice: List<Int>, val category: Category, val points: Int)

    fun play(tape: DiceTape): Result {
        val scores = LinkedHashMap<Category, Int>()
        val turns = mutableListOf<Turn>()
        var upperTotal = 0
        var yahtzeeBonusCount = 0
        var dice = List(5) { 1 }

        for (turn in 0 until DailyChallenge.TURNS) {
            // A turn always opens with all five dice off the tape: holds are cleared between
            // turns, so nothing carries over from the last one.
            dice = tape.apply(turn, 0, dice, emptySet())

            for (rollIndex in 1 until MAX_ROLLS_PER_TURN) {
                val open = Category.values().toSet() - scores.keys
                val rollsLeft = MAX_ROLLS_PER_TURN - rollIndex
                val holds = ExpertStrategy.chooseHolds(dice, open, rollsLeft, upperTotal)
                // Keeping all five is a decision to stop, not a reroll of nothing.
                if (holds.size == 5) break
                dice = tape.apply(turn, rollIndex, dice, holds)
            }

            val open = Category.values().toSet() - scores.keys
            val category = ExpertStrategy.chooseCategory(dice, open, upperTotal)
            val points = Scoring.score(category, dice)

            // The same extra-Yahtzee rule the real game applies. Left out, the expert would be
            // quietly understated against any player who rolled a second Yahtzee — and being
            // understated is worse than being wrong, because it flatters the player.
            if (category != Category.YAHTZEE &&
                dice.distinct().size == 1 &&
                scores[Category.YAHTZEE] == YAHTZEE_POINTS
            ) {
                yahtzeeBonusCount++
            }

            scores[category] = points
            if (category in Category.UPPER) upperTotal += points
            turns += Turn(dice, category, points)
        }

        return Result(Scoring.grandTotal(scores, yahtzeeBonusCount), turns)
    }

    private const val YAHTZEE_POINTS = 50
}
