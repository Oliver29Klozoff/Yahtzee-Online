package com.yahtzee.online.game

import android.content.Context
import androidx.core.graphics.ColorUtils
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
    private const val KEY_PIP_STYLE = "pip_style"
    private const val KEY_FINISH = "dice_finish"
    private const val KEY_SAVED = "saved_dice"
    private const val MAX_SAVED = 12

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

    /** A dice design the player built and named, so a custom colour is not lost to the next one. */
    data class SavedDie(val name: String, val color: Int, val pipStyle: PipStyle)

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
                SavedDie(
                    name = item.optString("name").ifEmpty { return@mapNotNull null },
                    color = item.optInt("color", DieTextureAtlas.DEFAULT_COLOR),
                    pipStyle = item.optString("pipStyle").takeIf { it.isNotEmpty() }
                        ?.let { name -> runCatching { PipStyle.valueOf(name) }.getOrNull() }
                    // Designs saved before styles existed stored a plain boolean.
                        ?: if (item.optBoolean("darkPips", true)) PipStyle.DARK else PipStyle.LIGHT
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Saves the current design under [name], replacing any design of the same name so re-saving
     * updates rather than duplicating. Oldest entries drop off past [MAX_SAVED].
     */
    fun saveDie(context: Context, name: String, color: Int, pipStyle: PipStyle) {
        val trimmed = name.trim().take(20)
        if (trimmed.isEmpty()) return
        val updated = (savedDice(context).filterNot { it.name.equals(trimmed, ignoreCase = true) } +
            SavedDie(trimmed, color, pipStyle)).takeLast(MAX_SAVED)
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
                    .put("pipStyle", it.pipStyle.name)
            )
        }
        prefs(context).edit().putString(KEY_SAVED, array.toString()).apply()
    }

    fun getColor(context: Context): Int =
        prefs(context).getInt(KEY_COLOR, DieTextureAtlas.DEFAULT_COLOR)

    fun setColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_COLOR, color).apply()
    }

    /**
     * How pips are coloured.
     *
     * [AUTO] is the default and picks per die from that die's own brightness, because a single
     * choice cannot suit every colour on the table: bot colours are spread right around the
     * wheel, so white pips vanish into a pale green or amber die while black pips disappear
     * into a navy one. The fixed options remain for anyone who prefers one look throughout.
     *
     * Local rather than synced: unlike the colour, which identifies whose turn it is and so has
     * to look the same to everyone, this is only about legibility on this screen.
     */
    enum class PipStyle(val label: String) {
        AUTO("Auto"),
        DARK("Dark"),
        LIGHT("White");

        /** Whether a die of [diceColor] should carry dark pips under this style. */
        fun darkFor(diceColor: Int): Boolean = when (this) {
            DARK -> true
            LIGHT -> false
            // Bright faces take dark pips and vice versa. The threshold sits above the midpoint
            // because a mid-tone die still reads better with light pips against a dark table.
            AUTO -> ColorUtils.calculateLuminance(diceColor) > 0.42
        }
    }

    /**
     * How the dice are made.
     *
     * [GLASS] is the original look — thick coloured glass, lit from the edges. [SOLID] is a
     * moulded plastic die: the same colour, shaded by plain light and shadow, with a soft
     * highlight instead of ignited edges. One shader draws both, with [gloss] scaling every
     * glass term, so they cannot drift apart.
     */
    enum class DiceFinish(val label: String, val gloss: Float) {
        GLASS("Glass", 1f),
        SOLID("Solid", 0f)
    }

    fun diceFinish(context: Context): DiceFinish {
        val name = prefs(context).getString(KEY_FINISH, DiceFinish.GLASS.name)
        return runCatching { DiceFinish.valueOf(name!!) }.getOrDefault(DiceFinish.GLASS)
    }

    fun setDiceFinish(context: Context, finish: DiceFinish) {
        prefs(context).edit().putString(KEY_FINISH, finish.name).apply()
    }

    fun pipStyle(context: Context): PipStyle {
        val name = prefs(context).getString(KEY_PIP_STYLE, PipStyle.AUTO.name)
        return runCatching { PipStyle.valueOf(name!!) }.getOrDefault(PipStyle.AUTO)
    }

    fun setPipStyle(context: Context, style: PipStyle) {
        prefs(context).edit().putString(KEY_PIP_STYLE, style.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
