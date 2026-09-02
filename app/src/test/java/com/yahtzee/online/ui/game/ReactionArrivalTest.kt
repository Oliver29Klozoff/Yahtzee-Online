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

    // The catch-up on opening a game. Reactions used to live only as long as the screen showing
    // them, so unless the other person was looking at their phone at the moment you tapped, nobody
    // ever saw it — which in a turn-a-day game is nearly always.

    /** Something sent while you were on your way to the app plays when you get there. */
    @Test
    fun `opening a game catches up on a recent reaction`() {
        val now = 1_000_000L
        val cutoff = now - Reactions.REPLAY_WINDOW_MS
        val reactions = room(them to ("🔥" to now - 30_000L))
        val arrivals = Reactions.arrivalsSince(reactions, me, lastSeen = emptyMap(), notOlderThan = cutoff)
        assertEquals(listOf(them), arrivals.map { it.key })
    }

    /**
     * The window has to outlast somebody putting their phone down.
     *
     * It was five minutes, and that is the window for a game two people watch together. This one
     * is played a turn at a time across a day: react, and the other person picks their phone up an
     * hour later to take their turn. At five minutes what they got was nothing, which read as the
     * feature being broken rather than as a window expiring.
     */
    @Test
    fun `a reaction from an hour ago still plays`() {
        val now = 100_000_000L
        val cutoff = now - Reactions.REPLAY_WINDOW_MS
        val reactions = room(them to ("🔥" to now - 60 * 60_000L))
        val arrivals = Reactions.arrivalsSince(reactions, me, lastSeen = emptyMap(), notOlderThan = cutoff)
        assertEquals(listOf(them), arrivals.map { it.key })
    }

    /** But not last week's. Opening an old room must not fire off a flurry nobody remembers. */
    @Test
    fun `opening a game does not replay an ancient reaction`() {
        val now = 100_000_000L
        val cutoff = now - Reactions.REPLAY_WINDOW_MS
        val reactions = room(them to ("🔥" to now - 7 * 24 * 60 * 60_000L))
        assertTrue(
            Reactions.arrivalsSince(reactions, me, lastSeen = emptyMap(), notOlderThan = cutoff).isEmpty()
        )
    }

    /** And not the same one twice: leaving and reopening the game must not replay what was shown. */
    @Test
    fun `a reaction already shown is not caught up on again`() {
        val now = 1_000_000L
        val cutoff = now - Reactions.REPLAY_WINDOW_MS
        val sentAt = now - 30_000L
        val reactions = room(them to ("🔥" to sentAt))
        val marks = mapOf(them to sentAt)
        assertTrue(
            Reactions.arrivalsSince(reactions, me, lastSeen = marks, notOlderThan = cutoff).isEmpty()
        )
    }

    /**
     * Live arrivals carry no window.
     *
     * The window compares somebody else's clock against ours, which is fine for "roughly the last
     * few minutes" and not fine for anything exact. Applying it to live arrivals would put a
     * device whose clock lags by more than the window back to being silently dropped — the bug
     * this whole file exists for.
     */
    @Test
    fun `a live reaction is shown however its clock is set`() {
        val marks = mapOf(them to 1L)
        val wayBehind = room(them to ("🔥" to 2L))
        val arrivals = Reactions.arrivalsSince(wayBehind, me, marks, notOlderThan = null)
        assertEquals(listOf(them), arrivals.map { it.key })
    }
}
