package com.yahtzee.online.bot

import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Who plays for the upper bonus and who does not.
 *
 * Everyone playing at full strength does: the expert bot, the coach, the duel's perfect player
 * and auto-play. The flag survives because the search still supports being blind to the bonus,
 * and both halves are worth pinning — a default tidied up in the wrong direction would quietly
 * hand back the handicap the expert bot used to carry.
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

    /** The expert bot plays for the bonus, and is wired to it rather than merely able to be. */
    @Test
    fun `the expert bot plays for the bonus`() {
        val open = setOf(Category.SIXES, Category.CHANCE)
        val bot = BotSkillPlay.chooseCategory(
            AppSettings.BotSkill.EXPERT, threeSixes, open, upperTotalSoFar = 45
        )
        val aware = ExpertStrategy.chooseCategory(
            threeSixes, open, upperTotal = 45, useUpperBonus = ExpertStrategy.PLAYS_FOR_UPPER_BONUS
        )
        assertEquals(aware, bot)
        assertEquals("18 in Sixes lands on 63 exactly; it should take the bonus", Category.SIXES, bot)
    }

    /** Held dice too — the two halves of a turn must not price the bonus differently. */
    @Test
    fun `the expert bot keeps for the bonus as well`() {
        val open = setOf(Category.SIXES, Category.CHANCE)
        val bot = BotSkillPlay.chooseHolds(
            AppSettings.BotSkill.EXPERT, threeSixes, open, rollsLeft = 1, upperTotalSoFar = 45
        )
        val aware = ExpertStrategy.chooseHolds(
            threeSixes, open, rollsLeft = 1, upperTotal = 45,
            useUpperBonus = ExpertStrategy.PLAYS_FOR_UPPER_BONUS
        )
        assertEquals(aware, bot)
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
