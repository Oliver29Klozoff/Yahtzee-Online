package com.yahtzee.online.game

import android.content.Context

/**
 * Which game formats this device has actually finished.
 *
 * Every format has its own board, because a three-card total and a one-card total are not the
 * same achievement and ranking them in one column ranks people partly by which they chose. But
 * offering a board for every format to everybody would bury the one board most people want
 * behind several they will never look at — so the picker lists the formats this player plays,
 * and nothing else.
 *
 * One card is always present. It is the classic game and the default board even for somebody who
 * has not finished one yet.
 */
object PlayedFormats {

    private const val PREFS = "played_formats"
    private const val KEY = "cards"

    fun record(context: Context, cardCount: Int) {
        if (cardCount !in GameState.CARD_OPTIONS) return
        val current = all(context)
        if (cardCount in current) return
        prefs(context).edit()
            .putString(KEY, (current + cardCount).sorted().joinToString(","))
            .apply()
    }

    /** Formats played, lowest first, always including the classic single card. */
    fun all(context: Context): List<Int> {
        val stored = prefs(context).getString(KEY, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in GameState.CARD_OPTIONS }
            .orEmpty()
        return (stored + 1).distinct().sorted()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
