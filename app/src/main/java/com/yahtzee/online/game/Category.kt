package com.yahtzee.online.game

enum class Category(val label: String) {
    ONES("Ones"),
    TWOS("Twos"),
    THREES("Threes"),
    FOURS("Fours"),
    FIVES("Fives"),
    SIXES("Sixes"),
    THREE_OF_A_KIND("3 of a Kind"),
    FOUR_OF_A_KIND("4 of a Kind"),
    FULL_HOUSE("Full House"),
    SMALL_STRAIGHT("Small Straight"),
    LARGE_STRAIGHT("Large Straight"),
    YAHTZEE("Yahtzee"),
    CHANCE("Chance");

    companion object {
        val UPPER = listOf(ONES, TWOS, THREES, FOURS, FIVES, SIXES)
        val LOWER = listOf(THREE_OF_A_KIND, FOUR_OF_A_KIND, FULL_HOUSE, SMALL_STRAIGHT, LARGE_STRAIGHT, YAHTZEE, CHANCE)
    }
}
