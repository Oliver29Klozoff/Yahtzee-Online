package com.yahtzee.online.ui.game

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.Button
import com.yahtzee.online.R
import com.yahtzee.online.dice3d.DieTextureAtlas
import com.yahtzee.online.game.GameState
import com.yahtzee.online.ui.ColorContrast

/**
 * The keep/reroll chips under the dice.
 *
 * Both states are outlined in whoever's dice colour is currently on the table, so the row
 * belongs to the same player as the dice above it. A kept die fills solid white with black
 * text — the strongest contrast available, and deliberately not the player's colour, since a
 * dark one would make the kept dice the hardest to pick out rather than the easiest.
 */

/** The colour of whoever is rolling, falling back to the default for older clients. */
fun activeDiceColorOf(state: GameState): Int =
    state.players[state.currentPlayerId]?.diceColor?.takeIf { it != 0 }
        ?: DieTextureAtlas.DEFAULT_COLOR

/**
 * The value on a chip, a good deal larger than a button's ordinary 14sp.
 *
 * These are not labels, they are dice: the number is the entire content, it is read at a glance
 * across five tiles while deciding what to keep, and it is competing with a coloured outline
 * around it for attention. At the default size it lost.
 */
private const val VALUE_TEXT_SP = 20f

fun styleHoldChip(chip: Button, held: Boolean, diceColor: Int) {
    val context: Context = chip.context
    val density = context.resources.displayMetrics.density
    val surface = context.resources.getColor(R.color.surface, context.theme)

    chip.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 10f * density
        setColor(if (held) android.graphics.Color.WHITE else surface)
        setStroke((2 * density).toInt(), diceColor)
    }
    chip.setTextColor(
        if (held) android.graphics.Color.BLACK else ColorContrast.textOn(surface)
    )
    chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, VALUE_TEXT_SP)
    chip.setTypeface(chip.typeface, Typeface.BOLD)
}
