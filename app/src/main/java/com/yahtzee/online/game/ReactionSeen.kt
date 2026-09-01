package com.yahtzee.online.game

import android.content.Context

/**
 * How far each player's reactions had been shown, per room, remembered across visits.
 *
 * A reaction used to live only as long as the screen showing it. Whatever was in the room when you
 * opened a game was adopted silently as history, so unless the other person happened to be looking
 * at their phone at the moment you tapped, nobody ever saw it. In a game played a turn a day that
 * is nearly always — the reactions were going almost entirely unseen.
 *
 * Kept per room rather than one mark for the app, because "have I seen this" is a question about a
 * particular game. Reacting in one room should not silence the reaction waiting in another.
 *
 * Stored as `playerId:timestamp` pairs. The map is small — one entry per player in the room, a
 * handful at most — and rooms drop out on their own when [forget] is called at the end of a game.
 */
object ReactionSeen {

    private const val PREFS = "reactions_seen"

    /** Separates one player's mark from the next; neither may appear in a player id. */
    private const val ENTRY = ','
    private const val FIELD = ':'

    /** What has already been shown for [roomCode]. Empty when this room is new to the device. */
    fun marks(context: Context, roomCode: String): Map<String, Long> {
        val raw = prefs(context).getString(roomCode, null).orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return raw.split(ENTRY).mapNotNull { entry ->
            val at = entry.substringAfterLast(FIELD, "").toLongOrNull() ?: return@mapNotNull null
            val id = entry.substringBeforeLast(FIELD)
            if (id.isEmpty()) null else id to at
        }.toMap()
    }

    fun remember(context: Context, roomCode: String, marks: Map<String, Long>) {
        if (roomCode.isEmpty()) return
        val raw = marks.entries.joinToString(ENTRY.toString()) { "${it.key}$FIELD${it.value}" }
        prefs(context).edit().putString(roomCode, raw).apply()
    }

    /** Drops a room, once there can be nothing further to see in it. */
    fun forget(context: Context, roomCode: String) {
        prefs(context).edit().remove(roomCode).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
