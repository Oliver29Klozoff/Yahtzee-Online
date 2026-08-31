package com.yahtzee.online.game

import android.content.Context

/**
 * When each room's last nudge was shown to this player.
 *
 * Shared between the background check and the start screen so the two cannot announce the same
 * nudge twice — being prodded once is the point; being prodded once by a notification and again
 * by a toast thirty seconds later is a bug wearing the feature's clothes.
 *
 * Its own store rather than a field on the tracked game, because a nudge is answered by taking
 * the turn rather than by the turn changing, so it does not fit the turn key everything else
 * dedupes on.
 */
object NudgeSeen {

    private const val PREFS = "nudges_seen"

    fun lastSeen(context: Context, roomCode: String): Long =
        prefs(context).getLong(roomCode, 0L)

    fun mark(context: Context, roomCode: String, at: Long) {
        prefs(context).edit().putLong(roomCode, at).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
