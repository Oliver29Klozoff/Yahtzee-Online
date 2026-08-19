package com.yahtzee.online.ui.game

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.grandTotalAllCards

/**
 * The row of per-player tabs sitting above the scorecard, so a player can switch which
 * opponent's card they're looking at mid-game (previously only your own card was ever
 * visible). The tab whose turn it is carries a dot marker, and the card currently being
 * viewed is highlighted; each tab shows that player's running grand total.
 *
 * Shared by the online [GameActivity] and the solo-vs-bots SoloGameActivity, which render the
 * same layout from the same GameState shape.
 */
object ScorecardTabs {

    fun render(
        context: Context,
        row: LinearLayout,
        state: GameState,
        localPlayerId: String,
        viewingPlayerId: String,
        onSelect: (String) -> Unit
    ) {
        row.removeAllViews()
        val density = context.resources.displayMetrics.density

        state.playerOrder.forEach { id ->
            val player = state.players[id] ?: return@forEach
            val total = player.grandTotalAllCards(state.cardCount)

            val isViewing = id == viewingPlayerId
            val isTheirTurn = id == state.currentPlayerId
            val label = buildString {
                if (isTheirTurn) append("● ")
                append(if (id == localPlayerId) "You" else player.name)
                append("   ")
                append(total)
            }

            // Tint each tab with that player's own dice colour, so the colour reads as their
            // identity at the table rather than only showing up on the dice during their turn.
            val playerColor = player.diceColor.takeIf { it != 0 }
                ?: context.resources.getColor(R.color.brand_primary, context.theme)

            val tab = TextView(context).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(
                    (14 * density).toInt(), (8 * density).toInt(),
                    (14 * density).toInt(), (8 * density).toInt()
                )
                setTextColor(
                    if (isViewing) context.resources.getColor(R.color.background, context.theme)
                    else playerColor
                )
                setBackgroundColor(
                    if (isViewing) playerColor
                    else context.resources.getColor(R.color.surface, context.theme)
                )
                setOnClickListener { onSelect(id) }
            }
            tab.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (8 * density).toInt() }
            row.addView(tab)
        }
    }
}
