package com.yahtzee.online.game

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
 * Accented views are marked with a tag in the layout rather than found by comparing colours.
 * Matching on colour looked tidier and failed quietly: anything storing its tint as a state list
 * or reporting it back a shade off simply never matched, and a miss was indistinguishable from
 * the whole feature not working.
 */
object AccentColor {

    private const val PREFS = "accent_color"
    private const val KEY_COLOR = "accent_value"
    private const val KEY_GRADIENT = "accent_gradient"

    /** How far round the hue wheel a gradient's dark end sits from the accent, in degrees. */
    private const val HUE_SHIFT = 16f

    /** Layout tags marking what carries the accent, so recolouring never has to guess. */
    private const val TAG_TEXT = "accentText"
    private const val TAG_BACKGROUND = "accentBg"

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
     * Whether buttons are filled with a sweep of the accent rather than a flat block of it.
     *
     * Off by default, and deliberately a setting rather than the new look: a gradient reads as
     * livelier to some people and as noise to others, and the one thing it must not do is make
     * the accent harder to recognise as the accent. The sweep therefore starts *at* the chosen
     * colour and only deepens from there — see [gradientEnd] — so a cobalt app still looks
     * cobalt rather than turning into some third colour nobody picked.
     */
    fun gradient(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GRADIENT, false)

    fun setGradient(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_GRADIENT, on).apply()
    }

    /**
     * The far end of a gradient button: the same colour taken deeper.
     *
     * Derived rather than paired, because the accent can be any colour at all and there is no
     * list of partners that could have been written down. A small nudge round the hue wheel
     * stops the fade reading as a plain shadow, and the vividness is lifted a touch to keep the
     * dark end from going muddy; the brightness floor keeps it from reaching black, where a
     * button's bottom edge would disappear into the page.
     */
    fun gradientEnd(accent: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        hsv[0] = (hsv[0] + HUE_SHIFT) % 360f
        hsv[1] = (hsv[1] * 1.08f).coerceAtMost(1f)
        hsv[2] = (hsv[2] * 0.58f).coerceAtLeast(0.2f)
        return Color.HSVToColor(Color.alpha(accent), hsv)
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
        // Read once for the whole walk rather than per view: it is a single app-wide setting, and
        // a preference lookup on every view of every screen is a cost with nothing to show for it.
        val useGradient = gradient(root.context)
        walk(root) { view ->
            // Sliders and spinners are tinted by the theme itself, so they carry no value worth
            // comparing and are simply set.
            when (view) {
                is SeekBar -> {
                    view.progressTintList = tint
                    view.thumbTintList = tint
                }
                is ProgressBar -> view.progressTintList = tint
            }

            // Everything else says outright that it is accented. Recolouring by comparing each
            // view against the theme's colour was the original approach and it failed quietly:
            // a widget that stores its tint as a state list, or reports it back a shade off,
            // simply never matched, and the miss looked identical to the feature not working.
            // A tag cannot miss.
            when (view.tag) {
                TAG_TEXT -> (view as? TextView)?.setTextColor(accent)
                TAG_BACKGROUND -> paintBackground(view, accent, useGradient)
            }

            // Anything untagged still gets the old treatment, so a view added later without a
            // tag is merely no worse off than before rather than stuck on the wrong colour.
            if (themeColor == accent || view.tag != null) return@walk
            if (view is TextView && view.textColors?.defaultColor == themeColor) {
                view.setTextColor(accent)
            }
            if (view.backgroundTintList?.defaultColor == themeColor) {
                view.backgroundTintList = tint
            }
        }
    }

    /**
     * Fills one accented view, as a flat block of [accent] or as a sweep of it.
     *
     * Only buttons ever take the sweep. The same tag is carried by text boxes — the room-code
     * field on the front screen is one — and a field whose fill shades from one end to the other
     * is harder to read for no gain: the gradient is there to give a *pressable* thing some
     * weight, and a box you type into is not that.
     */
    private fun paintBackground(view: View, accent: Int, gradient: Boolean) {
        if (!gradient || view !is Button) {
            // Covers switching the setting back off as well as the ordinary flat case: the
            // button's own drawable goes back on before the tint does.
            restoreOriginal(view)
            view.backgroundTintList = ColorStateList.valueOf(accent)
            return
        }
        // Stashed on the first pass only. A later retint — dragging the accent sliders — paints a
        // fresh gradient over a gradient, and overwriting the stash with one would lose the only
        // copy of the real background.
        if (view.getTag(R.id.accent_original_bg) == null) {
            view.setTag(R.id.accent_original_bg, view.background)
        }
        view.background = gradientFill(view, accent)
        // The gradient carries its own colours. Left in place, the tint would multiply through
        // both ends and flatten the sweep back into one colour.
        view.backgroundTintList = null
    }

    private fun restoreOriginal(view: View) {
        val original = view.getTag(R.id.accent_original_bg) as? Drawable ?: return
        view.background = original
        view.setTag(R.id.accent_original_bg, null)
    }

    /**
     * A button fill that shades from [accent] into [gradientEnd], with the press and disabled
     * states the platform drawable it replaces would have provided.
     *
     * The insets and the corner are the framework's own button metrics rather than a look of our
     * own choosing. They are what gives a stack of buttons the gaps between them, so a fill
     * without them would leave the gradient buttons visibly larger than every other button in
     * the app and touching their neighbours — the setting is meant to change the colour of a
     * button, not its size.
     */
    private fun gradientFill(view: View, accent: Int): Drawable {
        val density = view.resources.displayMetrics.density
        val radius = CORNER_DP * density
        val sweep = {
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(accent, gradientEnd(accent))
            ).apply { cornerRadius = radius }
        }

        // A mask rather than an unbounded ripple, so the touch feedback stops at the rounded
        // corner instead of spilling out past the button's edge.
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radius
        }
        val pressable = RippleDrawable(
            ColorStateList.valueOf(Color.argb(0x4D, 0xFF, 0xFF, 0xFF)),
            sweep(),
            mask
        )
        // Disabled buttons are common here — the update check disables its own button while it
        // runs — and a gradient that ignored the state would leave them looking live.
        val disabled = sweep().apply { alpha = DISABLED_ALPHA }

        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), disabled)
            addState(IntArray(0), pressable)
        }
        val horizontal = (INSET_HORIZONTAL_DP * density).toInt()
        val vertical = (INSET_VERTICAL_DP * density).toInt()
        return InsetDrawable(states, horizontal, vertical, horizontal, vertical)
    }

    /** The framework's button metrics, matched so a gradient button is the same size as a flat one. */
    private const val CORNER_DP = 4f
    private const val INSET_HORIZONTAL_DP = 4f
    private const val INSET_VERTICAL_DP = 6f

    /** How much of a disabled button's fill still shows, roughly the platform's own dimming. */
    private const val DISABLED_ALPHA = 0x4D

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
