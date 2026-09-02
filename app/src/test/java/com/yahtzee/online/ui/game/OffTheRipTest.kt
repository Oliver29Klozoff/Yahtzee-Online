package com.yahtzee.online.ui.game

import com.yahtzee.online.game.Category
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The called shout, which travels down the reaction channel as a reserved token.
 *
 * Worth pinning separately from the automatic one. The token has to stay distinct from the plain
 * dart reaction sitting next to it in the same row, and it has to stay inside the length the
 * database rules allow for a reaction — a token that outgrew that limit would be refused by the
 * server, and the only symptom would be a button that silently does nothing on other people's
 * screens.
 */
class OffTheRipCallTest {

    /** The rules cap an emoji at eight characters; see firebase-database-rules.json. */
    private val emojiFieldLimit = 8

    @Test
    fun `the shout token is not the plain dart reaction`() {
        assertFalse(Reactions.isShout("🎯"))
        assertTrue(Reactions.isShout(Reactions.OFF_THE_RIP))
    }

    /**
     * The dart left the row when the button took its job, but the token is still made of one, and
     * an older build still has it in its row. Both mean the animation has to stay shipped.
     */
    @Test
    fun `the dart's animation is still shipped`() {
        assertTrue(
            "the shout token is drawn from this code point on any client that throws it as an emoji",
            Reactions.OFF_THE_RIP.startsWith("🎯")
        )
    }

    /** Sending the token must not be something the database will refuse. */
    @Test
    fun `the shout token fits what the rules accept`() {
        assertTrue(Reactions.OFF_THE_RIP.isNotEmpty())
        assertTrue(Reactions.OFF_THE_RIP.length <= emojiFieldLimit)
    }

    /** Nothing else in the row may collide with it, or a tap would read as a shout. */
    @Test
    fun `no ordinary reaction is mistaken for the shout`() {
        Reactions.EMOJI.forEach { assertFalse(Reactions.isShout(it)) }
    }
}

/**
 * A shout that goes up for everything is noise. These pin down the two things that make it mean
 * something: it happened on the opening roll, and what was scored was actually worth having.
 */
class OffTheRipTest {

    @Test
    fun `a full house on the opening roll qualifies`() {
        assertTrue(OffTheRip.qualifies(1, Category.FULL_HOUSE, listOf(3, 3, 3, 5, 5)))
    }

    @Test
    fun `a yahtzee on the opening roll qualifies`() {
        assertTrue(OffTheRip.qualifies(1, Category.YAHTZEE, listOf(4, 4, 4, 4, 4)))
    }

    @Test
    fun `a large straight on the opening roll qualifies`() {
        assertTrue(OffTheRip.qualifies(1, Category.LARGE_STRAIGHT, listOf(1, 2, 3, 4, 5)))
    }

    /** The whole point is that no rerolls were spent. */
    @Test
    fun `the same hand after a reroll does not qualify`() {
        val dice = listOf(3, 3, 3, 5, 5)
        assertFalse(OffTheRip.qualifies(2, Category.FULL_HOUSE, dice))
        assertFalse(OffTheRip.qualifies(3, Category.FULL_HOUSE, dice))
    }

    @Test
    fun `nothing qualifies before a roll has been made`() {
        assertFalse(OffTheRip.qualifies(0, Category.YAHTZEE, listOf(4, 4, 4, 4, 4)))
    }

    /** Using a box as a dustbin is not a moment, however early it happens. */
    @Test
    fun `scoring a pittance in the upper section does not qualify`() {
        assertFalse(OffTheRip.qualifies(1, Category.ONES, listOf(1, 3, 4, 5, 6)))
        assertFalse(OffTheRip.qualifies(1, Category.TWOS, listOf(2, 2, 4, 5, 6)))
    }

    /** Nor does taking a zero, which is the opposite of something to celebrate. */
    @Test
    fun `taking a zero never qualifies`() {
        assertFalse(OffTheRip.qualifies(1, Category.YAHTZEE, listOf(1, 2, 3, 4, 5)))
        assertFalse(OffTheRip.qualifies(1, Category.FULL_HOUSE, listOf(1, 2, 3, 4, 5)))
        assertFalse(OffTheRip.qualifies(1, Category.LARGE_STRAIGHT, listOf(6, 6, 6, 6, 6)))
    }

    /** A big upper-section haul is worth the shout even though it is only sixes. */
    @Test
    fun `a heavy upper section roll qualifies`() {
        assertTrue(OffTheRip.qualifies(1, Category.SIXES, listOf(6, 6, 6, 6, 2)))
    }
}
