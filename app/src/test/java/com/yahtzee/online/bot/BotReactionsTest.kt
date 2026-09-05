package com.yahtzee.online.bot

import com.yahtzee.online.game.Category
import com.yahtzee.online.ui.game.Reactions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * When a bot has something to say, and — more importantly — when it does not.
 *
 * Something that reacts to everything is noise, so most of these assert silence.
 */
class BotReactionsTest {

    /** A generator that always speaks, so what is under test is the choice and not the dice. */
    private fun talkative() = Random(1)

    private fun repeated(times: Int, block: (Random) -> String?): List<String?> {
        val random = Random(20260905)
        return (1..times).map { block(random) }
    }

    @Test
    fun `a Yahtzee is worth a word`() {
        val said = repeated(60) { BotReactions.forOwnScore(Category.YAHTZEE, 50, it) }
        assertTrue("a bot should sometimes react to its own Yahtzee", said.any { it != null })
    }

    @Test
    fun `a large straight is worth a word`() {
        val said = repeated(60) { BotReactions.forOwnScore(Category.LARGE_STRAIGHT, 40, it) }
        assertTrue(said.any { it != null })
    }

    /** Throwing a box away is the one thing every player feels. */
    @Test
    fun `a zero gets a reaction`() {
        val said = repeated(60) { BotReactions.forOwnScore(Category.YAHTZEE, 0, it) }
        assertTrue(said.any { it != null })
    }

    /** An ordinary score is not an event. */
    @Test
    fun `a middling score says nothing`() {
        val said = repeated(80) { BotReactions.forOwnScore(Category.THREES, 9, it) }
        assertTrue("bots should not react to ordinary scores", said.all { it == null })
    }

    /** Applause is for something worth applauding, not for every turn that goes by. */
    @Test
    fun `an ordinary score by somebody else says nothing`() {
        val said = repeated(80) { BotReactions.forOtherScore(Category.FOURS, 12, it) }
        assertTrue(said.all { it == null })
    }

    @Test
    fun `somebody else's Yahtzee gets applause`() {
        val said = repeated(60) { BotReactions.forOtherScore(Category.YAHTZEE, 50, it) }
        assertTrue(said.any { it != null })
    }

    /** A Yahtzee box taken as a zero is not a Yahtzee, and is nobody's triumph. */
    @Test
    fun `a zero in the Yahtzee box is not applauded`() {
        val said = repeated(80) { BotReactions.forOtherScore(Category.YAHTZEE, 0, it) }
        assertTrue(said.all { it == null })
    }

    /** It stays quiet a fair amount even when there is something to say. */
    @Test
    fun `it does not react every single time`() {
        val said = repeated(200) { BotReactions.forOwnScore(Category.YAHTZEE, 50, it) }
        assertTrue("a bot that always reacts is a bot nobody reads", said.any { it == null })
    }

    /**
     * The one it must never send.
     *
     * A person choosing the rude emoji is banter between two people who know each other. A bot
     * sending it unprompted is the app insulting whoever is holding the phone, and there is no
     * reading of the game where that is wanted.
     */
    @Test
    fun `a bot is never rude`() {
        val rude = "🖕"
        val everything = Category.values().flatMap { category ->
            listOf(0, 5, 25, 40, 50).flatMap { points ->
                val random = Random(7)
                (1..40).flatMap {
                    listOf(
                        BotReactions.forOwnScore(category, points, random),
                        BotReactions.forOtherScore(category, points, random)
                    )
                }
            }
        }
        assertFalse("bots must never send the rude one", everything.contains(rude))
    }

    /** Whatever it sends has to be something the app can actually draw. */
    @Test
    fun `every reaction is one the app knows`() {
        val known = Reactions.EMOJI.toSet()
        val everything = Category.values().flatMap { category ->
            listOf(0, 25, 40, 50).flatMap { points ->
                val random = Random(11)
                (1..40).flatMap {
                    listOfNotNull(
                        BotReactions.forOwnScore(category, points, random),
                        BotReactions.forOtherScore(category, points, random)
                    )
                }
            }
        }
        assertNotNull(everything)
        everything.forEach { assertTrue("$it is not in the app's emoji row", it in known) }
    }

    /** Nothing is said about a category that was never scored. */
    @Test
    fun `silence is representable`() {
        assertNull(BotReactions.forOtherScore(Category.CHANCE, 18, talkative()))
    }
}
