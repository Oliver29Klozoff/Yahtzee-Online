package com.yahtzee.online.game

enum class Category(val label: String, val hint: String) {
    ONES("Ones", "Sum of 1s"),
    TWOS("Twos", "Sum of 2s"),
    THREES("Threes", "Sum of 3s"),
    FOURS("Fours", "Sum of 4s"),
    FIVES("Fives", "Sum of 5s"),
    SIXES("Sixes", "Sum of 6s"),
    THREE_OF_A_KIND("3 of a Kind", "Sum of all dice"),
    FOUR_OF_A_KIND("4 of a Kind", "Sum of all dice"),
    FULL_HOUSE("Full House", "25 points"),
    SMALL_STRAIGHT("Small Straight", "30 points"),
    LARGE_STRAIGHT("Large Straight", "40 points"),
    YAHTZEE("Yahtzee", "50 points"),
    CHANCE("Chance", "Sum of all dice");

    companion object {
        val UPPER = listOf(ONES, TWOS, THREES, FOURS, FIVES, SIXES)
        val LOWER = listOf(THREE_OF_A_KIND, FOUR_OF_A_KIND, FULL_HOUSE, SMALL_STRAIGHT, LARGE_STRAIGHT, YAHTZEE, CHANCE)
    }
}
