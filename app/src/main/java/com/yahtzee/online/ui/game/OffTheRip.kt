package com.yahtzee.online.ui.game

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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

    /** The score, sized to be seen rather than read. Matched to [ScoreAnnounce]'s headline. */
    private const val POINTS_SCALE = 2.1f

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

    /**
     * The same shout, called by a person rather than worked out from the scorecard.
     *
     * [qualifies] only fires for the player who did it, on a roll the rules agree was worth
     * remarking on. Plenty of rolls are worth remarking on that it will never fire for — somebody
     * else's, or one that fell just under the bar — and the only person who can judge that is
     * whoever is watching. So this says the same words with a name against them and no score,
     * because a called shout is an opinion about a roll rather than a reading of a box.
     */
    fun showCall(context: Context, popup: TextView, name: String) {
        popup.gravity = Gravity.CENTER
        EmojiPop.show(popup, callLabel(context, name), SHOW_MILLIS)
    }

    private fun callLabel(context: Context, name: String): CharSequence {
        val headline = context.getString(R.string.off_the_rip_call_headline)
        val text = context.getString(R.string.off_the_rip_call, headline, name)
        return SpannableString(text).apply {
            setSpan(RelativeSizeSpan(CALL_SCALE), 0, headline.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                0,
                headline.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** The words carry a called shout, so they take the size the score takes in an automatic one. */
    private const val CALL_SCALE = 1.6f

    /**
     * The score on its own line with the words beneath it.
     *
     * The dart used to sit on that first line, and it was the thing the eye landed on. Taking it
     * out would have left a shout with nothing to see and only something to read, so the number
     * takes the space instead — which says more anyway, being the part that differs between one
     * of these and the next. The dart is still the mark for this; it lives in the reaction row,
     * where it is a button somebody presses rather than decoration on a popup.
     *
     * Sized like [ScoreAnnounce]'s headline on purpose. They are the same popup, a moment apart,
     * and a number that changed size between them would read as two different kinds of thing.
     */
    private fun label(context: Context, category: Category, points: Int): CharSequence {
        val headline = points.toString()
        val text = context.getString(R.string.off_the_rip, points)
        val start = text.indexOf(headline)
        if (start < 0) return text
        return SpannableString(text).apply {
            setSpan(
                RelativeSizeSpan(POINTS_SCALE),
                start,
                start + headline.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                start,
                start + headline.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
