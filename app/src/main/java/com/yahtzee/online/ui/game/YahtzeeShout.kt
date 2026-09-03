package com.yahtzee.online.ui.game

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.widget.TextView
import com.yahtzee.online.R

/**
 * The big one.
 *
 * Five of a kind is the thing everybody at a table looks up for, and until now the game barely
 * acknowledged it: a small banner on the roller's own screen suggesting where to put it, and
 * nothing at all on anybody else's. The person who rolled it knew; the people it was worth
 * showing off to found out later, from a number appearing in a column.
 *
 * So it is shouted, on every screen in the room and on the television, the moment the dice stop.
 * Deliberately separate from [YahtzeeBanner], which is advice about scoring and has to stay quiet
 * and useful — this is the opposite of quiet and is not useful at all, which is the point.
 */
object YahtzeeShout {

    /** Long enough to be enjoyed, short enough not to sit over the decision that follows it. */
    const val SHOW_MILLIS = 2600L

    /** The word carries it; the name underneath is the detail. */
    private const val WORD_SCALE = 1.7f

    /**
     * Shows it in [shout], naming [playerName] unless it was you.
     *
     * Your own needs no name — you are holding the phone that threw it. Somebody else's does,
     * because on a screen showing four scorecards the interesting half of the news is who.
     */
    fun show(context: Context, shout: TextView, playerName: String, isYou: Boolean) {
        shout.gravity = Gravity.CENTER
        EmojiPop.show(shout, label(context, playerName, isYou), SHOW_MILLIS)
    }

    private fun label(context: Context, playerName: String, isYou: Boolean): CharSequence {
        val word = context.getString(R.string.yahtzee_shout)
        if (isYou || playerName.isEmpty()) {
            return SpannableString(word).apply {
                setSpan(RelativeSizeSpan(WORD_SCALE), 0, word.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val text = context.getString(R.string.yahtzee_shout_by, word, playerName)
        return SpannableString(text).apply {
            setSpan(RelativeSizeSpan(WORD_SCALE), 0, word.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
