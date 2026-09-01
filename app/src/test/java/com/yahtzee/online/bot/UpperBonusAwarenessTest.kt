package com.yahtzee.online.bot

import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Who plays for the upper bonus and who does not.
 *
 * The bot opponents are deliberately blind to it; the coach, the duel's perfect player and
 * auto-play are not. Both halves are worth pinning: the handicap is easy to lose by someone
 * tidying up a default, and easy to spread by someone passing the flag along to a caller that
 * should not have it.
 */
class UpperBonusAwarenessTest {

    /** Three sixes with Sixes open: 18 upper points, three short of the 63 pace. */
    private val threeSixes = listOf(6, 6, 6, 2, 3)

    @Test
    fun `the search plays for the bonus by default`() {
        // Sixes against Chance on the same hand. With 45 upper already banked, taking 18 in
        // Sixes lands exactly on 63 and secures the bonus, which is what should tip it.
        val open = setOf(Category.SIXES, Category.CHANCE)
        val chosen = ExpertStrategy.chooseCategory(threeSixes, open, upperTotal = 45)
        assertEquals(Category.SIXES, chosen)
    }

    @Test
    fun `told to ignore it, the same position is judged differently`() {
        val open = setOf(Category.SIXES, Category.CHANCE)
        val aware = ExpertStrategy.chooseCategory(
            threeSixes, open, upperTotal = 45, useUpperBonus = ExpertStrategy.PLAYS_FOR_UPPER_BONUS
        )
        val blind = ExpertStrategy.chooseCategory(
            threeSixes, open, upperTotal = 45, useUpperBonus = ExpertStrategy.IGNORES_UPPER_BONUS
        )
        // The bonus is the only thing separating these two readings of the same hand.
        assertNotEquals("the flag changed nothing, so it is not being applied", aware, blind)
    }

    /** Whatever it decides, the blind search must not be secretly using the bonus. */
    @Test
    fun `the blind search ignores how close the bonus is`() {
        val open = setOf(Category.SIXES, Category.CHANCE)
        val nearBonus = ExpertStrategy.chooseCategory(
            threeSixes, open, upperTotal = 45, useUpperBonus = ExpertStrategy.IGNORES_UPPER_BONUS
        )
        val farFromBonus = ExpertStrategy.chooseCategory(
            threeSixes, open, upperTotal = 0, useUpperBonus = ExpertStrategy.IGNORES_UPPER_BONUS
        )
        assertEquals(nearBonus, farFromBonus)
    }

    /** The aware search does care, which is the whole point of it. */
    @Test
    fun `the aware search does care how close the bonus is`() {
        val open = setOf(Category.SIXES, Category.CHANCE, Category.FOUR_OF_A_KIND)
        val nearBonus = ExpertStrategy.chooseCategory(threeSixes, open, upperTotal = 45)
        val farFromBonus = ExpertStrategy.chooseCategory(threeSixes, open, upperTotal = 0)
        assertNotEquals(nearBonus, farFromBonus)
    }

    /** The bot opponent is wired to the blind search, not merely able to be. */
    @Test
    fun `the expert bot uses the blind search`() {
        val open = setOf(Category.SIXES, Category.CHANCE)
        val bot = BotSkillPlay.chooseCategory(
            AppSettings.BotSkill.EXPERT, threeSixes, open, upperTotalSoFar = 45
        )
        val blind = ExpertStrategy.chooseCategory(
            threeSixes, open, upperTotal = 45, useUpperBonus = ExpertStrategy.IGNORES_UPPER_BONUS
        )
        assertEquals(blind, bot)
    }

    /** The coach keeps the bonus in view — its advice would be worse without it. */
    @Test
    fun `the coach still plays for the bonus`() {
        val open = setOf(Category.SIXES, Category.CHANCE)
        // GameReview asks with the default, so the default is what has to stay aware.
        val advice = ExpertStrategy.chooseCategory(threeSixes, open, upperTotal = 45)
        assertEquals(Category.SIXES, advice)
    }
}
