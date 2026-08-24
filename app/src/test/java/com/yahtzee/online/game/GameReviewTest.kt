package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The review tells the player they were wrong, so it had better be right.
 *
 * The case worth guarding is the one that is easy to get backwards: taking fewer points on
 * purpose. A review that flags protecting a box as a mistake teaches the opposite of the lesson,
 * and would be worse than no review at all.
 */
class GameReviewTest {

    private fun turn(
        dice: List<Int>,
        chosen: Category,
        open: Set<Category>,
        upperTotal: Int = 0
    ) = GameReview.Turn(
        dice = dice,
        card = 0,
        chosen = chosen,
        points = Scoring.score(chosen, dice),
        open = open,
        upperTotal = upperTotal
    )

    @Test
    fun `the obvious best box is not second-guessed`() {
        // Five sixes with the Yahtzee box open: there is nothing to discuss.
        val reviewed = turn(
            dice = listOf(6, 6, 6, 6, 6),
            chosen = Category.YAHTZEE,
            open = setOf(Category.YAHTZEE, Category.SIXES, Category.CHANCE)
        )
        assertFalse(reviewed.differs)
        assertEquals(0, reviewed.missed)
    }

    @Test
    fun `throwing points away is caught and priced`() {
        // 5-5-5-5-1 dropped into Ones for nothing, with Fives sitting open at 20.
        val reviewed = turn(
            dice = listOf(5, 5, 5, 5, 1),
            chosen = Category.ONES,
            open = setOf(Category.ONES, Category.FIVES)
        )
        assertTrue(reviewed.differs)
        assertEquals(Category.FIVES, reviewed.better)
        assertEquals(20, reviewed.betterPoints)
        assertEquals(19, reviewed.missed) // Ones scored 1, Fives would have scored 20.
    }

    @Test
    fun `protecting a box is never counted as points lost`() {
        // Whatever the strategy prefers here, the review must never report a NEGATIVE loss or
        // punish a choice that scored fewer points on purpose. missed is floored at zero for
        // exactly this reason.
        val open = Category.values().toSet()
        for (a in 1..6) for (b in a..6) for (c in b..6) for (d in c..6) for (e in d..6) {
            val dice = listOf(a, b, c, d, e)
            for (category in open) {
                val reviewed = turn(dice, category, open)
                assertTrue(
                    "missed went negative for $dice into $category",
                    reviewed.missed >= 0
                )
                // A turn scoring at least what the recommendation scores can never be a loss.
                if (reviewed.points >= reviewed.betterPoints) {
                    assertEquals(
                        "$dice into $category was priced as a loss despite scoring no fewer",
                        0,
                        reviewed.missed
                    )
                }
            }
        }
    }

    @Test
    fun `a swap that costs nothing now is still reported as a difference`() {
        // Both boxes score zero, but spending Yahtzee on a hand that is not one is the mistake —
        // and it has no immediate price, which is why differs exists separately from missed.
        val reviewed = turn(
            dice = listOf(2, 2, 3, 4, 6),
            chosen = Category.YAHTZEE,
            open = setOf(Category.ONES, Category.YAHTZEE)
        )
        assertEquals(0, reviewed.points)
        assertTrue("burning Yahtzee for nothing should be flagged", reviewed.differs)
        assertEquals("it cost no points, so it must not be priced", 0, reviewed.missed)
    }

    @Test
    fun `the only open box is always the right one`() {
        Category.values().forEach { category ->
            val reviewed = turn(listOf(1, 2, 3, 4, 5), category, setOf(category))
            assertFalse(reviewed.differs)
            assertEquals(0, reviewed.missed)
        }
    }
}
