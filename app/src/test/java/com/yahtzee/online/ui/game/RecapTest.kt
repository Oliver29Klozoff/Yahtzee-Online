package com.yahtzee.online.ui.game

import com.yahtzee.online.game.Category
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.Player
import com.yahtzee.online.game.RecapSeen
import com.yahtzee.online.game.ScoreKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a game says it missed.
 *
 * The room keeps no history, so the recap is a difference between the room now and the shape this
 * device last saw it in. These pin the three ways that can go wrong: saying nothing when there is
 * something, saying something when there is nothing, and telling somebody about their own turn.
 */
class RecapTest {

    private val me = "me"
    private val them = "them"

    private fun room(
        cards: Int = 1,
        mine: Map<Category, Int> = emptyMap(),
        theirs: Map<Category, Int> = emptyMap()
    ) = GameState(
        cardCount = cards,
        playerOrder = listOf(me, them),
        players = mapOf(
            me to Player(id = me, name = "Me", scores = mine.mapKeys { ScoreKey.of(0, it.key) }),
            them to Player(id = them, name = "Them", scores = theirs.mapKeys { ScoreKey.of(0, it.key) })
        )
    )

    /** Never having seen a room is not the same as nothing having happened in it. */
    @Test
    fun `a room this device has never seen is not recapped`() {
        val state = room(theirs = mapOf(Category.YAHTZEE to 50))
        assertTrue(Recap.since(previous = null, state = state, localPlayerId = me).isEmpty())
    }

    @Test
    fun `a box filled since the last look is reported`() {
        val before = RecapSeen.snapshot(room())
        val state = room(theirs = mapOf(Category.FULL_HOUSE to 25))
        val lines = Recap.since(before, state, me)
        assertEquals(1, lines.size)
        assertEquals("Them", lines[0].playerName)
        assertEquals(25, lines[0].points)
    }

    /** You were there for your own turn. */
    @Test
    fun `your own boxes are left out`() {
        val before = RecapSeen.snapshot(room())
        val state = room(mine = mapOf(Category.YAHTZEE to 50))
        assertTrue(Recap.since(before, state, me).isEmpty())
    }

    @Test
    fun `a box that was already there is not reported again`() {
        val state = room(theirs = mapOf(Category.FULL_HOUSE to 25))
        val before = RecapSeen.snapshot(state)
        assertTrue(Recap.since(before, state, me).isEmpty())
    }

    /** Several turns while you were away all arrive, biggest first. */
    @Test
    fun `several missed turns are reported, biggest first`() {
        val before = RecapSeen.snapshot(room())
        val state = room(
            theirs = mapOf(
                Category.ONES to 3,
                Category.YAHTZEE to 50,
                Category.FULL_HOUSE to 25
            )
        )
        val lines = Recap.since(before, state, me)
        assertEquals(listOf(50, 25, 3), lines.map { it.points })
    }

    /** A zero taken in a good box is news too — arguably the most useful kind. */
    @Test
    fun `a scratched box is still reported`() {
        val before = RecapSeen.snapshot(room())
        val state = room(theirs = mapOf(Category.YAHTZEE to 0))
        val lines = Recap.since(before, state, me)
        assertEquals(1, lines.size)
        assertEquals(0, lines[0].points)
    }

    /**
     * Cards are tracked separately.
     *
     * With six scorecards the same category is six different boxes, and a mask that collapsed them
     * would call the second Full House old news because the first one was already filled.
     */
    @Test
    fun `the same category on another card is a separate box`() {
        val filledOnCardZero = GameState(
            cardCount = 3,
            playerOrder = listOf(me, them),
            players = mapOf(
                me to Player(id = me, name = "Me"),
                them to Player(
                    id = them, name = "Them",
                    scores = mapOf(ScoreKey.of(0, Category.FULL_HOUSE) to 25)
                )
            )
        )
        val before = RecapSeen.snapshot(filledOnCardZero)
        val alsoCardTwo = filledOnCardZero.copy(
            players = filledOnCardZero.players.mapValues { (id, p) ->
                if (id != them) p else p.copy(
                    scores = p.scores + (ScoreKey.of(2, Category.FULL_HOUSE) to 25)
                )
            }
        )
        val lines = Recap.since(before, alsoCardTwo, me)
        assertEquals(1, lines.size)
        assertEquals(2, lines[0].card)
    }

    /** A newcomer's whole card is theirs; it should not read as a dozen missed turns. */
    @Test
    fun `the list is capped so a long absence is a summary`() {
        val before = RecapSeen.snapshot(room())
        val everything = Category.values().associateWith { 10 }
        val lines = Recap.since(before, room(theirs = everything), me)
        assertTrue("a recap of ${lines.size} lines is the scorecard again", lines.size <= 6)
    }
}
