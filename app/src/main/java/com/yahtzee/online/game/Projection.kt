package com.yahtzee.online.game

import com.yahtzee.online.bot.ExpertStrategy
import kotlin.math.roundToInt

/**
 * Where a card is heading, given what has been filled in so far.
 *
 * A scorecard tells you what you have. It says nothing about whether you are having a good game,
 * which is the thing you actually want to know while deciding what to do with a roll — 120 with
 * the whole lower section open is a fine position and 120 with only Ones left is a poor one, and
 * the card presents both identically.
 *
 * ## What the number is, and what it is not
 *
 * It is what a decent player tends to finish on from here: the points already banked, plus the
 * usual return on each box still open, plus the upper bonus if the pace suggests it will land.
 * The per-box figures are the solver's own, so the projection and the coach cannot drift apart.
 *
 * It is deliberately NOT an exact expectation. A true one would have to search every remaining
 * position, which is far more than a screen redrawn on every held die can afford, and it would
 * be a false precision anyway — nobody plays the rest of a game perfectly. Treated as "on pace
 * for", it is honest; read as a promise, it would not be.
 */
object Projection {

    /** Points on the table for the upper bonus, and the total needed to earn it. */
    private const val UPPER_TARGET = 63
    private const val UPPER_BONUS = 35

    /**
     * Projected final score for one card.
     *
     * The upper bonus is counted in proportion to how close the pace is rather than as all or
     * nothing: with three boxes still open and a healthy total it is likelier than not, and
     * calling it either 0 or 35 would make the projection lurch by 35 points on a single score.
     */
    fun forCard(scores: Map<Category, Int>): Int {
        val banked = scores.values.sum()
        val open = Category.values().filterNot { scores.containsKey(it) }
        val expected = open.sumOf { ExpertStrategy.typicalFor(it).toDouble() }

        return (banked + expected + upperBonusOutlook(scores)).roundToInt()
    }

    /** The projection across every card, plus whatever Yahtzee bonuses are already banked. */
    fun forPlayer(player: Player, cardCount: Int): Int {
        val cards = cardCount.coerceAtLeast(1)
        val perCard = (0 until cards).sumOf { forCard(player.scoresForCard(it)) }
        return perCard + player.yahtzeeBonusCount * 100
    }

    /**
     * The share of the upper bonus this card looks like earning.
     *
     * Already secured, it is worth all of it. Already impossible — every upper box filled and
     * short — it is worth nothing. In between it is scaled by how much of the remaining gap the
     * open boxes can be expected to cover, which moves smoothly as the card fills instead of
     * flipping between two answers.
     */
    private fun upperBonusOutlook(scores: Map<Category, Int>): Double {
        val upperTotal = Category.UPPER.sumOf { scores[it] ?: 0 }
        if (upperTotal >= UPPER_TARGET) return UPPER_BONUS.toDouble()

        val openUpper = Category.UPPER.filterNot { scores.containsKey(it) }
        if (openUpper.isEmpty()) return 0.0

        val expectedMore = openUpper.sumOf { ExpertStrategy.typicalFor(it).toDouble() }
        val shortfall = UPPER_TARGET - upperTotal
        if (expectedMore <= 0.0) return 0.0

        // Comfortably on pace counts as good as banked; well behind counts as gone.
        val ratio = (expectedMore / shortfall).coerceIn(0.0, 1.0)
        return UPPER_BONUS * ratio * ratio
    }
}
