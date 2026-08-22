package com.yahtzee.online.ui.game

import android.content.Context
import android.widget.Toast
import com.yahtzee.online.R
import com.yahtzee.online.game.AppSettings
import com.yahtzee.online.game.Category

/**
 * The optional double-tap guard before a score is committed.
 *
 * Tracks the card as well as the category: with several cards in play, confirming by category
 * alone would let a tap on card one be completed by a tap on the same row of card two, quietly
 * scoring somewhere the player never chose.
 *
 * The pending tap is cleared whenever the dice change or the turn moves on, so a confirmation
 * cannot be left hanging and then satisfied much later against a completely different roll.
 */
class ScoreConfirm(private val context: Context) {

    private var pending: Pair<Int, Category>? = null

    /**
     * @return true when the tap was consumed as a first tap and the caller should NOT score yet.
     */
    fun consumesTap(card: Int, category: Category): Boolean {
        if (!AppSettings.confirmScoring(context)) return false
        if (pending == card to category) {
            pending = null
            return false
        }
        pending = card to category
        Toast.makeText(
            context,
            context.getString(R.string.confirm_score, category.label),
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    fun reset() {
        pending = null
    }
}
