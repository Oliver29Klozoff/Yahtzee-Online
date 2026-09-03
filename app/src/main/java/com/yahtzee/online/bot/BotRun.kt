package com.yahtzee.online.bot

import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.MAX_ROLLS_PER_TURN
import com.yahtzee.online.game.Scoring
import kotlin.random.Random

/**
 * A whole game of Yahtzee played by a bot, with nobody watching.
 *
 * Needed because a bracket can draw two bots against each other, and that fixture has no player to
 * sit down at it. Left unresolved it stalls the round above and the tournament stops — so the two
 * of them play it out here, in no time at all, and the bracket moves on.
 *
 * Deliberately not [ExpertRun]. That one exists to play a *given* tape perfectly, for comparing a
 * human's decisions against the best available ones, and it is expert-only. This plays its own
 * dice at whatever strength the bot is meant to have, so a field of bots is a field of different
 * opponents rather than the same one several times.
 *
 * The turn structure mirrors [LocalGameEngine]: three rolls, holds chosen between them, keeping
 * all five is a decision to stop rather than a reroll of nothing.
 */
object BotRun {

    /** One card's worth of turns — a tournament match is a single scorecard. */
    private val ALL = Category.values().toSet()

    fun play(skill: AppSettings.BotSkill, random: Random = Random.Default): Int {
        val scores = LinkedHashMap<Category, Int>()
        var upperTotal = 0
        var yahtzeeBonuses = 0

        repeat(ALL.size) {
            var dice = List(5) { random.nextInt(1, 7) }

            for (rollIndex in 1 until MAX_ROLLS_PER_TURN) {
                val open = ALL - scores.keys
                val holds = BotSkillPlay.chooseHolds(
                    skill, dice, open, MAX_ROLLS_PER_TURN - rollIndex, upperTotal, random
                )
                if (holds.size == 5) break
                dice = dice.mapIndexed { i, value ->
                    if (i in holds) value else random.nextInt(1, 7)
                }
            }

            val open = ALL - scores.keys
            val category = BotSkillPlay.chooseCategory(skill, dice, open, upperTotal, random)
            val points = Scoring.score(category, dice)

            // An extra Yahtzee once the box is already filled with fifty is worth a bonus, the
            // same as it is in the real game. Left out, a bot would be quietly worse than the one
            // a person plays against, and a bracket is meant to be the same game throughout.
            if (dice.distinct().size == 1 && scores[Category.YAHTZEE] == 50 &&
                category != Category.YAHTZEE
            ) {
                yahtzeeBonuses++
            }

            scores[category] = points
            if (category in Category.UPPER) upperTotal += points
        }

        return Scoring.grandTotal(scores, yahtzeeBonuses)
    }
}
