package com.yahtzee.online.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which reactions count as news.
 *
 * These exist because of a bug that was invisible in every test done on one device: a reaction is
 * stamped with the clock of the phone that sent it, and no two phones agree. Held as one mark for
 * the whole room, the bar was set by whichever device ran fastest — including your own, the moment
 * you reacted — and everybody behind that bar was silently dropped rather than shown late. Two
 * phones sitting on the same desk here were fifty milliseconds apart; a device that has drifted is
 * seconds out, and for those seconds its owner may as well not be in the room.
 */
class ReactionArrivalTest {

    private fun room(vararg entries: Pair<String, Pair<String, Long>>) = mapOf(*entries)

    private val me = "me"
    private val them = "them"

    /** Nothing seen yet is history, not news — opening a game must not replay it. */
    @Test
    fun `the first look adopts what is already there`() {
        val reactions = room(them to ("🔥" to 5_000L))
        assertTrue(Reactions.arrivalsSince(reactions, me, lastSeen = null).isEmpty())
    }

    @Test
    fun `a reaction after the last look is news`() {
        val marks = mapOf(them to 5_000L)
        val reactions = room(them to ("🔥" to 5_001L))
        val arrivals = Reactions.arrivalsSince(reactions, me, marks)
        assertEquals(listOf(them), arrivals.map { it.key })
    }

    /** Yours is already on screen, thrown the instant you tapped. */
    @Test
    fun `your own reaction is not replayed at you`() {
        val marks = mapOf(me to 5_000L)
        val reactions = room(me to ("🔥" to 9_999L))
        assertTrue(Reactions.arrivalsSince(reactions, me, marks).isEmpty())
    }

    /**
     * The bug, stated as a test.
     *
     * My clock runs an hour fast. I react, which under the old scheme pushed the room's single
     * mark up to my hour-ahead timestamp. Everything the other player sends for the next hour
     * carries a smaller number and used to be dropped. Their emoji must still arrive.
     */
    @Test
    fun `a player on a slower clock is still heard`() {
        val anHour = 3_600_000L
        val marks = mapOf(me to 10_000L + anHour, them to 9_000L)
        val reactions = room(
            me to ("👏" to 10_000L + anHour),
            them to ("🔥" to 9_500L)
        )
        val arrivals = Reactions.arrivalsSince(reactions, me, marks)
        assertEquals(
            "a reaction from a device whose clock is behind mine was dropped",
            listOf(them),
            arrivals.map { it.key }
        )
    }

    /** And the reverse: my own fast clock must not make me replay their old one either. */
    @Test
    fun `an unchanged reaction is not shown twice`() {
        val marks = mapOf(them to 9_500L)
        val reactions = room(them to ("🔥" to 9_500L))
        assertTrue(Reactions.arrivalsSince(reactions, me, marks).isEmpty())
    }

    /** Somebody who joins mid-game and reacts has no mark yet; their first is still news. */
    @Test
    fun `a newcomer's first reaction is news`() {
        val marks = mapOf(them to 9_500L)
        val reactions = room(
            them to ("🔥" to 9_500L),
            "latecomer" to ("😱" to 1L)
        )
        val arrivals = Reactions.arrivalsSince(reactions, me, marks)
        assertEquals(listOf("latecomer"), arrivals.map { it.key })
    }

    /** Several at once all arrive, oldest first, rather than only the newest surviving. */
    @Test
    fun `everyone who reacted since the last look is shown, in order`() {
        val marks = mapOf("a" to 1L, "b" to 1L)
        val reactions = room(
            "a" to ("🔥" to 30L),
            "b" to ("👏" to 20L)
        )
        val arrivals = Reactions.arrivalsSince(reactions, me, marks)
        assertEquals(listOf("b", "a"), arrivals.map { it.key })
    }

    /** The marks carried forward are per player, so the next look compares like with like. */
    @Test
    fun `the marks carried forward are each player's own clock`() {
        val reactions = room("a" to ("🔥" to 30L), "b" to ("👏" to 20L))
        assertEquals(mapOf("a" to 30L, "b" to 20L), Reactions.marksFrom(reactions))
    }
}
