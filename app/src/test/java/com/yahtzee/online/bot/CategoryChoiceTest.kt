package com.yahtzee.online.bot

import com.yahtzee.online.game.Category
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the search puts a hand when two boxes pay the same.
 *
 * The points are only half of the decision. Three of a Kind and Four of a Kind both score the sum
 * of all five dice, so a four of a kind is worth exactly as much in either — and a valuation that
 * looked only at what a box pays today rated them identically and took whichever the enum listed
 * first. That is Three of a Kind, every time, which is the easier box to fill again and therefore
 * the wrong one to spend.
 */
class CategoryChoiceTest {

    private val open = setOf(Category.THREE_OF_A_KIND, Category.FOUR_OF_A_KIND)

    /** Four sixes and a five: 29 points either way, and the tie used to go the wrong way. */
    @Test
    fun `a big four of a kind goes in Four of a Kind`() {
        val chosen = ExpertStrategy.chooseCategory(listOf(6, 6, 6, 6, 5), open, upperTotal = 0)
        assertEquals(Category.FOUR_OF_A_KIND, chosen)
    }

    /** The whole range, not just the one hand that was noticed. */
    @Test
    fun `every four of a kind goes in Four of a Kind`() {
        for (face in 1..6) {
            for (spare in 1..6) {
                if (spare == face) continue
                val dice = List(4) { face } + spare
                assertEquals(
                    "four ${face}s and a $spare",
                    Category.FOUR_OF_A_KIND,
                    ExpertStrategy.chooseCategory(dice, open, upperTotal = 0)
                )
            }
        }
    }

    /**
     * A hand that is only three of a kind still goes in the box it can actually fill.
     *
     * The tie-break must not have turned into a blanket preference for the harder box — Four of a
     * Kind scores zero here, and taking zero to protect a box is worse than the bug it replaced.
     */
    @Test
    fun `three of a kind does not chase the harder box`() {
        val chosen = ExpertStrategy.chooseCategory(listOf(5, 5, 5, 2, 3), open, upperTotal = 0)
        assertEquals(Category.THREE_OF_A_KIND, chosen)
    }

    /** A Yahtzee still goes in Yahtzee, tie-break or no tie-break. */
    @Test
    fun `five of a kind goes in Yahtzee`() {
        val all = open + Category.YAHTZEE + Category.CHANCE
        val chosen = ExpertStrategy.chooseCategory(listOf(4, 4, 4, 4, 4), all, upperTotal = 0)
        assertEquals(Category.YAHTZEE, chosen)
    }

    /** With Four of a Kind already spent, the same hand has to fall back. */
    @Test
    fun `a four of a kind falls back when its box is gone`() {
        val chosen = ExpertStrategy.chooseCategory(
            listOf(6, 6, 6, 6, 5), setOf(Category.THREE_OF_A_KIND, Category.CHANCE), upperTotal = 0
        )
        assertEquals(Category.THREE_OF_A_KIND, chosen)
    }
}
