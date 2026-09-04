package com.yahtzee.online.bot

import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.Scoring
import kotlin.random.Random

/**
 * Applies a difficulty level on top of [BotStrategy].
 *
 * [BotStrategy] plays as well as it knows how, which is the right ceiling but makes for a
 * discouraging opponent for a child or a casual game. Rather than writing three separate
 * strategies, weaker levels degrade the strong one in the two places the game is actually won
 * or lost:
 *
 *  - which dice to keep, where a weak player keeps the wrong ones;
 *  - and whether to protect a valuable box, where a weak player will happily put a bad roll in
 *    Yahtzee because it is worth the most points right now.
 *
 * Degrading rather than replacing keeps every level coherent — an easy bot still plays a
 * recognisable game of Yahtzee, it just plays it worse.
 */
object BotSkillPlay {

    fun chooseHolds(
        skill: AppSettings.BotSkill,
        dice: List<Int>,
        openCategories: Set<Category>,
        rollsLeft: Int,
        upperTotalSoFar: Int = 0,
        random: Random = Random.Default
    ): Set<Int> {
        // Expert is a different method rather than a stronger setting of the same one: it works
        // the keep out by search instead of matching the dice against a list of rules.
        if (skill == AppSettings.BotSkill.EXPERT) {
            return ExpertStrategy.chooseHolds(
                dice,
                openCategories,
                rollsLeft,
                upperTotalSoFar,
                ExpertStrategy.PLAYS_FOR_UPPER_BONUS
            )
        }
        val best = BotStrategy.chooseHolds(dice, openCategories, rollsLeft)
        return when (skill) {
            AppSettings.BotSkill.EXPERT -> best
            AppSettings.BotSkill.HARD -> best
            // Occasionally keeps the wrong dice, the way a decent player misreads a roll.
            AppSettings.BotSkill.NORMAL ->
                if (random.nextFloat() < 0.25f) randomHolds(dice, random) else best
            // Mostly guesswork, though it will still hang on to an obvious set.
            AppSettings.BotSkill.EASY ->
                if (random.nextFloat() < 0.7f) randomHolds(dice, random) else best
        }
    }

    fun chooseCategory(
        skill: AppSettings.BotSkill,
        dice: List<Int>,
        openCategories: Set<Category>,
        upperTotalSoFar: Int,
        random: Random = Random.Default
    ): Category {
        return when (skill) {
            // Priced the same way the search prices it, so where it scores agrees with what it
            // kept — the two halves of a turn pulling different directions is its own weakness.
            // That includes the upper bonus, which expert now plays for on both halves.
            AppSettings.BotSkill.EXPERT ->
                ExpertStrategy.chooseCategory(
                    dice,
                    openCategories,
                    upperTotalSoFar,
                    ExpertStrategy.PLAYS_FOR_UPPER_BONUS
                )
            AppSettings.BotSkill.HARD ->
                BotStrategy.chooseCategory(dice, openCategories, upperTotalSoFar)
            AppSettings.BotSkill.NORMAL ->
                if (random.nextFloat() < 0.2f) greedy(dice, openCategories)
                else BotStrategy.chooseCategory(dice, openCategories, upperTotalSoFar)
            // Takes the most points on offer right now, with no thought for what it burns —
            // which is exactly how a beginner loses their Yahtzee box to three of a kind.
            AppSettings.BotSkill.EASY -> greedy(dice, openCategories)
        }
    }

    private fun randomHolds(dice: List<Int>, random: Random): Set<Int> =
        dice.indices.filter { random.nextBoolean() }.toSet()

    private fun greedy(dice: List<Int>, openCategories: Set<Category>): Category =
        openCategories.maxByOrNull { Scoring.score(it, dice) } ?: openCategories.first()
}
