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
 * a chat: a fixed handful of taps needs no keyboard and no moderation queue, because the room can
 * only ever say one of seven things.
 */
object Reactions {

    /**
     * Small enough to fit a phone width, and broad enough to cover most of what a roll deserves.
     *
     * The dart used to sit here as the off-the-rip mark, from before there was a button that says
     * so in words. Two ways to make the same point is one too many, and the row is the scarcer
     * space of the two, so the button kept the job.
     *
     * Which left room for the rude one. It is what people reach for when somebody takes the box
     * they were saving, and a game between friends that cannot say so is missing something the
     * table would have said out loud. Worth knowing what it costs: this is a room a stranger can
     * join off a code, and a row that could previously only be enthusiastic can now be pointed.
     *
     * The peeking face replaced the screaming one, which was the second of two ways to say the
     * same thing — the sobbing face already covers dismay. Peeking says something the row could
     * not: watching a turn you cannot bear to watch, which in a game decided by other people's
     * dice is most of it.
     *
     * The pile of poo replaced the laughing face on the same reasoning from the other end. Two
     * faces cannot both be the laugh, and between them the crying-laughing one was the redundant
     * half — what a bad roll actually wants is a verdict on it rather than amusement at it.
     *
     * Animations for the ones that have left live on in the assets. During a rollout half the room
     * is still on a build whose row has them, and a reaction is drawn from whatever arrives rather
     * than from this list — so dropping the file would leave those players' taps landing as flat
     * font glyphs on everyone else's screen. Safe to prune once nobody is on an older build.
     */
    val EMOJI = listOf("👏", "💩", "🫣", "🔥", "🖕", "🎲", "😭")

    /**
     * What the off-the-rip button sends, carried down the reaction channel rather than a node of
     * its own.
     *
     * Reusing the channel is worth a little strangeness. A new node would mean new database rules,
     * which have to be pasted into the console by hand and which every device must be running the
     * matching build for before they land — a lot of ceremony for one button. This travels on
     * plumbing that already exists, is already validated, and is already read by the television.
     *
     * A dart with an exclamation mark rather than an invented word, because of what happens on a
     * phone that has not updated yet: it does not know the token, so it throws it as a reaction,
     * and what that player sees is a dart — which is the off-the-rip mark anyway. The shout
     * degrades into the gesture it stands for instead of into nonsense.
     *
     * The dart's own animation stays in the assets even though it has left the row above. Unlike
     * the other retired ones it has a permanent reason to: this token is drawn from it by any
     * caller that passes no shout handler, so it outlives the rollout.
     */
    const val OFF_THE_RIP = "🎯!"

    /** Whether an arriving reaction is the shout rather than an emoji somebody threw. */
    fun isShout(emoji: String): Boolean = emoji == OFF_THE_RIP

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
     * The reactions in [reactions] that are news, given what has already been shown.
     *
     * A mark per player rather than one for the room, which is the whole point.
     *
     * A reaction is stamped with the clock of the phone that sent it, and no two phones agree.
     * Held as a single high-water mark, the room's newest timestamp came from whichever device was
     * running fastest — and the mark ratcheted up over your own reactions too. So the moment you
     * reacted, you set the bar to your own clock, and anybody whose phone was running behind yours
     * became invisible to you until their clock caught up. Between the two phones here that is
     * fifty-odd milliseconds; against a device that has drifted it is seconds, and for as long as
     * it lasts their emoji are silently dropped rather than shown late.
     *
     * Keyed by player, every comparison is between two readings of the same clock, which is the
     * only comparison that means anything. Skew stops mattering entirely.
     *
     * A null [lastSeen] means this screen has not seen the room yet: whatever is already there is
     * history rather than news, and replaying it would greet you with the last thing anyone said,
     * possibly days ago.
     */
    fun arrivalsSince(
        reactions: Map<String, Pair<String, Long>>,
        localPlayerId: String,
        lastSeen: Map<String, Long>?,
        notOlderThan: Long? = null
    ): List<Map.Entry<String, Pair<String, Long>>> {
        if (lastSeen == null) return emptyList()
        return reactions.entries
            // Your own are already on screen — thrown the instant you tapped, rather than when
            // the room got round to telling you about something you did yourself.
            .filterNot { it.key == localPlayerId }
            .filter { entry ->
                val seen = lastSeen[entry.key]
                // Nothing seen from this player yet means they joined and reacted since; their
                // first is news by definition.
                seen == null || entry.value.second > seen
            }
            .filter { notOlderThan == null || it.value.second >= notOlderThan }
            .sortedBy { it.value.second }
    }

    /**
     * How far back a game reaches for reactions when you open it.
     *
     * The point is to catch the ones sent while you were on your way to the app rather than to
     * replay a day of them. Somebody reacts, you open the game to take your turn, and you see it —
     * which is what people expect and what was silently not happening.
     */
    const val REPLAY_WINDOW_MS = 5 * 60_000L

    /**
     * The cutoff for that window, against this device's clock.
     *
     * This is a cross-clock comparison — the sender's timestamp against our own now — which is
     * exactly the thing [arrivalsSince] refuses to do when deciding what is new. It is safe here
     * and not there because of what the two comparisons are for. Deciding whether one reaction
     * came after another is a strict ordering, and a phone a second out flips it; deciding whether
     * something happened in the last five minutes is a rough question, and a phone a second out
     * moves the edge of a five-minute window by a second. Do not tighten this into an exact one.
     */
    fun replayCutoff(now: Long = System.currentTimeMillis()): Long = now - REPLAY_WINDOW_MS

    /** The mark to carry into the next snapshot: where every player's clock has reached. */
    fun marksFrom(reactions: Map<String, Pair<String, Long>>): Map<String, Long> =
        reactions.mapValues { it.value.second }

    /**
     * Shows whatever has arrived since last time.
     *
     * [lastSeen] is what each player's clock had reached when this screen last looked, and the
     * return value replaces it. Comparing timestamps rather than values is what lets the same
     * emoji sent twice register twice — and what stops every unrelated update to the room
     * replaying the last one.
     */
    fun render(
        burstLayer: FrameLayout,
        state: GameState,
        localPlayerId: String,
        lastSeen: Map<String, Long>?,
        captionSp: Float = EmojiBurst.CAPTION_SP,
        onShout: ((String) -> Unit)? = null,
        notOlderThan: Long? = null
    ): Map<String, Long> {
        val arrivals = arrivalsSince(state.reactions, localPlayerId, lastSeen, notOlderThan)

        // Everyone who has said something since last time, not just the latest — with several
        // people reacting at once, showing only the newest loses the rest.
        arrivals.forEach { entry ->
            val name = state.players[entry.key]?.name.orEmpty()
            // The shout is words rather than a flying emoji, so it goes to the popup instead of
            // the burst layer. A caller with nowhere to put it throws the token as an ordinary
            // reaction, which draws the dart it is made of — the same thing an older build does.
            if (isShout(entry.value.first) && onShout != null) {
                onShout(name)
            } else {
                EmojiBurst.spawn(burstLayer, entry.value.first, name, captionSp)
            }
        }

        return marksFrom(state.reactions)
    }
}
