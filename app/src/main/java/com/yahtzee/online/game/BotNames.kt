package com.yahtzee.online.game

import android.content.Context

/**
 * Who the bots are, and whose turn it is to be one.
 *
 * The pool was always there; what was missing was any memory of having used it. Every place that
 * seated a bot took the first name nobody in that room was using, which is a perfectly good rule
 * that produces Ada, every single time, because the room is empty when the first bot sits down.
 * Playing the same opponent for weeks is not a naming scheme, it is one name.
 *
 * So the cursor is kept on the device rather than worked out from the table. Rotation has to
 * outlive the game it happens in — anything derived from who is currently seated starts over from
 * the top of the list the moment a room is empty, which is exactly when a bot is being added.
 */
object BotNames {

    val POOL = listOf(
        "Ada", "Bruno", "Cleo", "Dexter", "Etta", "Felix", "Greta", "Hugo",
        "Iris", "Jonas", "Kira", "Lorne", "Mabel", "Nico", "Opal", "Piper",
        "Quinn", "Rufus", "Sable", "Theo", "Uma", "Vera", "Wilder", "Zaia"
    )

    private const val PREFS = "bot_names"
    private const val KEY_CURSOR = "cursor"

    /**
     * The next [count] names, and where the cursor lands afterwards.
     *
     * Pure, so the rotation can be tested without a device. Names in [avoid] are stepped over
     * rather than counted — somebody already at this table is not available, but skipping them
     * should not cost the rotation its place.
     *
     * Falls back to reusing the pool if a table somehow wants more names than exist, which cannot
     * happen at present but is a poor reason for a crash.
     */
    fun pick(cursor: Int, count: Int, avoid: Set<String> = emptySet()): Pair<List<String>, Int> {
        if (count <= 0 || POOL.isEmpty()) return emptyList<String>() to cursor

        val taken = ArrayList<String>(count)
        var position = ((cursor % POOL.size) + POOL.size) % POOL.size
        var stepsLeft = POOL.size

        while (taken.size < count) {
            val candidate = POOL[position]
            position = (position + 1) % POOL.size
            if (candidate !in avoid && candidate !in taken) taken.add(candidate)

            // One full lap without filling the order means everything left is spoken for.
            if (--stepsLeft <= 0) {
                if (taken.isEmpty()) return POOL.take(count) to position
                break
            }
        }
        return taken to position
    }

    /** The next name, advancing the rotation. */
    fun next(context: Context, avoid: Set<String> = emptySet()): String =
        next(context, 1, avoid).firstOrNull() ?: POOL.first()

    /** The next [count] names, advancing the rotation past all of them. */
    fun next(context: Context, count: Int, avoid: Set<String> = emptySet()): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val (names, nextCursor) = pick(prefs.getInt(KEY_CURSOR, 0), count, avoid)
        prefs.edit().putInt(KEY_CURSOR, nextCursor).apply()
        return names
    }
}
