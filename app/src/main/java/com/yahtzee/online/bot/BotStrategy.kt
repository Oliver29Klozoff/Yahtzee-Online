package com.yahtzee.online.bot

import com.yahtzee.online.game.Category
import com.yahtzee.online.game.Scoring

/**
 * Heuristic Yahtzee strategy: strong, deterministic, no simulation. Decides which dice to
 * hold between rolls (by evaluating what each candidate keep-set is "going for") and which
 * category to lock in at the end of a turn (maximizing score now, with lookahead penalties
 * for burning high-value categories on low rolls, and awareness of the upper-section bonus).
 */
object BotStrategy {

    /**
     * Chooses which dice indices to hold before the next reroll, given the current dice and
     * which categories are still open. Reasons about the single most promising category the
     * current dice suggest, then holds whatever supports it.
     */
    fun chooseHolds(dice: List<Int>, openCategories: Set<Category>, rollsLeft: Int): Set<Int> {
        if (rollsLeft <= 0) return dice.indices.toSet()

        val counts = IntArray(7)
        dice.forEach { counts[it]++ }

        // Yahtzee/four/three-of-a-kind: hold the most common face if it appears >= 2 times
        // and that pursuit is still viable (category open or would still score well elsewhere).
        val mostCommonValue = (1..6).maxByOrNull { counts[it] } ?: 1
        val mostCommonCount = counts[mostCommonValue]

        val wantsYahtzeeOrKind = openCategories.any {
            it == Category.YAHTZEE || it == Category.FOUR_OF_A_KIND || it == Category.THREE_OF_A_KIND
        }
        if (mostCommonCount >= 3 && wantsYahtzeeOrKind) {
            return dice.indices.filter { dice[it] == mostCommonValue }.toSet()
        }

        // Straights: hold the longest run of distinct consecutive values if a straight is open.
        if (openCategories.contains(Category.LARGE_STRAIGHT) || openCategories.contains(Category.SMALL_STRAIGHT)) {
            val distinctSorted = dice.toSet().sorted()
            val bestRun = longestConsecutiveRun(distinctSorted)
            if (bestRun.size >= 3) {
                val keepValues = bestRun.toSet()
                val used = mutableSetOf<Int>()
                val indices = mutableSetOf<Int>()
                dice.forEachIndexed { i, v ->
                    if (v in keepValues && v !in used) {
                        indices.add(i)
                        used.add(v)
                    }
                }
                return indices
            }
        }

        // Full house: hold pairs/triples that contribute to a 3+2 split.
        if (openCategories.contains(Category.FULL_HOUSE)) {
            val pairOrBetter = counts.withIndex().filter { it.index in 1..6 && it.value >= 2 }
            if (pairOrBetter.size >= 1 && counts.count { it >= 2 } >= 1) {
                val keepValues = pairOrBetter.map { it.index }.toSet()
                if (keepValues.isNotEmpty()) {
                    return dice.indices.filter { dice[it] in keepValues }.toSet()
                }
            }
        }

        // Upper-section categories: if a specific number is open and appears >= 2 times, keep it
        // (chasing upper bonus is high-value in real Yahtzee strategy).
        val upperTargets = Category.UPPER.filter { it in openCategories }
            .mapNotNull { categoryToUpperValue(it) }
            .filter { counts[it] >= 2 }
        if (upperTargets.isNotEmpty()) {
            val best = upperTargets.maxByOrNull { it * counts[it] } ?: upperTargets.first()
            return dice.indices.filter { dice[it] == best }.toSet()
        }

        // Chance / fallback: hold only high-value dice (4, 5, 6) to bias the reroll upward.
        return dice.indices.filter { dice[it] >= 4 }.toSet()
    }

    /** Picks the best open category to score into, given the final dice for this turn. */
    fun chooseCategory(dice: List<Int>, openCategories: Set<Category>, upperTotalSoFar: Int): Category {
        require(openCategories.isNotEmpty())

        // Score every open category at face value.
        val scored = openCategories.associateWith { Scoring.score(it, dice) }

        // Weight: prioritize categories that are otherwise hard to fill (Yahtzee, straights,
        // full house) when they actually hit, since zeroing those out late is costly. Penalize
        // using a valuable category for a low/zero score unless nothing better is available.
        val weighted = scored.mapValues { (category, points) ->
            val isHighValueCategory = category in setOf(
                Category.YAHTZEE, Category.LARGE_STRAIGHT, Category.SMALL_STRAIGHT, Category.FULL_HOUSE
            )
            val bonusPush = if (category in Category.UPPER) {
                val value = categoryToUpperValue(category) ?: 1
                // Slight bonus weight for upper categories that help reach the 63 threshold.
                if (upperTotalSoFar < 63) points * 0.15 else 0.0
            } else 0.0

            when {
                points == 0 && isHighValueCategory -> points.toDouble() - 5.0 // avoid zeroing these unless forced
                points == 0 -> points.toDouble() - 1.0
                else -> points.toDouble() + bonusPush
            }
        }

        return weighted.entries.maxByOrNull { it.value }!!.key
    }

    private fun categoryToUpperValue(category: Category): Int? = when (category) {
        Category.ONES -> 1
        Category.TWOS -> 2
        Category.THREES -> 3
        Category.FOURS -> 4
        Category.FIVES -> 5
        Category.SIXES -> 6
        else -> null
    }

    private fun longestConsecutiveRun(sortedDistinct: List<Int>): List<Int> {
        if (sortedDistinct.isEmpty()) return emptyList()
        var best = listOf(sortedDistinct.first())
        var current = mutableListOf(sortedDistinct.first())
        for (i in 1 until sortedDistinct.size) {
            if (sortedDistinct[i] == sortedDistinct[i - 1] + 1) {
                current.add(sortedDistinct[i])
            } else {
                if (current.size > best.size) best = current.toList()
                current = mutableListOf(sortedDistinct[i])
            }
        }
        if (current.size > best.size) best = current.toList()
        return best
    }
}
