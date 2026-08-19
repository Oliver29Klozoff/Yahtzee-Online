package com.yahtzee.online.game

import android.content.Context
import com.yahtzee.online.dice3d.DieTextureAtlas

/**
 * Stores the player's chosen dice colour. Local-only and per-device — dice colour is a personal
 * display preference, so it is deliberately not synced through the game state; every player in
 * a room sees their own choice.
 */
object DicePreferences {

    private const val PREFS = "dice_prefs"
    private const val KEY_COLOR = "dice_color"

    /** Selectable colours, paired with the label shown in Settings. */
    val PALETTE: List<Pair<String, Int>> = listOf(
        "Cobalt" to 0xFF3D7FFF.toInt(),
        "Crimson" to 0xFFE23D4B.toInt(),
        "Emerald" to 0xFF16B972.toInt(),
        "Amethyst" to 0xFF9B5DE5.toInt(),
        "Amber" to 0xFFF5A524.toInt(),
        "Cyan" to 0xFF12C2D8.toInt(),
        "Rose" to 0xFFF25FA6.toInt(),
        "Slate" to 0xFF7A8699.toInt()
    )

    fun getColor(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_COLOR, DieTextureAtlas.DEFAULT_COLOR)

    fun setColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_COLOR, color)
            .apply()
    }
}
