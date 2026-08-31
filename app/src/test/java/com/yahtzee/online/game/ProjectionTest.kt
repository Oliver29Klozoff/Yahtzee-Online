package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTest {

    /** A finished card is worth exactly what it says — nothing left to project. */
    @Test
    fun `a full card projects its own total`() {
        val scores = Category.values().associateWith { 10 }
        // 13 boxes of 10, upper section 60 so no bonus.
        assertEquals(Scoring.grandTotal(scores), Projection.forCard(scores))
    }

    /** An empty card should project a plausible game rather than zero. */
    @Test
    fun `an empty card projects a whole game`() {
        val projected = Projection.forCard(emptyMap())
        assertTrue("projected $projected", projected in 150..280)
    }

    @Test
    fun `banked points always count`() {
        val withYahtzee = Projection.forCard(mapOf(Category.YAHTZEE to 50))
        val withScratch = Projection.forCard(mapOf(Category.YAHTZEE to 0))
        assertEquals(50, withYahtzee - withScratch)
    }

    /** Filling boxes well should raise the projection; filling them badly should lower it. */
    @Test
    fun `a strong start projects higher than a weak one`() {
        val strong = Projection.forCard(
            mapOf(Category.SIXES to 30, Category.LARGE_STRAIGHT to 40)
        )
        val weak = Projection.forCard(
            mapOf(Category.SIXES to 6, Category.LARGE_STRAIGHT to 0)
        )
        assertTrue("strong $strong should beat weak $weak", strong > weak)
    }

    /** A secured upper bonus is worth its full 35 on top of the boxes. */
    @Test
    fun `a secured upper bonus is counted in full`() {
        // 105 in the upper section: comfortably past 63, so the bonus is banked.
        val secured = mapOf(
            Category.ONES to 5, Category.TWOS to 10, Category.THREES to 15,
            Category.FOURS to 20, Category.FIVES to 25, Category.SIXES to 30
        )
        // 45 with every upper box closed: the bonus can no longer be reached.
        val missed = mapOf(
            Category.ONES to 0, Category.TWOS to 0, Category.THREES to 0,
            Category.FOURS to 20, Category.FIVES to 25, Category.SIXES to 0
        )

        val boxDifference = secured.values.sum() - missed.values.sum()
        val difference = Projection.forCard(secured) - Projection.forCard(missed)
        assertEquals(
            "the gap should be the boxes plus the whole bonus",
            (boxDifference + 35).toDouble(),
            difference.toDouble(),
            1.0
        )
    }

    /** With the upper section closed and short, the bonus is gone and must not be counted. */
    @Test
    fun `a missed upper bonus is not counted`() {
        val missed = mapOf(
            Category.ONES to 0, Category.TWOS to 0, Category.THREES to 0,
            Category.FOURS to 0, Category.FIVES to 0, Category.SIXES to 0
        )
        val open = Category.values().filterNot { missed.containsKey(it) }
        val expectedLower = open.sumOf { com.yahtzee.online.bot.ExpertStrategy.typicalFor(it).toDouble() }
        assertEquals(expectedLower, Projection.forCard(missed).toDouble(), 1.0)
    }

    /** Multi-card games project every card, and carry banked Yahtzee bonuses. */
    @Test
    fun `a player across several cards adds up`() {
        val player = Player(
            id = "p",
            name = "P",
            joinedAt = 0L,
            scores = mapOf(
                ScoreKey.of(0, Category.YAHTZEE) to 50,
                ScoreKey.of(1, Category.YAHTZEE) to 50
            ),
            yahtzeeBonusCount = 2
        )
        val projected = Projection.forPlayer(player, cardCount = 2)
        val oneCard = Projection.forCard(mapOf(Category.YAHTZEE to 50))
        assertEquals(oneCard * 2 + 200, projected)
    }
}
