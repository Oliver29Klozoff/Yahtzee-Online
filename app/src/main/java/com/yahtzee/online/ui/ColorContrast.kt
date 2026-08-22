package com.yahtzee.online.ui

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Keeps player colours legible when they are used as text or as a background.
 *
 * Dice colour doubles as a player's identity in the UI — names on the scorecard tabs, the
 * roll-off labels — but any colour the player likes on a die is not necessarily readable as
 * text. A near-black die is the clearest case: black-on-black in the label, and when that tab
 * is selected the colour becomes the background with dark text on top, so it disappears twice
 * over. Very pale colours have the same problem in reverse against a light background.
 *
 * Rather than restricting which colours may be chosen, the colour is nudged until it can
 * actually be read, keeping its hue so it still identifies the player.
 */
object ColorContrast {

    /** WCAG relative luminance. */
    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /**
     * [color] adjusted until it reads against [background], preserving its hue.
     *
     * Brightness is walked toward whichever end of the scale is away from the background, and
     * saturation eased off as it goes, because a fully saturated colour cannot get bright enough
     * to carry on its own. Gives up at the extremes rather than looping, so a colour that simply
     * cannot reach the target still comes back as legible as it can manage.
     */
    fun readableOn(color: Int, background: Int, minimumRatio: Double = 3.5): Int {
        if (contrastRatio(color, background) >= minimumRatio) return color

        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val lighten = luminance(background) < 0.5

        var best = color
        var steps = 0
        while (steps < 24) {
            hsv[2] = (hsv[2] + if (lighten) 0.06f else -0.06f).coerceIn(0f, 1f)
            // Pastel out slightly as it brightens; a vivid hue at full saturation tops out too
            // dark to ever meet the target.
            if (lighten) hsv[1] = (hsv[1] - 0.03f).coerceAtLeast(0.25f)
            best = Color.HSVToColor(hsv)
            if (contrastRatio(best, background) >= minimumRatio) return best
            if (hsv[2] <= 0f || hsv[2] >= 1f) break
            steps++
        }
        return best
    }

    /** Black or white, whichever can be read on top of [background]. */
    fun textOn(background: Int): Int =
        if (luminance(background) > 0.45) Color.parseColor("#0B0E12") else Color.WHITE
}
