package com.yahtzee.online.game

import android.content.Context
import android.util.TypedValue
import androidx.annotation.StyleRes
import com.yahtzee.online.R

/**
 * The colour the app itself is trimmed in — buttons, links, the highlight on your own name.
 *
 * Applied as a theme rather than by tinting views one at a time. A theme reaches everything that
 * asks for colorPrimary, including the parts of a Button that are drawn by the platform and were
 * never going to be reachable from this code, and it costs nothing per screen. The price is that
 * the choice is a fixed set rather than a free colour picker, since each one has to exist as a
 * style; the dice, where a free picker genuinely matters, keep theirs.
 */
object AccentColor {

    private const val PREFS = "accent_color"
    private const val KEY_ACCENT = "accent"

    /**
     * Each accent is a value, a readable name, and the theme that carries it. The first is the
     * app's original blue and stays the default.
     */
    enum class Accent(
        val label: String,
        val value: Int,
        @StyleRes val theme: Int
    ) {
        COBALT("Cobalt", 0xFF3D7FFF.toInt(), R.style.Theme_YahtzeeOnline),
        EMERALD("Emerald", 0xFF16B972.toInt(), R.style.Theme_YahtzeeOnline_Emerald),
        AMBER("Amber", 0xFFF5A524.toInt(), R.style.Theme_YahtzeeOnline_Amber),
        CRIMSON("Crimson", 0xFFE23D4B.toInt(), R.style.Theme_YahtzeeOnline_Crimson),
        AMETHYST("Amethyst", 0xFF9B5DE5.toInt(), R.style.Theme_YahtzeeOnline_Amethyst),
        CYAN("Cyan", 0xFF12C2D8.toInt(), R.style.Theme_YahtzeeOnline_Cyan)
    }

    fun current(context: Context): Accent {
        val stored = prefs(context).getString(KEY_ACCENT, Accent.COBALT.name)
        return runCatching { Accent.valueOf(stored!!) }.getOrDefault(Accent.COBALT)
    }

    fun set(context: Context, accent: Accent) {
        prefs(context).edit().putString(KEY_ACCENT, accent.name).apply()
    }

    /**
     * The accent as it currently resolves on [context]'s theme.
     *
     * Read from the theme rather than from the stored value so that code drawing a colour and
     * XML drawing one can never disagree — anything built before a change still repaints in the
     * accent its own screen was themed with.
     */
    fun resolve(context: Context): Int {
        val typed = TypedValue()
        return if (context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typed, true)) {
            if (typed.resourceId != 0) context.getColor(typed.resourceId) else typed.data
        } else {
            Accent.COBALT.value
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
