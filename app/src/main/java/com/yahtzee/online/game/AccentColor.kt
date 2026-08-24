package com.yahtzee.online.game

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.yahtzee.online.R
import kotlin.math.abs

/**
 * The colour the app itself is trimmed in — buttons, links, the highlight on your own name.
 *
 * Any colour can be chosen, not just a listed one, which a theme alone cannot do: a theme has to
 * exist as a compiled style. So a theme still goes on first, the nearest of a handful of presets,
 * and then [retint] walks the inflated view and replaces that theme's colour with the exact one.
 *
 * The walk works because every accent in the app is written as `?attr/colorPrimary`. That gives
 * one value to look for, so recolouring is an exact match rather than a guess about which blue
 * meant what — and a view that was never accented is left alone.
 */
object AccentColor {

    private const val PREFS = "accent_color"
    private const val KEY_COLOR = "accent_value"

    /** Starting points for the picker. The first is the app's original blue and is the default. */
    val PALETTE: List<Pair<String, Int>> = listOf(
        "Cobalt" to 0xFF3D7FFF.toInt(),
        "Emerald" to 0xFF16B972.toInt(),
        "Amber" to 0xFFF5A524.toInt(),
        "Crimson" to 0xFFE23D4B.toInt(),
        "Amethyst" to 0xFF9B5DE5.toInt(),
        "Cyan" to 0xFF12C2D8.toInt()
    )

    /** Theme per preset, used as the base a custom colour is painted over. */
    private val THEMES = listOf(
        R.style.Theme_YahtzeeOnline,
        R.style.Theme_YahtzeeOnline_Emerald,
        R.style.Theme_YahtzeeOnline_Amber,
        R.style.Theme_YahtzeeOnline_Crimson,
        R.style.Theme_YahtzeeOnline_Amethyst,
        R.style.Theme_YahtzeeOnline_Cyan
    )

    fun getColor(context: Context): Int =
        prefs(context).getInt(KEY_COLOR, PALETTE.first().second)

    fun setColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_COLOR, color).apply()
    }

    /**
     * The preset theme closest to [color] in hue.
     *
     * Matters for the parts no walk can reach — a dialog's buttons, a text cursor — which the
     * platform draws from the theme before this code sees them. Close is enough there; the
     * exact colour lands on everything in the layout itself.
     */
    fun themeFor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val target = hsv[0]

        var bestIndex = 0
        var bestDistance = Float.MAX_VALUE
        PALETTE.forEachIndexed { index, (_, preset) ->
            val presetHsv = FloatArray(3)
            Color.colorToHSV(preset, presetHsv)
            // Hue is a circle, so 350 and 10 are twenty degrees apart, not three hundred.
            val raw = abs(presetHsv[0] - target)
            val distance = minOf(raw, 360f - raw)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return THEMES[bestIndex]
    }

    /** The accent as chosen. Code drawing a colour and XML drawing one then agree exactly. */
    fun resolve(context: Context): Int = getColor(context)

    /**
     * The accent laid faintly over the card surface, for the filled badge behind a score that is
     * still available.
     *
     * Derived rather than listed, which now matters more than ever: with any colour selectable
     * there is no fixed set of badge colours that could have been listed in the first place.
     */
    fun badgeBackground(context: Context): Int = androidx.core.graphics.ColorUtils.blendARGB(
        context.getColor(R.color.surface),
        resolve(context),
        BADGE_BLEND
    )

    /** How much accent is mixed into the badge: enough to read as tinted, not as a coloured tile. */
    private const val BADGE_BLEND = 0.16f

    /**
     * Replaces the theme's accent with the chosen one throughout [root].
     *
     * [themeColor] is what the base theme resolved `?attr/colorPrimary` to, which is the value
     * every accented view is currently wearing.
     */
    fun retint(root: View, themeColor: Int, accent: Int) {
        val tint = ColorStateList.valueOf(accent)
        walk(root) { view ->
            // Sliders and spinners are tinted by the theme itself, so they carry no value this
            // walk could match on and were the one thing left wearing the old colour. That made
            // dragging the accent sliders look broken above all else: every slider in the app is
            // accented, so the control under the finger was the last thing to change. They are
            // set outright rather than matched.
            when (view) {
                is SeekBar -> {
                    view.progressTintList = tint
                    view.thumbTintList = tint
                }
                is ProgressBar -> view.progressTintList = tint
            }

            if (themeColor == accent) return@walk
            if (view is TextView && view.textColors?.defaultColor == themeColor) {
                view.setTextColor(accent)
            }
            if (view.backgroundTintList?.defaultColor == themeColor) {
                view.backgroundTintList = ColorStateList.valueOf(accent)
            }
        }
    }

    /** What the current theme resolves the accent attribute to, before any retinting. */
    fun themeColorOf(context: Context): Int {
        val typed = TypedValue()
        val found = context.theme.resolveAttribute(
            androidx.appcompat.R.attr.colorPrimary, typed, true
        )
        if (!found) return PALETTE.first().second
        return if (typed.resourceId != 0) context.getColor(typed.resourceId) else typed.data
    }

    private fun walk(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), action)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
