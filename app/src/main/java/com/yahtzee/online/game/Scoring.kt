package com.yahtzee.online.game

object Scoring {

    fun score(category: Category, dice: List<Int>): Int {
        val counts = IntArray(7)
        dice.forEach { counts[it]++ }
        val sum = dice.sum()

        return when (category) {
            Category.ONES -> counts[1] * 1
            Category.TWOS -> counts[2] * 2
            Category.THREES -> counts[3] * 3
            Category.FOURS -> counts[4] * 4
            Category.FIVES -> counts[5] * 5
            Category.SIXES -> counts[6] * 6
            Category.THREE_OF_A_KIND -> if (counts.any { it >= 3 }) sum else 0
            Category.FOUR_OF_A_KIND -> if (counts.any { it >= 4 }) sum else 0
            Category.FULL_HOUSE -> {
                val hasThree = counts.any { it == 3 }
                val hasTwo = counts.any { it == 2 }
                val hasFive = counts.any { it == 5 }
                if ((hasThree && hasTwo) || hasFive) 25 else 0
            }
            Category.SMALL_STRAIGHT -> {
                val present = (1..6).filter { counts[it] > 0 }.toSet()
                val runs = listOf(setOf(1, 2, 3, 4), setOf(2, 3, 4, 5), setOf(3, 4, 5, 6))
                if (runs.any { present.containsAll(it) }) 30 else 0
            }
            Category.LARGE_STRAIGHT -> {
                val present = (1..6).filter { counts[it] > 0 }.toSet()
                val runs = listOf(setOf(1, 2, 3, 4, 5), setOf(2, 3, 4, 5, 6))
                if (runs.any { present == it }) 40 else 0
            }
            Category.YAHTZEE -> if (counts.any { it == 5 }) 50 else 0
            Category.CHANCE -> sum
        }
    }

    fun upperBonus(scores: Map<Category, Int>): Int {
        val upperTotal = Category.UPPER.sumOf { scores[it] ?: 0 }
        return if (upperTotal >= 63) 35 else 0
    }

    fun grandTotal(scores: Map<Category, Int>, yahtzeeBonusCount: Int = 0): Int {
        val base = scores.values.sum()
        return base + upperBonus(scores) + yahtzeeBonusCount * 100
    }
}
