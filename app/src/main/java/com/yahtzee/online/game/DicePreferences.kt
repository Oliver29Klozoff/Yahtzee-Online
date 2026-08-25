package com.yahtzee.online.game

import android.content.Context
import com.yahtzee.online.dice3d.DieTextureAtlas
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores the player's chosen dice colour. Local-only and per-device — dice colour is a personal
 * display preference, so it is deliberately not synced through the game state; every player in
 * a room sees their own choice.
 */
object DicePreferences {

    private const val PREFS = "dice_prefs"
    private const val KEY_COLOR = "dice_color"
    private const val KEY_SAVED = "saved_dice"
    private const val MAX_SAVED = 12

    /**
     * Selectable colours, paired with the label shown in Settings.
     *
     * Classic comes first because it is the die everyone already knows. It needs nothing special
     * beyond the colour: every die is moulded plastic now, and Auto pips read its brightness and
     * come out black.
     *
     * Bone rather than pure white, which glares against a dark table and leaves no room for the
     * highlight to show.
     */
    val PALETTE: List<Pair<String, Int>> = listOf(
        "Classic" to 0xFFF3F1EC.toInt(),
        "Cobalt" to 0xFF3D7FFF.toInt(),
        "Crimson" to 0xFFE23D4B.toInt(),
        "Emerald" to 0xFF16B972.toInt(),
        "Amethyst" to 0xFF9B5DE5.toInt(),
        "Amber" to 0xFFF5A524.toInt(),
        "Cyan" to 0xFF12C2D8.toInt(),
        "Rose" to 0xFFF25FA6.toInt(),
        "Slate" to 0xFF7A8699.toInt()
    )

    /** A dice design the player built and named, so a custom colour is not lost to the next one. */
    data class SavedDie(val name: String, val color: Int)

    /**
     * Saved designs, oldest first.
     *
     * Stored as JSON in a single preference rather than one key per die: the list is read and
     * written whole every time, and a flat key scheme would need its own index to enumerate.
     */
    fun savedDice(context: Context): List<SavedDie> {
        val raw = prefs(context).getString(KEY_SAVED, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                // Designs saved when pips were a choice carry a style here too; it is read past,
                // since the colour now decides its own pips.
                SavedDie(
                    name = item.optString("name").ifEmpty { return@mapNotNull null },
                    color = item.optInt("color", DieTextureAtlas.DEFAULT_COLOR)
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Saves the current design under [name], replacing any design of the same name so re-saving
     * updates rather than duplicating. Oldest entries drop off past [MAX_SAVED].
     */
    fun saveDie(context: Context, name: String, color: Int) {
        val trimmed = name.trim().take(20)
        if (trimmed.isEmpty()) return
        val updated = (savedDice(context).filterNot { it.name.equals(trimmed, ignoreCase = true) } +
            SavedDie(trimmed, color)).takeLast(MAX_SAVED)
        writeSaved(context, updated)
    }

    fun deleteSavedDie(context: Context, name: String) {
        writeSaved(context, savedDice(context).filterNot { it.name == name })
    }

    private fun writeSaved(context: Context, dice: List<SavedDie>) {
        val array = JSONArray()
        dice.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("color", it.color)
            )
        }
        prefs(context).edit().putString(KEY_SAVED, array.toString()).apply()
    }

    fun getColor(context: Context): Int =
        prefs(context).getInt(KEY_COLOR, DieTextureAtlas.DEFAULT_COLOR)

    fun setColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_COLOR, color).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
