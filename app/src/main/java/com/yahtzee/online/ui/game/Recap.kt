package com.yahtzee.online.ui.game

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import com.yahtzee.online.R
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.RecapSeen
import com.yahtzee.online.game.ScoreKey
import com.yahtzee.online.game.grandTotalAllCards

/**
 * What happened in a game while you were not looking at it.
 *
 * A turn-a-day game tells you almost nothing when you come back to it. Somebody took their turn,
 * possibly several, and the only trace is that some numbers are different from the numbers you do
 * not remember. The scores were always there to be read; what was missing was anybody saying what
 * had changed, which is the part a person sitting at the table would have done out loud.
 *
 * Worked out by comparing the room against the last shape this device saw it in, the same way the
 * score announcement compares one snapshot to the next. Nothing is written to the room and no
 * history is kept in it — the recap is assembled on the device that missed it, for that device
 * only, which is what lets two people who were away for different lengths of time each be told
 * the right thing.
 */
object Recap {

    /** Beyond this the list stops being a summary and starts being the scorecard again. */
    private const val MAX_LINES = 6

    /** One box somebody filled while you were away. */
    data class Line(val playerName: String, val label: String, val points: Int, val card: Int)

    /**
     * Everything filled since [previous], newest last, or empty when there is nothing to say.
     *
     * A null [previous] means this device has never seen the room. That is not the same as nothing
     * having happened — it is not knowing — and recapping the whole game to somebody opening it
     * for the first time would be a wall of text about turns they were present for. Adopted
     * silently instead, exactly as reactions are.
     *
     * Your own boxes are left out. You filled them; being told is not news.
     */
    fun since(
        previous: Map<String, String>?,
        state: GameState,
        localPlayerId: String
    ): List<Line> {
        if (previous == null) return emptyList()
        val cards = state.cardCount.coerceAtLeast(1)
        val lines = mutableListOf<Line>()

        state.players.values
            .filter { it.id != localPlayerId }
            .forEach { player ->
                val before = previous[player.id].orEmpty()
                player.scores.keys.forEach { key ->
                    val slot = RecapSeen.slotOf(key, cards)
                    if (slot < 0 || RecapSeen.isFilled(before, slot)) return@forEach
                    val category = ScoreKey.categoryOf(key) ?: return@forEach
                    lines += Line(
                        playerName = player.name,
                        label = category.label,
                        points = player.scores[key] ?: 0,
                        card = ScoreKey.cardOf(key)
                    )
                }
            }

        // Biggest first, then trimmed. If somebody has been playing for a week the interesting
        // part is the Yahtzee, not the eleven boxes either side of it.
        return lines.sortedByDescending { it.points }.take(MAX_LINES)
    }

    /**
     * The recap as it reads on screen, or null when there is nothing worth interrupting for.
     *
     * The heading carries who and how much; the lines carry what. Multi-card rooms name the card
     * as well, because "took Full House" is ambiguous when there are six of them and only one is
     * the one you were watching.
     */
    fun text(
        context: Context,
        state: GameState,
        lines: List<Line>,
        multiCard: Boolean,
        reactions: List<Pair<String, String>> = emptyList()
    ): CharSequence? {
        if (lines.isEmpty() && reactions.isEmpty()) return null

        val builder = SpannableStringBuilder()
        val heading = context.getString(R.string.recap_heading)
        builder.append(heading)
        builder.setSpan(
            StyleSpan(android.graphics.Typeface.BOLD),
            0,
            heading.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        lines.forEach { line ->
            builder.append("\n")
            builder.append(
                if (multiCard) {
                    context.getString(
                        R.string.recap_line_card, line.playerName, line.label, line.card + 1, line.points
                    )
                } else {
                    context.getString(R.string.recap_line, line.playerName, line.label, line.points)
                }
            )
        }

        // Reactions in words as well as in flight.
        //
        // The flying emoji is a two-second animation that has to be on screen at the moment it
        // arrives, and every way that can fail has failed at least once: the app in a pocket, the
        // screen off, a build without the fix. Saying it here costs a line and cannot be missed,
        // because the recap is text on a panel that waits to be read rather than a thing that
        // happens. If the burst plays too, so much the better — it is the flourish, this is the
        // record.
        reactions.forEach { (name, emoji) ->
            builder.append("\n")
            builder.append(context.getString(R.string.recap_reaction, name, emoji))
        }

        // Where it leaves everybody, which is the question the list makes you ask.
        val standings = state.playerOrder
            .mapNotNull { state.players[it] }
            .joinToString("   ") { "${it.name} ${it.grandTotalAllCards(state.cardCount)}" }
        if (standings.isNotEmpty()) {
            builder.append("\n")
            builder.append(standings)
        }
        return builder
    }
}
