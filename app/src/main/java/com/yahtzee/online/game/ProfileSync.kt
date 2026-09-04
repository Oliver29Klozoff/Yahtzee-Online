package com.yahtzee.online.game

import android.content.Context
import org.json.JSONObject

/**
 * Everything about a player that lives on their phone rather than on the server.
 *
 * The recovery code moves an identity between phones, and everything keyed by that identity on the
 * server — a leaderboard place, a seat in an unfinished game — follows it. Everything else does
 * not. Stats, the record against every person you have played, the daily streak, which boards you
 * are eligible for: all of it sits in preferences files and stays on the old phone.
 *
 * That is a quiet loss and the worst kind, because nothing announces it. You restore a code on a
 * new phone, the leaderboard remembers you, so it looks like it all came across — and months of
 * head-to-head history is simply gone.
 *
 * Whole preference files are copied rather than each store being taught to serialise itself. It is
 * less tidy and much harder to get wrong: a store that gains a key gains it here for free, with no
 * second place to remember to update, and nothing here has to know what any of it means.
 */
object ProfileSync {

    /**
     * The files that make up a player.
     *
     * Deliberately not everything. Device settings — dice colour, sound, accent — belong to the
     * phone rather than the person, and a new phone should keep its own. Room and reaction
     * bookkeeping is about what *this* screen has seen and would be actively wrong to carry over.
     */
    val FILES = listOf("player_stats", "rivalries", "played_formats", "daily_challenge")

    /** Each file as a JSON object of its contents, ready to be stored as plain strings. */
    fun snapshot(context: Context): Map<String, String> =
        FILES.associateWith { file ->
            val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            val json = JSONObject()
            prefs.all.forEach { (key, value) ->
                when (value) {
                    is String, is Int, is Long, is Boolean, is Float -> json.put(key, value)
                    // Anything else is a set or an unknown type nothing here writes; skipping it
                    // loses nothing and beats guessing at a representation for it.
                    else -> Unit
                }
            }
            json.toString()
        }

    /**
     * Writes a snapshot back over this device's files.
     *
     * Types are carried through because preferences are typed and reading an Int back as a String
     * throws rather than degrading — a streak stored as a number has to come back as one.
     */
    fun apply(context: Context, data: Map<String, String>) {
        data.forEach { (file, raw) ->
            if (file !in FILES) return@forEach
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            val editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            json.keys().forEach { key ->
                when (val value = json.get(key)) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Double -> editor.putFloat(key, value.toFloat())
                    else -> Unit
                }
            }
            editor.apply()
        }
    }

    /**
     * Whether a snapshot is worth keeping over what is already here.
     *
     * A phone that has just restored a code has nothing of its own to lose, but one that has been
     * played on does, and pulling a stale snapshot over it would throw away real games. Compared
     * on games played, which only ever goes up.
     */
    fun gamesIn(data: Map<String, String>): Int {
        val raw = data["player_stats"] ?: return 0
        val stats = runCatching { JSONObject(raw) }.getOrNull() ?: return 0
        val totals = runCatching { JSONObject(stats.optString("totals")) }.getOrNull() ?: return 0
        return totals.optInt("played", 0)
    }
}
