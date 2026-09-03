package com.yahtzee.online.game

import android.content.Context

/**
 * The tournament this device is in.
 *
 * Remembered because a tournament is not a screen, it is a thing you are part of for days. Without
 * this, backing out of the bracket was indistinguishable from never having been in it: the code
 * was held in a field, the field died with the activity, and the only way back was to have written
 * the code down. People do not write the code down. They back out to take a turn somewhere else
 * and expect the tournament to still be there, the same way an unfinished game is.
 *
 * One at a time, which matches how these are actually run — a group plays a cup, and the next one
 * starts when that one has finished. Entering a different code simply replaces it.
 */
object TournamentStore {

    private const val PREFS = "tournament"
    private const val KEY_CODE = "code"

    fun current(context: Context): String =
        prefs(context).getString(KEY_CODE, "").orEmpty()

    fun remember(context: Context, code: String) {
        prefs(context).edit().putString(KEY_CODE, code).apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_CODE).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
