package com.yahtzee.online.game

import android.content.Context

/**
 * Which boxes each player had filled, per room, as this device last saw them.
 *
 * The room keeps no history — it is a picture of now, not a record of how it got here — so the
 * only way to know what happened while you were away is to remember what it looked like when you
 * last looked. That is what this is.
 *
 * A scorecard is stored as one character per box: '1' filled, '0' not, indexed by card and
 * category. It is not the tightest possible encoding, but it is one anybody can read straight out
 * of the preferences file when something looks wrong, which for a thing that decides what people
 * are told has been worth more than the bytes.
 */
object RecapSeen {

    private const val PREFS = "recap_seen"
    private const val ENTRY = ','
    private const val FIELD = ':'

    private const val FILLED = '1'
    private const val EMPTY = '0'

    /** Boxes per card. Fixed by the rules rather than by the room. */
    private val CATEGORIES = Category.values()

    /** What this device last saw in [roomCode], or null if it has never looked. */
    fun marks(context: Context, roomCode: String): Map<String, String>? {
        val raw = prefs(context).getString(roomCode, null) ?: return null
        if (raw.isEmpty()) return emptyMap()
        return raw.split(ENTRY).mapNotNull { entry ->
            val mask = entry.substringAfterLast(FIELD, "")
            val id = entry.substringBeforeLast(FIELD)
            if (id.isEmpty()) null else id to mask
        }.toMap()
    }

    fun remember(context: Context, roomCode: String, marks: Map<String, String>) {
        if (roomCode.isEmpty()) return
        val raw = marks.entries.joinToString(ENTRY.toString()) { "${it.key}$FIELD${it.value}" }
        prefs(context).edit().putString(roomCode, raw).apply()
    }

    /** Drops a room, once there can be nothing further to miss in it. */
    fun forget(context: Context, roomCode: String) {
        prefs(context).edit().remove(roomCode).apply()
    }

    /** The room as it stands, in the same shape [marks] returns. */
    fun snapshot(state: GameState): Map<String, String> {
        val cards = state.cardCount.coerceAtLeast(1)
        return state.players.mapValues { (_, player) ->
            val slots = CharArray(CATEGORIES.size * cards) { EMPTY }
            player.scores.keys.forEach { key ->
                val slot = slotOf(key, cards)
                if (slot >= 0) slots[slot] = FILLED
            }
            String(slots)
        }
    }

    /** Where a score key sits in the mask, or -1 if it is not a box this room has. */
    fun slotOf(key: String, cards: Int): Int {
        val category = ScoreKey.categoryOf(key) ?: return -1
        val card = ScoreKey.cardOf(key)
        if (card < 0 || card >= cards) return -1
        return card * CATEGORIES.size + category.ordinal
    }

    /** The category a mask position stands for. */
    fun categoryAt(slot: Int): Category = CATEGORIES[slot % CATEGORIES.size]

    /** The card a mask position sits on. */
    fun cardAt(slot: Int): Int = slot / CATEGORIES.size

    fun isFilled(mask: String, slot: Int): Boolean = mask.getOrNull(slot) == FILLED

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
