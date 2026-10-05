package com.yahtzee.online.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Settings promises about keeping games, and what the sweep actually does.
 *
 * Settings now states these in days and hours. They are read from here rather than written into
 * the text so the two cannot disagree — but a careless edit could still change a number without
 * anyone weighing what it means, and the number that matters is a promise about losing somebody's
 * game halfway through it.
 */
class RetentionTest {

    /**
     * A fortnight, because an unfinished game may legitimately be played a turn a day.
     *
     * This is the one that would hurt. A game deleted out from under people mid-way is worse than
     * any amount of storage, so shortening it is a decision to be made deliberately rather than
     * noticed afterwards.
     */
    @Test
    fun `a game in progress is kept for a fortnight`() {
        assertEquals(14, RoomCleanup.PLAYING_TTL_DAYS)
    }

    @Test
    fun `a finished game outlives the last look at the score`() {
        assertEquals(2, RoomCleanup.FINISHED_TTL_DAYS)
    }

    /** An unstarted lobby is over the moment everyone walks away, and these are the bulk of them. */
    @Test
    fun `an unstarted lobby goes within the day`() {
        assertEquals(6, RoomCleanup.LOBBY_TTL_HOURS)
        assertTrue("a lobby should not outlive a day", RoomCleanup.LOBBY_TTL_HOURS < 24)
    }

    @Test
    fun `a challenge is kept a month`() {
        assertEquals(30, RoomCleanup.DUEL_TTL_DAYS)
    }

    /**
     * The order has to hold whatever the numbers are.
     *
     * A lobby must go first and a game in progress must outlive a finished one; any other
     * ordering would be deleting the rooms people still want in preference to the ones they do
     * not.
     */
    @Test
    fun `the shortest life is the one nobody started`() {
        val lobbyDays = RoomCleanup.LOBBY_TTL_HOURS / 24.0
        assertTrue(lobbyDays < RoomCleanup.FINISHED_TTL_DAYS)
        assertTrue(RoomCleanup.FINISHED_TTL_DAYS < RoomCleanup.PLAYING_TTL_DAYS)
    }
}
