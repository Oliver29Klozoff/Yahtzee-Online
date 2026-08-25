package com.yahtzee.online.ui.game

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
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

    /** The emoji against the sender's name. Big enough to be the thing you see, not the caption. */
    private const val EMOJI_SCALE = 2.4f

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
        lastSeen: Long
    ): Long {
        // Below zero means this screen has not seen the room yet. Whatever is already there is
        // history, not news — adopt it silently, or opening a game would replay the last thing
        // anyone said, possibly from days ago.
        if (lastSeen < 0L) return state.reactions.values.maxOfOrNull { it.second } ?: 0L

        // Your own included. Tapping an emoji and watching nothing happen reads as a control that
        // did not work, and seeing it fly is half the point of sending one.
        val newest = state.reactions.entries
            .filter { it.value.second > lastSeen }
            .maxByOrNull { it.value.second }
            ?: return lastSeen

        val name = state.players[newest.key]?.name.orEmpty()
        popup.text = label(popup, name, newest.value.first)
        popup.gravity = Gravity.CENTER
        animateIn(popup)

        handler.removeCallbacksAndMessages(popup)
        handler.postAtTime(
            { animateOut(popup) },
            popup,
            android.os.SystemClock.uptimeMillis() + SHOW_MILLIS
        )

        return newest.value.second
    }

    /**
     * The emoji is the message; the name is the footnote.
     *
     * Both share one view so the pair moves as a unit, with the emoji blown up several times the
     * label size — at body-text size an emoji is a character in a sentence, and the whole point of
     * a reaction is that you read it at a glance without reading it at all.
     */
    private fun label(popup: TextView, name: String, emoji: String): CharSequence {
        val text = popup.context.getString(R.string.reaction_from, name, emoji)
        val start = text.lastIndexOf(emoji)
        if (start < 0) return text
        return SpannableString(text).apply {
            setSpan(
                RelativeSizeSpan(EMOJI_SCALE),
                start,
                start + emoji.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /**
     * The reaction arrives with a bounce and drifts upward.
     *
     * Motion is what separates a reaction from a label. Appearing and disappearing in place reads
     * as text updating; something that lands, settles and floats away reads as someone reacting.
     * The overshoot on the way in is what gives it the pop.
     */
    private fun animateIn(popup: TextView) {
        popup.animate().cancel()
        popup.visibility = View.VISIBLE
        popup.alpha = 0f
        popup.scaleX = 0.4f
        popup.scaleY = 0.4f
        popup.translationY = popup.height.toFloat() * 0.4f

        popup.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(320)
            .setInterpolator(OvershootInterpolator(2.2f))
            .withEndAction {
                // A slow drift while it sits there, so it never looks frozen mid-display.
                popup.animate()
                    .translationY(-popup.height.toFloat() * 0.35f)
                    .setDuration(SHOW_MILLIS - 320)
                    .setInterpolator(LinearInterpolator())
                    .start()
            }
            .start()
    }

    private fun animateOut(popup: TextView) {
        popup.animate().cancel()
        popup.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(260)
            .withEndAction {
                popup.visibility = View.GONE
                popup.translationY = 0f
                popup.scaleX = 1f
                popup.scaleY = 1f
            }
            .start()
    }
}
