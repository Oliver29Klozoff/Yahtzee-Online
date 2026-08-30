package com.yahtzee.online.ui.game

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
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

    /**
     * How much bigger the emoji is than the name under it.
     *
     * Large on purpose. At anything modest the emoji reads as a character inside a sentence, and
     * the whole point is that it is seen rather than read — it has to carry across a table from
     * someone else's phone, at a glance, while the reader is looking at their dice.
     */
    private const val EMOJI_SCALE = 3.6f

    /** Builds the row of buttons once. [onPick] sends; the room does the rest. */
    fun buildRow(context: Context, row: LinearLayout, onPick: (String) -> Unit) {
        if (row.childCount > 0) return
        val density = context.resources.displayMetrics.density

        EMOJI.forEach { emoji ->
            // AppCompatButton rather than Button.
            //
            // EmojiCompat is what supplies the current emoji set — downloaded, so a phone on an
            // older Android still draws the modern glyphs instead of whatever shipped with it,
            // and never draws an empty box for one it has never heard of. It reaches views
            // through AppCompat, and a plain Button built in code has not been through AppCompat's
            // inflater, so these were being drawn by the system font and missing all of it.
            val button = AppCompatButton(context).apply {
                text = emoji
                textSize = 26f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                includeFontPadding = false
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                background = null
                setOnClickListener {
                    // A tap should feel like it did something even before the room answers.
                    animate().cancel()
                    scaleX = 0.8f
                    scaleY = 0.8f
                    animate().scaleX(1f).scaleY(1f).setDuration(180)
                        .setInterpolator(OvershootInterpolator(3f)).start()
                    onPick(emoji)
                }
            }
            row.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (48 * density).toInt()
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
        popup.gravity = Gravity.CENTER
        EmojiPop.show(popup, label(popup, name, newest.value.first), SHOW_MILLIS)

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
        // Emoji on its own line above the name, rather than beside it. Side by side, the name
        // sets the line height and the emoji can only grow so far before the row looks broken;
        // stacked, it can be as large as it likes and the name reads as a caption under it.
        val text = popup.context.getString(R.string.reaction_from, emoji, name)
        val end = emoji.length
        return SpannableString(text).apply {
            setSpan(
                RelativeSizeSpan(EMOJI_SCALE),
                0,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

}
