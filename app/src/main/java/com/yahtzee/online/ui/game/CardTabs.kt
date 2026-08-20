package com.yahtzee.online.ui.game

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.scoresForCard

/**
 * The scorecard selector shown in multi-card rooms, letting a player choose which card a roll
 * should be scored on. Each tab reports how many of its thirteen categories are already filled,
 * which is the information the choice actually turns on.
 *
 * Hidden entirely in single-card games, where there is nothing to choose.
 *
 * Shared by the online [GameActivity] and the solo-vs-bots SoloGameActivity so the two cannot
 * drift apart.
 */
object CardTabs {

    /** @return the card index to use, clamped to what this room actually has. */
    fun render(
        context: Context,
        scroll: View,
        row: LinearLayout,
        state: GameState,
        viewedPlayerId: String,
        selectedCard: Int,
        onSelect: (Int) -> Unit
    ): Int {
        if (state.cardCount <= 1) {
            scroll.visibility = View.GONE
            return 0
        }
        scroll.visibility = View.VISIBLE
        val selected = selectedCard.coerceIn(0, state.cardCount - 1)

        row.removeAllViews()
        val density = context.resources.displayMetrics.density
        val viewed = state.players[viewedPlayerId]

        for (card in 0 until state.cardCount) {
            val filled = viewed?.scoresForCard(card)?.size ?: 0
            val isSelected = card == selected
            val tab = TextView(context).apply {
                text = context.getString(R.string.card_tab, card + 1, filled, Category.values().size)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(
                    (12 * density).toInt(), (7 * density).toInt(),
                    (12 * density).toInt(), (7 * density).toInt()
                )
                setTextColor(
                    if (isSelected) context.resources.getColor(R.color.background, context.theme)
                    else context.resources.getColor(R.color.text_muted, context.theme)
                )
                setBackgroundColor(
                    if (isSelected) context.resources.getColor(R.color.brand_primary, context.theme)
                    else context.resources.getColor(R.color.surface, context.theme)
                )
                setOnClickListener { onSelect(card) }
            }
            tab.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (8 * density).toInt() }
            row.addView(tab)
        }
        return selected
    }
}
