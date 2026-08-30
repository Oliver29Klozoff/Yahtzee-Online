package com.yahtzee.online.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Yahtzee bonus across several cards.
 *
 * Every Yahtzee box in the game has to hold 50 before an extra Yahtzee pays anything. Paying as
 * soon as one of them did handed a multi-card game hundreds of points it had not earned, with the
 * remaining boxes still open and waiting for 50s of their own.
 */
class YahtzeeBonusTest {

    private fun player(vararg yahtzeeByCard: Pair<Int, Int?>) = Player(
        id = "p",
        name = "P",
        joinedAt = 0L,
        scores = yahtzeeByCard.mapNotNull { (card, value) ->
            value?.let { ScoreKey.of(card, Category.YAHTZEE) to it }
        }.toMap()
    )

    private fun stateWith(player: Player, cards: Int, dice: List<Int>) = GameState(
        roomCode = "T",
        hostId = player.id,
        status = GameState.STATUS_PLAYING,
        playerOrder = listOf(player.id),
        players = mapOf(player.id to player),
        cardCount = cards,
        rollsUsed = 1,
        dice = dice
    )

    private val yahtzee = listOf(4, 4, 4, 4, 4)

    @Test
    fun `one card is unlocked by its own box`() {
        assertTrue(player(0 to 50).yahtzeeBonusUnlocked(1))
        assertFalse(player(0 to 0).yahtzeeBonusUnlocked(1))
        assertFalse(player().yahtzeeBonusUnlocked(1))
    }

    /** The case that was wrong: one card banked, the others still open. */
    @Test
    fun `three cards are not unlocked by one banked yahtzee`() {
        assertFalse(player(0 to 50).yahtzeeBonusUnlocked(3))
        assertFalse(player(0 to 50, 1 to 50).yahtzeeBonusUnlocked(3))
        assertTrue(player(0 to 50, 1 to 50, 2 to 50).yahtzeeBonusUnlocked(3))
    }

    /** A single scratched box locks the bonus out for the whole game, however many 50s there are. */
    @Test
    fun `a scratched box anywhere blocks the bonus`() {
        assertFalse(player(0 to 50, 1 to 0, 2 to 50).yahtzeeBonusUnlocked(3))
    }

    @Test
    fun `a yahtzee with a box still open reads as one to bank`() {
        val state = stateWith(player(0 to 50), cards = 3, dice = yahtzee)
        assertEquals(YahtzeeState.FIRST, state.yahtzeeStateFor("p"))
    }

    @Test
    fun `a yahtzee once every box holds fifty reads as a bonus`() {
        val state = stateWith(player(0 to 50, 1 to 50, 2 to 50), cards = 3, dice = yahtzee)
        assertEquals(YahtzeeState.BONUS, state.yahtzeeStateFor("p"))
    }

    @Test
    fun `every box filled but one scratched reads as forfeited`() {
        val state = stateWith(player(0 to 50, 1 to 0, 2 to 50), cards = 3, dice = yahtzee)
        assertEquals(YahtzeeState.FORFEITED, state.yahtzeeStateFor("p"))
    }

    /** Ordinary dice say nothing at all, whatever the boxes hold. */
    @Test
    fun `no yahtzee on the table means nothing to report`() {
        val state = stateWith(player(0 to 50, 1 to 50), cards = 2, dice = listOf(1, 2, 3, 4, 5))
        assertEquals(YahtzeeState.NONE, state.yahtzeeStateFor("p"))
    }

    /** The classic single-card game must behave exactly as it always did. */
    @Test
    fun `one card behaves as before`() {
        assertEquals(
            YahtzeeState.FIRST,
            stateWith(player(), cards = 1, dice = yahtzee).yahtzeeStateFor("p")
        )
        assertEquals(
            YahtzeeState.BONUS,
            stateWith(player(0 to 50), cards = 1, dice = yahtzee).yahtzeeStateFor("p")
        )
        assertEquals(
            YahtzeeState.FORFEITED,
            stateWith(player(0 to 0), cards = 1, dice = yahtzee).yahtzeeStateFor("p")
        )
    }
}
