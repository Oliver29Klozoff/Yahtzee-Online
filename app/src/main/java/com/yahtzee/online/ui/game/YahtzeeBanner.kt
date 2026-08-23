package com.yahtzee.online.ui.game

import android.content.Context
import android.view.View
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.game.AccentColor
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.YahtzeeState
import com.yahtzee.online.game.yahtzeeStateFor

/**
 * The line above the hold row that says what a Yahtzee on the table is worth.
 *
 * Shared by the online and solo screens because the rule is the same in both, and because the
 * complaint it answers — rolling a second Yahtzee and seeing no sign of the hundred points —
 * applies equally wherever the game is played.
 */
object YahtzeeBanner {

    /**
     * Shows the banner for [playerId], or hides it when there is nothing to say.
     *
     * [suppress] hides it while the dice are still tumbling, matching the hold chips: the result
     * of a roll should not be readable before the dice have actually landed on it.
     */
    fun render(
        context: Context,
        banner: TextView,
        state: GameState,
        playerId: String,
        isMyTurn: Boolean,
        suppress: Boolean
    ) {
        val yahtzee = if (isMyTurn && !suppress) state.yahtzeeStateFor(playerId) else YahtzeeState.NONE

        val text = when (yahtzee) {
            YahtzeeState.NONE -> null
            YahtzeeState.FIRST -> R.string.yahtzee_first
            YahtzeeState.BONUS -> R.string.yahtzee_bonus_ready
            YahtzeeState.FORFEITED -> R.string.yahtzee_forfeited
        }

        if (text == null) {
            banner.visibility = View.GONE
            return
        }

        banner.visibility = View.VISIBLE
        banner.setText(text)
        // A forfeited Yahtzee is a warning, not an opportunity, so it stays amber rather than
        // being dressed up in the accent like a bonus that is actually payable.
        val forfeited = yahtzee == YahtzeeState.FORFEITED
        banner.setTextColor(
            if (forfeited) context.resources.getColor(R.color.timer_warn, context.theme)
            else AccentColor.resolve(context)
        )
        banner.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 10f * context.resources.displayMetrics.density
            setColor(AccentColor.badgeBackground(context))
        }
    }
}
