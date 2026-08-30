package com.yahtzee.online.ui.game

import android.content.Context
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
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

    /**
     * Small enough to fit a phone width, and broad enough to cover most of what a roll deserves.
     *
     * The dart is the off-the-rip mark. That shout fires by itself when you score off the opening
     * roll, but only the player who did it sees it — so this is how you say it about somebody
     * else's roll, which is usually when it most wants saying.
     */
    val EMOJI = listOf("👏", "😂", "😱", "🔥", "🎯", "🎲", "😭")

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

    /**
     * Builds the row of buttons once. [onPick] sends; the room does the rest.
     *
     * Each tap throws its own emoji onto [burstLayer] immediately, without waiting for the write
     * to come back. That is not only about latency: a rapid tapper overwrites their own slot in
     * the room faster than it can echo, so going through the round trip would silently swallow
     * most of a flurry. Firing locally means every tap the player makes is a tap they see.
     */
    fun buildRow(
        context: Context,
        row: LinearLayout,
        burstLayer: FrameLayout,
        onPick: (String) -> Unit
    ) {
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
                // Tight, because seven of these plus the chat and nudge buttons have to share a
                // narrow phone without the row wrapping or the last emoji falling off the end.
                setPadding((5 * density).toInt(), 0, (5 * density).toInt(), 0)
                background = null
                setOnClickListener {
                    // A tap should feel like it did something even before the room answers.
                    animate().cancel()
                    scaleX = 0.8f
                    scaleY = 0.8f
                    animate().scaleX(1f).scaleY(1f).setDuration(180)
                        .setInterpolator(OvershootInterpolator(3f)).start()
                    EmojiBurst.spawn(burstLayer, emoji, "")
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
        burstLayer: FrameLayout,
        state: GameState,
        localPlayerId: String,
        lastSeen: Long
    ): Long {
        // Below zero means this screen has not seen the room yet. Whatever is already there is
        // history, not news — adopt it silently, or opening a game would replay the last thing
        // anyone said, possibly from days ago.
        if (lastSeen < 0L) return state.reactions.values.maxOfOrNull { it.second } ?: 0L

        val arrivals = state.reactions.entries
            .filter { it.value.second > lastSeen }
            // Your own are already on screen — thrown the instant you tapped, rather than when
            // the room got round to telling you about something you did yourself.
            .filterNot { it.key == localPlayerId }

        if (arrivals.isEmpty()) {
            return maxOf(lastSeen, state.reactions.values.maxOfOrNull { it.second } ?: lastSeen)
        }

        // Everyone who has said something since last time, not just the latest — with several
        // people reacting at once, showing only the newest loses the rest.
        arrivals.sortedBy { it.value.second }.forEach { entry ->
            val name = state.players[entry.key]?.name.orEmpty()
            EmojiBurst.spawn(burstLayer, entry.value.first, name)
        }

        return state.reactions.values.maxOfOrNull { it.second } ?: lastSeen
    }
}
