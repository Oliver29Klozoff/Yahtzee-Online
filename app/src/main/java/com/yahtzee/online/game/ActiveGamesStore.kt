package com.yahtzee.online.game

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The online games this device is currently sitting in.
 *
 * Room codes were previously held only in the intent that opened the lobby, so a game existed
 * for exactly as long as the screen showing it. That is fine when everyone plays a room through
 * in one sitting and fatal the moment a turn is meant to be taken tomorrow — there was no record
 * anywhere that the player was even in a game.
 *
 * Kept on the device rather than under the player's node in Firebase: this is a list of what to
 * *check*, and a device that has been wiped or reinstalled has nothing to check for, so there is
 * nothing worth restoring.
 */
object ActiveGamesStore {

    private const val PREFS = "active_games"
    private const val KEY_GAMES = "games"

    /** Well past a plausible game, so a room abandoned by everyone is eventually dropped. */
    private const val STALE_AFTER_MILLIS = 30L * 24 * 60 * 60 * 1000

    /**
     * One tracked room.
     *
     * [notifiedTurnKey] records the turn the player was last told about, so a job that runs every
     * quarter of an hour does not announce the same turn four times an hour until it is taken.
     */
    data class TrackedGame(
        val roomCode: String,
        val joinedAt: Long,
        val notifiedTurnKey: String = ""
    )

    fun all(context: Context): List<TrackedGame> {
        val raw = prefs(context).getString(KEY_GAMES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val code = item.optString("roomCode").ifEmpty { return@mapNotNull null }
                TrackedGame(
                    roomCode = code,
                    joinedAt = item.optLong("joinedAt"),
                    notifiedTurnKey = item.optString("notifiedTurnKey")
                )
            }
        }.getOrDefault(emptyList())
            .filter { System.currentTimeMillis() - it.joinedAt < STALE_AFTER_MILLIS }
    }

    fun track(context: Context, roomCode: String) {
        if (roomCode.isEmpty()) return
        val existing = all(context)
        if (existing.any { it.roomCode == roomCode }) return
        write(context, existing + TrackedGame(roomCode, System.currentTimeMillis()))
    }

    fun untrack(context: Context, roomCode: String) {
        write(context, all(context).filterNot { it.roomCode == roomCode })
    }

    /** Records that the player has been told about [turnKey] in [roomCode]. */
    fun markNotified(context: Context, roomCode: String, turnKey: String) {
        write(
            context,
            all(context).map {
                if (it.roomCode == roomCode) it.copy(notifiedTurnKey = turnKey) else it
            }
        )
    }

    fun isEmpty(context: Context): Boolean = all(context).isEmpty()

    private fun write(context: Context, games: List<TrackedGame>) {
        val array = JSONArray()
        games.forEach {
            array.put(
                JSONObject()
                    .put("roomCode", it.roomCode)
                    .put("joinedAt", it.joinedAt)
                    .put("notifiedTurnKey", it.notifiedTurnKey)
            )
        }
        prefs(context).edit().putString(KEY_GAMES, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
