package com.yahtzee.online.game

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deciding whether a stored history is worth taking.
 *
 * The dangerous direction is a stale snapshot landing on a phone that has been played on since,
 * which would quietly delete real games — the exact failure this whole feature exists to stop,
 * only pointed the other way. Games played is the yardstick because it is the one number that
 * cannot go down.
 */
class ProfileSyncTest {

    private fun history(played: Int): Map<String, String> = mapOf(
        "player_stats" to JSONObject()
            .put("totals", JSONObject().put("played", played).toString())
            .toString()
    )

    @Test
    fun `games played is read out of a stored snapshot`() {
        assertEquals(37, ProfileSync.gamesIn(history(37)))
    }

    @Test
    fun `a snapshot with nothing in it counts as no games`() {
        assertEquals(0, ProfileSync.gamesIn(emptyMap()))
    }

    /** A phone that has never played must not be treated as ahead of one that has. */
    @Test
    fun `a fresh phone counts as zero and loses to any history`() {
        assertEquals(0, ProfileSync.gamesIn(history(0)))
        assert(ProfileSync.gamesIn(history(0)) < ProfileSync.gamesIn(history(1)))
    }

    /** Nothing here should throw on data written by a future or broken build. */
    @Test
    fun `rubbish in a snapshot is survivable`() {
        assertEquals(0, ProfileSync.gamesIn(mapOf("player_stats" to "not json at all")))
        assertEquals(0, ProfileSync.gamesIn(mapOf("player_stats" to "{}")))
        assertEquals(
            0,
            ProfileSync.gamesIn(mapOf("player_stats" to JSONObject().put("totals", "{}").toString()))
        )
    }

    /**
     * The file list is the contract.
     *
     * Device settings are deliberately absent: dice colour and sound belong to the phone rather
     * than the person, and the seen-bookkeeping for rooms and reactions would be actively wrong to
     * carry to a device that has not seen any of it.
     */
    @Test
    fun `only a player's own history is carried`() {
        assertEquals(
            listOf("player_stats", "rivalries", "played_formats", "daily_challenge"),
            ProfileSync.FILES
        )
        listOf("dice_prefs", "settings", "reactions_seen", "recap_seen", "tournament").forEach {
            assert(it !in ProfileSync.FILES) { "$it belongs to the phone, not the player" }
        }
    }
}
