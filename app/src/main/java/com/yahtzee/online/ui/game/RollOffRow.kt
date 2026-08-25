package com.yahtzee.online.ui.game

import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.dice3d.DieTextureAtlas
import com.yahtzee.online.game.DicePreferences
import com.yahtzee.online.game.GameState

/**
 * Everyone's opening roll shown together as dice, one per player in their own colour, so the
 * roll-off can be read at a glance instead of a value at a time.
 *
 * Shared by the lobby and the solo screen: both decide turn order the same way, and a bot's
 * roll deserves to be seen next to the player's just as another person's would.
 */
object RollOffRow {

    fun render(
        context: Context,
        row: LinearLayout,
        state: GameState,
        localPlayerId: String,
        rolls: Map<String, Int> = state.openingRolls,
        order: List<String> = state.playerOrder,
        winnerId: String? = null
    ) {
        row.removeAllViews()
        val density = context.resources.displayMetrics.density
        // The winner is shown a little larger during the reveal.
        val dieSize = ((if (winnerId != null) 64 else 54) * density).toInt()
        val tied = state.openingRollTied.toSet()
        val highest = rolls.values.maxOrNull()

        order.forEach { id ->
            val player = state.players[id] ?: return@forEach
            val roll = rolls[id]
            val color = player.diceColor.takeIf { it != 0 } ?: DieTextureAtlas.DEFAULT_COLOR

            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            }

            // Yet to roll shows a dim placeholder, so the row has a slot for every player from
            // the start and fills in rather than growing.
            cell.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dieSize, dieSize)
                setImageBitmap(DieTextureAtlas.face(color, roll ?: 1))
                alpha = when {
                    roll == null -> 0.18f
                    winnerId != null && id != winnerId -> 0.45f
                    else -> 1f
                }
            })

            val awaitingReroll = id in tied
            val isLeader = if (winnerId != null) id == winnerId else roll != null && roll == highest

            cell.addView(TextView(context).apply {
                text = if (id == localPlayerId) context.getString(R.string.you_label) else player.name
                textSize = 12f
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(0, (5 * density).toInt(), 0, 0)
                setTextColor(
                    when {
                        awaitingReroll -> context.resources.getColor(R.color.timer_warn, context.theme)
                        // The leader is named in plain white rather than their dice colour, which
                        // would be unreadable for anyone playing a dark one. The die beside it
                        // already carries the colour.
                        isLeader -> context.resources.getColor(R.color.text_dark, context.theme)
                        else -> context.resources.getColor(R.color.text_muted, context.theme)
                    }
                )
            })
            row.addView(cell)
        }
    }
}
