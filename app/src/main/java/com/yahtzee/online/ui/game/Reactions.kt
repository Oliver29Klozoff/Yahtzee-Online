package com.yahtzee.online.ui.game

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.game.GameState

/**
 * Quick reactions between players in an online game.
 *
 * A turn-a-day game is silent by nature — the whole point of playing someone rather than a bot is
 * lost if the only thing that ever arrives is a number on a scorecard. These are deliberately not
 * a chat: a fixed handful of taps needs no keyboard, no moderation, and cannot say anything worth
 * reporting, which for a game strangers can join is the difference between shipping it and not.
 */
object Reactions {

    /** Small enough to fit a phone width, and broad enough to cover most of what a roll deserves. */
    val EMOJI = listOf("👏", "😂", "😱", "🔥", "🎲", "😭")

    /** How long a reaction stays on screen before it fades. */
    private const val SHOW_MILLIS = 2600L

    private val handler = Handler(Looper.getMainLooper())

    /** Builds the row of buttons once. [onPick] sends; the room does the rest. */
    fun buildRow(context: Context, row: LinearLayout, onPick: (String) -> Unit) {
        if (row.childCount > 0) return
        val density = context.resources.displayMetrics.density

        EMOJI.forEach { emoji ->
            val button = Button(context).apply {
                text = emoji
                textSize = 20f
                minWidth = 0
                minimumWidth = 0
                setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                background = null
                setOnClickListener { onPick(emoji) }
            }
            row.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (44 * density).toInt()
                )
            )
        }
    }

    /**
     * Shows whatever has arrived since last time.
     *
     * [lastSeen] is the timestamp of the newest reaction already shown, and the return value
     * replaces it. Comparing timestamps rather than values is what lets the same emoji sent twice
     * register twice — and what stops every unrelated update to the room replaying the last one.
     */
    fun render(
        popup: TextView,
        state: GameState,
        localPlayerId: String,
        lastSeen: Long
    ): Long {
        // Below zero means this screen has not seen the room yet. Whatever is already there is
        // history, not news — adopt it silently, or opening a game would replay the last thing
        // anyone said, possibly from days ago.
        if (lastSeen < 0L) return state.reactions.values.maxOfOrNull { it.second } ?: 0L

        val newest = state.reactions.entries
            .filter { it.value.second > lastSeen }
            // Your own reaction is not news to you; you just tapped it.
            .filterNot { it.key == localPlayerId }
            .maxByOrNull { it.value.second }
            ?: return lastSeen

        val name = state.players[newest.key]?.name.orEmpty()
        popup.text = popup.context.getString(R.string.reaction_from, name, newest.value.first)
        popup.visibility = View.VISIBLE
        popup.gravity = Gravity.CENTER

        handler.removeCallbacksAndMessages(popup)
        handler.postAtTime({ popup.visibility = View.GONE }, popup, android.os.SystemClock.uptimeMillis() + SHOW_MILLIS)

        return newest.value.second
    }
}
