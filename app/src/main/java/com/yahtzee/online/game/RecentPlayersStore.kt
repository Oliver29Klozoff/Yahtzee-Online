package com.yahtzee.online.game

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * People this device has actually played against, so a second game with them costs one tap
 * instead of another round of reading a room code down the phone.
 *
 * Local, and deliberately not a friends list: nobody is added, accepted or removed, and the
 * other player is never told they are on it. It is a record of who you have played, which is
 * something this device already knew.
 */
object RecentPlayersStore {

    private const val PREFS = "recent_players"
    private const val KEY_PLAYERS = "players"
    private const val MAX_PLAYERS = 12

    data class RecentPlayer(val id: String, val name: String, val lastPlayedAt: Long)

    /** Most recently played first. */
    fun all(context: Context): List<RecentPlayer> {
        val raw = prefs(context).getString(KEY_PLAYERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                RecentPlayer(
                    id = item.optString("id").ifEmpty { return@mapNotNull null },
                    name = item.optString("name").ifEmpty { return@mapNotNull null },
                    lastPlayedAt = item.optLong("lastPlayedAt")
                )
            }
        }.getOrDefault(emptyList()).sortedByDescending { it.lastPlayedAt }
    }

    /**
     * Records everyone in a game apart from this device's own player. Bots are excluded by the
     * caller — they have no id worth keeping and cannot be invited anywhere.
     */
    fun remember(context: Context, players: Collection<Player>, exceptId: String) {
        val now = System.currentTimeMillis()
        val existing = all(context).associateBy { it.id }.toMutableMap()

        players.filter { it.id != exceptId && it.id.isNotEmpty() && it.name.isNotEmpty() }
            .forEach { existing[it.id] = RecentPlayer(it.id, it.name, now) }

        write(
            context,
            existing.values.sortedByDescending { it.lastPlayedAt }.take(MAX_PLAYERS)
        )
    }

    fun forget(context: Context, id: String) {
        write(context, all(context).filterNot { it.id == id })
    }

    private fun write(context: Context, players: List<RecentPlayer>) {
        val array = JSONArray()
        players.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("lastPlayedAt", it.lastPlayedAt)
            )
        }
        prefs(context).edit().putString(KEY_PLAYERS, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
