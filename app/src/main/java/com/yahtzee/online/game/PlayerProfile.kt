package com.yahtzee.online.game

import android.content.Context
import java.util.UUID

/**
 * The player's identity on this device: a display name they choose once, plus a stable id that
 * outlives any single game.
 *
 * The id matters for the leaderboard. Room player ids are generated per [GameRepository]
 * instance and so change between sessions; keying the board on those would leave a player with
 * a new row every time they reopened the app. This id is generated once and kept, so a player
 * owns exactly one leaderboard entry no matter how many games they play.
 */
object PlayerProfile {

    private const val PREFS = "player_profile"
    private const val KEY_NAME = "player_name"
    private const val KEY_ID = "profile_id"

    fun hasName(context: Context): Boolean = getName(context).isNotEmpty()

    fun getName(context: Context): String =
        prefs(context).getString(KEY_NAME, "")?.trim().orEmpty()

    fun setName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NAME, name.trim()).apply()
    }

    /** Stable per-device id, created on first use. */
    fun getId(context: Context): String {
        val existing = prefs(context).getString(KEY_ID, null)
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_ID, generated).apply()
        return generated
    }

    /**
     * Adopts an id from a recovery code. Only [ProfileRecovery] should call this — every other
     * caller wants [getId], and rewriting the id at any other time abandons the player's
     * leaderboard row and their seat in every game they are part of.
     */
    fun setId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ID, id).apply()
    }

    /**
     * Issues this device a brand new identity.
     *
     * The way out of two devices sharing one. That happens if a recovery code is entered on both,
     * or if a backup restored the first one's profile onto the second — to the game they are then
     * a single seat, so they cannot join a room against each other, and whichever moves second
     * simply lands on the first one's player. Taking a new id here makes this device a different
     * player, at the cost of the leaderboard row and the games that stay with the old one.
     */
    fun resetId(context: Context): String {
        val fresh = UUID.randomUUID().toString()
        setId(context, fresh)
        return fresh
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
