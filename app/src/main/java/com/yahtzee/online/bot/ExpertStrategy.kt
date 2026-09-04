package com.yahtzee.online.bot

import com.yahtzee.online.game.Category
import com.yahtzee.online.game.Scoring

/**
 * A bot that works out what to keep instead of guessing.
 *
 * [BotStrategy] decides by rule: it picks the one category the dice most look like, holds
 * whatever supports it, and never reconsiders. That is why its keeps look wrong — it will chase
 * a third of a kind past a four-card straight, or hold a lone pair when throwing all five back
 * is worth more, because nothing in it ever compares the two.
 *
 * This searches instead. Every one of the 32 ways to hold is evaluated by averaging over what
 * the reroll could actually bring, and the best average wins. With two rolls left it does the
 * same thing a second time, so the value of a keep accounts for the roll after next as well.
 *
 * That is affordable because five dice have only 252 distinct outcomes once order stops
 * mattering, which it does — a hand is a multiset. Positions are indexed by their sorted faces
 * and the one-roll-left values are computed once per turn and reused across all 32 candidate
 * keeps, so a decision costs a few hundred thousand array reads rather than a simulation.
 */
object ExpertStrategy {

    private const val FACES = 6
    private const val DICE = 5

    /** Every sorted hand encoded in base 6. Sparse — only the 252 sorted ones are ever filled. */
    private const val STATES = 7776

    /**
     * Score a category is worth aiming at, under good play. Used to price what closing a box
     * costs: taking 8 in Fours is not a gain of 8, it is a loss of what Fours was worth.
     */
    /**
     * What a competent player tends to make of each box over a game.
     *
     * Exposed so the projection can use the same numbers the solver plans against, rather than a
     * second set that could drift away from them and quietly disagree with the coach.
     */
    fun typicalFor(category: Category): Float = TYPICAL[category] ?: 0f

    private val TYPICAL = mapOf(
        Category.ONES to 1.9f,
        Category.TWOS to 5.3f,
        Category.THREES to 8.6f,
        Category.FOURS to 12.2f,
        Category.FIVES to 15.7f,
        Category.SIXES to 19.2f,
        Category.THREE_OF_A_KIND to 22.6f,
        Category.FOUR_OF_A_KIND to 13.1f,
        Category.FULL_HOUSE to 22.6f,
        Category.SMALL_STRAIGHT to 29.5f,
        Category.LARGE_STRAIGHT to 32.7f,
        Category.YAHTZEE to 16.9f,
        Category.CHANCE to 22.0f
    )

    /**
     * Roughly how often a box can be filled again in a later turn, playing for it.
     *
     * Not the same thing as [TYPICAL], which is what a box pays when it is filled — Chance pays
     * less than Three of a Kind on average and is still the box you can always come back to. What
     * this measures is whether leaving a box open strands it, which is the question that decides
     * where to put a hand two boxes value equally.
     */
    private val REFILL = mapOf(
        Category.CHANCE to 1.0f,
        Category.ONES to 0.8f,
        Category.TWOS to 0.8f,
        Category.THREES to 0.8f,
        Category.FOURS to 0.8f,
        Category.FIVES to 0.8f,
        Category.SIXES to 0.8f,
        Category.THREE_OF_A_KIND to 0.73f,
        Category.SMALL_STRAIGHT to 0.62f,
        Category.FULL_HOUSE to 0.34f,
        Category.FOUR_OF_A_KIND to 0.31f,
        Category.LARGE_STRAIGHT to 0.23f,
        Category.YAHTZEE to 0.05f
    )

    /** How much of the shortfall against [TYPICAL] counts against closing a box early. */
    private const val WASTE_WEIGHT = 0.45f

    /** Weight on beating the 63 pace — three of a face per box — while the bonus is still live. */
    private const val UPPER_PACE_WEIGHT = 0.7f

    /** Paid once for the roll that actually secures the upper bonus. */
    private const val UPPER_BONUS_SECURED = 22f

    /**
     * Whether a caller wants the search to play for the upper bonus.
     *
     * Every caller does now. The 35 points are the largest single swing on the card, and a player
     * who does not steer the upper section toward 63 is not playing the same game as one who does
     * — over 600 games the search averages 227.6 with the bonus in view against 223.2 without.
     *
     * Expert bots ignored it for a while, as a deliberate handicap. That is over: expert is meant
     * to be the level that plays properly, and a bot dropping two fives into Fives while 63 is
     * still live reads as a mistake rather than as a difficulty setting. The easier levels are
     * where the handicap belongs, and they have their own.
     */
    const val PLAYS_FOR_UPPER_BONUS = true
    const val IGNORES_UPPER_BONUS = false

    /**
     * Which dice to keep. [rollsLeft] counts rolls still to come, so 2 at the start of a turn
     * that has rolled once.
     */
    fun chooseHolds(
        dice: List<Int>,
        openCategories: Set<Category>,
        rollsLeft: Int,
        upperTotal: Int,
        useUpperBonus: Boolean = PLAYS_FOR_UPPER_BONUS
    ): Set<Int> {
        if (rollsLeft <= 0 || openCategories.isEmpty() || dice.size != DICE) {
            return dice.indices.toSet()
        }

        val tables = tablesFor(openCategories, upperTotal, useUpperBonus)
        // With two rolls to come, a keep is worth what the position after the next roll is worth
        // — which is itself a keep decision. Evaluating that first is what lets the bot hold for
        // a draw that only pays off on the roll after next.
        val table = if (rollsLeft >= 2) tables.oneRoll else tables.terminal

        var bestMask = 0
        var bestValue = -Float.MAX_VALUE
        for (mask in 0 until (1 shl DICE)) {
            val value = expectedValue(dice, mask, table)
            if (value > bestValue) {
                bestValue = value
                bestMask = mask
            }
        }
        return (0 until DICE).filter { (bestMask shr it) and 1 == 1 }.toSet()
    }

    /** Where to score a finished hand, priced the same way the search prices it. */
    fun chooseCategory(
        dice: List<Int>,
        openCategories: Set<Category>,
        upperTotal: Int,
        useUpperBonus: Boolean = PLAYS_FOR_UPPER_BONUS
    ): Category = openCategories.maxWithOrNull(
        // Value first, then the box that is hardest to fill again.
        //
        // The second half is not a nicety. Once a hand clears the typical score of two boxes the
        // shortfall term is zero for both, so they value identically and the winner was whichever
        // the enum happened to list first — which is how four sixes went into Three of a Kind and
        // left Four of a Kind open for a hand that may never come. Both pay the same today; the
        // difference is entirely in what is left behind, and what should be left behind is the box
        // most likely to come round again.
        compareBy(
            { adjustedScore(it, dice, upperTotal, useUpperBonus) },
            { -(REFILL[it] ?: 0.5f) }
        )
    ) ?: openCategories.first()

    private class Tables(val terminal: FloatArray, val oneRoll: FloatArray)

    /**
     * The position the cached tables were built for.
     *
     * Carries whether the bonus was in view as well as the open boxes and the upper total: the
     * two answers differ, and a key that left it out would hand a bot the coach's tables, or
     * worse, hand the coach a bot's.
     */
    private var cachedKey: Triple<Set<Category>, Int, Boolean>? = null
    private var cachedTables: Tables? = null

    /**
     * The value tables for this position, reused while the position holds.
     *
     * Worth caching because a single roll asks twice — once to decide whether the bot is done
     * rolling, once to actually pick the keep — and both rolls of a turn share the same open
     * boxes and upper total. One entry is enough: the question only changes at the turn
     * boundary, and by then the old answer is of no use anyway.
     */
    @Synchronized
    private fun tablesFor(
        openCategories: Set<Category>,
        upperTotal: Int,
        useUpperBonus: Boolean
    ): Tables {
        val key = Triple(openCategories, upperTotal, useUpperBonus)
        cachedTables?.let { if (cachedKey == key) return it }

        val terminal = terminalValues(openCategories, upperTotal, useUpperBonus)
        val tables = Tables(terminal, oneRollValues(terminal))
        cachedKey = key
        cachedTables = tables
        return tables
    }

    /** The best a hand can be cashed in for right now, by the valuation above. */
    private fun terminalValues(
        openCategories: Set<Category>,
        upperTotal: Int,
        useUpperBonus: Boolean
    ): FloatArray {
        val table = FloatArray(STATES)
        forEachHand { counts, index ->
            val hand = toHand(counts)
            var best = -Float.MAX_VALUE
            for (category in openCategories) {
                val value = adjustedScore(category, hand, upperTotal, useUpperBonus)
                if (value > best) best = value
            }
            table[index] = best
        }
        return table
    }

    /** For every hand, the best average it can reach with one reroll of any subset. */
    private fun oneRollValues(terminal: FloatArray): FloatArray {
        val table = FloatArray(STATES)
        forEachHand { counts, index ->
            val hand = toHand(counts)
            var best = -Float.MAX_VALUE
            for (mask in 0 until (1 shl DICE)) {
                val value = expectedValue(hand, mask, terminal)
                if (value > best) best = value
            }
            table[index] = best
        }
        return table
    }

    /**
     * What holding [mask] is worth: the average of [table] over every hand the reroll can
     * produce.
     *
     * Outcomes are enumerated as multisets carrying their own multinomial weight rather than as
     * ordered rolls. Both give the same average, but there are 252 multisets against 7776
     * ordered rolls for five dice, and the saving is what makes the two-deep search cheap.
     */
    private fun expectedValue(dice: List<Int>, mask: Int, table: FloatArray): Float {
        val counts = IntArray(FACES + 1)
        var rerolling = 0
        for (i in dice.indices) {
            if ((mask shr i) and 1 == 1) counts[dice[i]]++ else rerolling++
        }
        if (rerolling == 0) return table[encode(counts)]

        var total = 0.0
        val added = IntArray(FACES + 1)

        fun walk(face: Int, remaining: Int, ways: Double) {
            if (face > FACES) {
                if (remaining == 0) {
                    for (f in 1..FACES) counts[f] += added[f]
                    total += ways * table[encode(counts)]
                    for (f in 1..FACES) counts[f] -= added[f]
                }
                return
            }
            for (take in 0..remaining) {
                added[face] = take
                walk(face + 1, remaining - take, ways * binomial(remaining, take))
                added[face] = 0
            }
        }
        walk(1, rerolling, 1.0)

        var outcomes = 1.0
        repeat(rerolling) { outcomes *= FACES }
        return (total / outcomes).toFloat()
    }

    /**
     * What a category is really worth for this hand: the points, less what closing the box gives
     * up, plus what it does for the upper bonus.
     *
     * The shortfall term is what stops the bot dropping a weak roll into Yahtzee or a straight
     * simply because those pay the most when they land; the pace term is what makes it prefer
     * four sixes to four fives while 63 is still reachable.
     */
    private fun adjustedScore(
        category: Category,
        dice: List<Int>,
        upperTotal: Int,
        useUpperBonus: Boolean
    ): Float {
        val points = Scoring.score(category, dice).toFloat()
        val typical = TYPICAL[category] ?: 0f
        var value = points - WASTE_WEIGHT * maxOf(0f, typical - points)

        if (useUpperBonus && category in Category.UPPER) {
            val face = Category.UPPER.indexOf(category) + 1
            if (upperTotal < 63) {
                value += UPPER_PACE_WEIGHT * (points - 3f * face)
                if (upperTotal + points >= 63) value += UPPER_BONUS_SECURED
            }
        }
        return value
    }

    /** Visits every sorted hand exactly once, as face counts and its encoded index. */
    private inline fun forEachHand(action: (counts: IntArray, index: Int) -> Unit) {
        val counts = IntArray(FACES + 1)
        for (a in 1..FACES) for (b in a..FACES) for (c in b..FACES) for (d in c..FACES) for (e in d..FACES) {
            java.util.Arrays.fill(counts, 0)
            counts[a]++; counts[b]++; counts[c]++; counts[d]++; counts[e]++
            action(counts, encode(counts))
        }
    }

    /** Sorted faces packed base-6, so hands differing only in order share an entry. */
    private fun encode(counts: IntArray): Int {
        var index = 0
        for (face in 1..FACES) {
            repeat(counts[face]) { index = index * FACES + (face - 1) }
        }
        return index
    }

    private fun toHand(counts: IntArray): List<Int> {
        val hand = ArrayList<Int>(DICE)
        for (face in 1..FACES) repeat(counts[face]) { hand.add(face) }
        return hand
    }

    private fun binomial(n: Int, k: Int): Double {
        if (k < 0 || k > n) return 0.0
        var result = 1.0
        for (i in 0 until k) result = result * (n - i) / (i + 1)
        return result
    }
}
