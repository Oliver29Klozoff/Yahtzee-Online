package com.yahtzee.online.bot

import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.Scoring
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Plays each difficulty out over a run of solo games and compares what they actually score.
 *
 * "The bot is stronger now" is a claim about outcomes, and the only way to know is to let it
 * play. Every level sees the same dice — the seed is reset per level — so a difference in the
 * averages is a difference in the decisions and not in the luck.
 */
class BotStrengthTest {

    private companion object {
        const val GAMES = 120
        const val SEED = 20260824L
    }

    /** One complete solitaire game on a single card. */
    private fun playGame(skill: AppSettings.BotSkill, random: Random): Int {
        val scores = mutableMapOf<Category, Int>()
        var upperTotal = 0

        repeat(Category.values().size) {
            var dice = List(5) { random.nextInt(1, 7) }

            // Two rerolls available after the opening roll.
            for (roll in 1..2) {
                val open = Category.values().toSet() - scores.keys
                val holds = BotSkillPlay.chooseHolds(skill, dice, open, 3 - roll, upperTotal, random)
                if (holds.size == 5) break
                dice = dice.mapIndexed { i, value ->
                    if (i in holds) value else random.nextInt(1, 7)
                }
            }

            val open = Category.values().toSet() - scores.keys
            val category = BotSkillPlay.chooseCategory(skill, dice, open, upperTotal, random)
            val points = Scoring.score(category, dice)
            scores[category] = points
            if (category in Category.UPPER) upperTotal += points
        }
        return Scoring.grandTotal(scores)
    }

    private fun average(skill: AppSettings.BotSkill): Double {
        val random = Random(SEED)
        var total = 0L
        repeat(GAMES) { total += playGame(skill, random) }
        return total.toDouble() / GAMES
    }

    @Test
    fun `expert outscores the heuristic levels`() {
        val easy = average(AppSettings.BotSkill.EASY)
        val normal = average(AppSettings.BotSkill.NORMAL)
        val hard = average(AppSettings.BotSkill.HARD)
        val expert = average(AppSettings.BotSkill.EXPERT)

        println("Average over $GAMES games — easy $easy, normal $normal, hard $hard, expert $expert")

        // The ladder has to be a ladder: each level should beat the one below it.
        assertTrue("easy $easy should trail normal $normal", easy < normal)
        assertTrue("normal $normal should trail hard $hard", normal < hard)
        assertTrue("hard $hard should trail expert $expert", hard < expert)
    }

    @Test
    fun `expert plays a respectable game of yahtzee`() {
        val expert = average(AppSettings.BotSkill.EXPERT)
        // A competent human averages around 200-220 on a single card. Anything below this is not
        // an opponent worth the name, whatever it does to the weaker levels.
        assertTrue("expert averaged $expert", expert >= 200.0)
    }

    @Test
    fun `a decision is fast enough to sit inside a turn`() {
        val random = Random(SEED)
        val open = Category.values().toSet()
        val dice = List(5) { random.nextInt(1, 7) }

        // Warm up, so this measures the work and not the first-call class loading.
        ExpertStrategy.chooseHolds(dice, open, 2, 0)

        val started = System.nanoTime()
        repeat(20) { attempt ->
            // Vary the position so the cache cannot answer every call for free.
            ExpertStrategy.chooseHolds(dice, open, 2, attempt)
        }
        val millisEach = (System.nanoTime() - started) / 20 / 1_000_000.0
        println("Expert hold decision: ${millisEach}ms")
        assertTrue("a decision took ${millisEach}ms", millisEach < 250.0)
    }
}
