package com.yahtzee.online.game

import kotlin.random.Random

const val MAX_ROLLS_PER_TURN = 3
const val DICE_COUNT = 5

class DiceRoller(private val random: Random = Random.Default) {

    fun rollAll(): List<Int> = List(DICE_COUNT) { random.nextInt(1, 7) }

    fun reroll(current: List<Int>, held: Set<Int>): List<Int> =
        current.mapIndexed { index, value -> if (index in held) value else random.nextInt(1, 7) }
}
