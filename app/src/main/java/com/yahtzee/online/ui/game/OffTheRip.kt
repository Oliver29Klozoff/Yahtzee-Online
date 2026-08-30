package com.yahtzee.online.ui.game

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.Scoring

/**
 * Marks a category scored straight off the opening roll, without spending a reroll.
 *
 * Nothing in the game acknowledged this. A full house that turns up on the first throw and one
 * assembled over three rolls are written into the same box for the same points, and the scorecard
 * afterwards cannot tell you which it was — so the best thing that happens in a turn passed
 * without comment. It is worth calling out precisely because it is the one outcome that is
 * entirely luck and entirely satisfying.
 *
 * Deliberately not celebrated for everything. Taking a zero off the first roll, or dumping three
 * points into Ones because the roll was a write-off, is not an achievement and saying so would
 * make the whole thing meaningless within a game or two.
 */
object OffTheRip {

    /** How long the shout stays up. Shorter than a reaction — it is punctuation, not a message. */
    private const val SHOW_MILLIS = 1900L

    /** Matched to a reaction's, so the two shouts carry the same weight on the same screen. */
    private const val EMOJI_SCALE = 3.6f

    /**
     * Below this a box is being used as a dustbin rather than being hit. Scoring three in the
     * upper section off the rip is not a moment, and treating it as one cheapens the ones that
     * are.
     */
    private const val MIN_POINTS = 20

    /**
     * Whether scoring [category] with [dice] after [rollsUsed] rolls deserves the shout.
     *
     * The category has to be worth something in its own right, so a Yahtzee, a large straight or
     * a full house always qualifies while a handful of twos never does.
     */
    fun qualifies(rollsUsed: Int, category: Category, dice: List<Int>): Boolean {
        if (rollsUsed != 1) return false
        return Scoring.score(category, dice) >= MIN_POINTS
    }

    /** Shows it, reusing the popup and the pop the reactions already use. */
    fun show(context: Context, popup: TextView, category: Category, points: Int) {
        popup.gravity = Gravity.CENTER
        EmojiPop.show(popup, label(context, category, points), SHOW_MILLIS)
    }

    private fun label(context: Context, category: Category, points: Int): CharSequence {
        val emoji = context.getString(R.string.off_the_rip_emoji)
        val text = context.getString(R.string.off_the_rip, emoji, points)
        val start = text.indexOf(emoji)
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
}
