package com.yahtzee.online.ui.game

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.TextView
import com.yahtzee.online.R
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.ScoreKey

/**
 * Says what the player before you just did with their roll.
 *
 * Watching someone else's turn, the only sign anything happened was a number quietly appearing in
 * a scorecard you were probably not looking at, and the turn moving on. Whether they took a
 * Yahtzee or threw the roll away in Ones — the two things that most change how you play your own
 * turn — went by without a word.
 *
 * Worked out by comparing one snapshot of the room to the next rather than by adding a field to
 * it. The scorecards already say everything needed: a box that was empty and now is not is a
 * score somebody has just taken. That keeps it entirely on the client, with nothing to write, no
 * rule to change, and no chance of the announcement disagreeing with the card it came from.
 */
object ScoreAnnounce {

    /** Long enough to read a name and a number, short enough not to sit over the next roll. */
    const val SHOW_MILLIS = 2300L

    data class Taken(val playerName: String, val label: String, val points: Int)

    /**
     * The box somebody else has just filled, or null if nothing changed.
     *
     * Only other players. Your own score is not news to you — you chose it a moment ago, and
     * [OffTheRip] already handles the one case worth remarking on.
     *
     * If several appear at once — a client catching up after being offline, say — the highest is
     * reported. Announcing them one after another would be a queue of stale news, and the biggest
     * is the one that changes anything.
     */
    fun detect(previous: GameState?, current: GameState, localPlayerId: String): Taken? {
        if (previous == null) return null

        return current.players.values
            .filter { it.id != localPlayerId }
            .mapNotNull { player ->
                val before = previous.players[player.id]?.scores ?: return@mapNotNull null
                val fresh = player.scores.keys - before.keys
                if (fresh.isEmpty()) return@mapNotNull null

                val key = fresh.maxByOrNull { player.scores[it] ?: 0 } ?: return@mapNotNull null
                val category = ScoreKey.categoryOf(key) ?: return@mapNotNull null
                Taken(
                    playerName = player.name,
                    label = category.label,
                    points = player.scores[key] ?: 0
                )
            }
            .maxByOrNull { it.points }
    }

    /** Shows [taken] in the popup the off-the-rip shout uses, with the same pop. */
    fun show(context: Context, popup: TextView, taken: Taken) {
        popup.gravity = Gravity.CENTER
        EmojiPop.show(popup, label(context, taken), SHOW_MILLIS)
    }

    private fun label(context: Context, taken: Taken): CharSequence {
        val headline = context.getString(R.string.score_taken_points, taken.points)
        val text = context.getString(
            R.string.score_taken,
            headline,
            taken.playerName,
            taken.label
        )
        return SpannableString(text).apply {
            // The number carries the announcement; the sentence under it is the detail. Sized and
            // weighted so it can be taken in without being read.
            setSpan(RelativeSizeSpan(2.1f), 0, headline.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                0,
                headline.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
