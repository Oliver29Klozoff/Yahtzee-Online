package com.yahtzee.online.ui.game

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import com.yahtzee.online.R
import com.yahtzee.online.dice3d.DieTextureAtlas
import com.yahtzee.online.game.GameState
import com.yahtzee.online.ui.ColorContrast

/**
 * The keep/reroll chips under the dice, drawn in whoever's dice colour is currently on the
 * table so the row belongs to the same player as the dice above it.
 *
 * A held chip is filled with that colour and an unheld one merely outlined in it, which reads
 * as picked-up versus left-on-the-table without needing a second colour. The number takes black
 * or white by what it sits on rather than the player's colour, so a black or very pale die does
 * not leave the value unreadable.
 */

/** The colour of whoever is rolling, falling back to the default for older clients. */
fun activeDiceColorOf(state: GameState): Int =
    state.players[state.currentPlayerId]?.diceColor?.takeIf { it != 0 }
        ?: DieTextureAtlas.DEFAULT_COLOR

fun styleHoldChip(chip: Button, held: Boolean, diceColor: Int) {
    val context: Context = chip.context
    val density = context.resources.displayMetrics.density
    val surface = context.resources.getColor(R.color.surface, context.theme)

    chip.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 10f * density
        setColor(if (held) diceColor else surface)
        setStroke((2 * density).toInt(), diceColor)
    }
    chip.setTextColor(
        if (held) ColorContrast.textOn(diceColor) else ColorContrast.textOn(surface)
    )
}
