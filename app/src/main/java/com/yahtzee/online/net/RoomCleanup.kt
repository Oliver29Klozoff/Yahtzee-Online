package com.yahtzee.online.net

import android.content.Context
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.yahtzee.online.game.GameState

/**
 * Deletes rooms nobody is coming back to.
 *
 * Nothing ever removed a room. Every game created one, every television created a fresh one each
 * time it was switched on, and every abandoned lobby left one behind — all of them permanent. On
 * a free database that only ends one way.
 *
 * There is no server to run this on, so the clients do it: one sweep a day per device, on the way
 * into the start screen, deleting a small batch of the oldest rooms. It does not matter which
 * device does the sweeping or whether any given one ever does, because a room that survives one
 * day's sweep is only older and more certainly dead by the next.
 *
 * ## How long is dead
 *
 * The cutoffs differ by what the room was doing when it stopped, because "idle" means completely
 * different things for each. An unstarted lobby is over the moment everyone walks away. A finished
 * game is over by definition, and only wants to outlive the last player still looking at the final
 * score. A game in progress may be an async one, where a turn a day is the intended pace and a
 * quiet weekend is normal — deleting one of those out from under people would be far worse than
 * paying to store it, so it gets two weeks.
 */
object RoomCleanup {

    private const val TAG = "RoomCleanup"

    private const val PREFS = "room_cleanup"
    private const val KEY_LAST_SWEEP = "lastSweepAt"

    private const val HOUR = 60 * 60 * 1000L
    private const val DAY = 24 * HOUR

    /** How often any one device bothers. The work is shared and idempotent. */
    private const val SWEEP_INTERVAL_MS = DAY

    /** Nobody ever started it. */
    private const val LOBBY_TTL_MS = 6 * HOUR

    /** Everyone has seen the result. */
    private const val FINISHED_TTL_MS = 2 * DAY

    /** A game in progress, which may legitimately be played a turn a day. */
    private const val PLAYING_TTL_MS = 14 * DAY

    /** Deleted per sweep, so one launch never turns into a long run of writes. */
    private const val BATCH = 25

    /**
     * Sweeps at most once per [SWEEP_INTERVAL_MS], and only with a session in hand.
     *
     * Fails silently by design. This is housekeeping — there is no version of "could not tidy up"
     * that a player wants to be told about mid-game.
     */
    fun maybeSweep(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_SWEEP, 0L)
        if (now - last < SWEEP_INTERVAL_MS) return

        // Recorded before the sweep rather than after. If it fails there is no point retrying on
        // every launch for the rest of the day, and tomorrow's sweep picks up whatever was missed.
        prefs.edit().putLong(KEY_LAST_SWEEP, now).apply()

        FirebaseSignIn.awaitReady { sweep() }
    }

    private fun sweep() {
        val games = FirebaseDatabase.getInstance().getReference("games")

        // Ordered by the heartbeat, oldest first, cut off at the *shortest* of the three lives.
        // Anything newer than that cannot be expired under any of them, so it need not be read;
        // which of the longer cutoffs actually applies is decided per room below.
        //
        // Rooms written before this version have no `updatedAt` at all. Firebase sorts a missing
        // key ahead of every number, so they arrive first — which is right: they predate the
        // heartbeat, so they are the oldest things here by definition.
        games.orderByChild("updatedAt")
            .endAt((System.currentTimeMillis() - LOBBY_TTL_MS).toDouble())
            .limitToFirst(BATCH)
            .get()
            .addOnSuccessListener { snapshot ->
                val now = System.currentTimeMillis()
                var removed = 0
                snapshot.children.forEach { room ->
                    val status = room.child("status").getValue(String::class.java)
                        ?: GameState.STATUS_LOBBY
                    // No heartbeat means it was created before rooms had one, so treat it as
                    // ancient rather than as brand new — the opposite reading would make every
                    // legacy room immortal, which is the problem being fixed.
                    val updatedAt = room.child("updatedAt").getValue(Long::class.java) ?: 0L
                    if (now - updatedAt < ttlFor(status)) return@forEach
                    room.ref.removeValue()
                    removed++
                }
                if (removed > 0) Log.i(TAG, "Removed $removed stale room(s)")
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Sweep skipped", error)
            }
    }

    private fun ttlFor(status: String): Long = when (status) {
        GameState.STATUS_FINISHED -> FINISHED_TTL_MS
        GameState.STATUS_PLAYING, GameState.STATUS_ROLL_OFF -> PLAYING_TTL_MS
        else -> LOBBY_TTL_MS
    }
}
