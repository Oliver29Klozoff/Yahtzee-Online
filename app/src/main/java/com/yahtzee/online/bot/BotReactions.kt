package com.yahtzee.online.bot

import com.yahtzee.online.game.Category
import kotlin.random.Random

/**
 * When a bot has something to say about a roll, and what.
 *
 * Bots played in complete silence while everyone else threw emoji at each other, which made them
 * read less like opponents than like the table keeping score. Reacting is the cheapest thing that
 * puts them in the room.
 *
 * Two rules shape all of it. A bot only speaks at moments a person would — a Yahtzee, a big
 * straight, a box thrown away for nothing — and it stays quiet most of the time even then, because
 * something that reacts to everything is noise and stops being read at all.
 *
 * It is also never rude. The row a person can send includes an emoji for telling somebody where to
 * go, and it is deliberately not reachable from here: a human choosing it is banter between two
 * people who know each other, while a bot doing it unprompted is just the app insulting whoever is
 * holding the phone.
 */
object BotReactions {

    /** Its own Yahtzee, or a straight it worked for. */
    private const val DELIGHTED = "🔥"

    /** A good roll, cheerfully. */
    private const val PLEASED = "🎲"

    /** A box given away for nothing. */
    private const val GUTTED = "😭"

    /** The same, but making a joke of itself. */
    private const val RUEFUL = "💩"

    /** Somebody else did something worth watching. */
    private const val IMPRESSED = "👏"

    /** How often a bot passes up a moment it could have reacted to. */
    private const val SPEAKS_ODDS = 0.55f

    /** The bar for a lower-section score being worth a word. */
    private const val GOOD_SCORE = 25

    /**
     * What a bot says about its own finished turn, or null to stay quiet.
     *
     * [points] is what it actually took, which is what makes a zero legible here: taking nothing
     * in a box is the one outcome a player always feels, and a bot that shrugs at it is the bot
     * feeling the same thing.
     */
    fun forOwnScore(
        category: Category,
        points: Int,
        random: Random = Random.Default
    ): String? {
        val candidate = when {
            category == Category.YAHTZEE && points > 0 -> DELIGHTED
            category == Category.LARGE_STRAIGHT && points > 0 -> DELIGHTED
            points == 0 -> if (random.nextBoolean()) GUTTED else RUEFUL
            category in Category.LOWER && points >= GOOD_SCORE -> PLEASED
            else -> null
        } ?: return null

        return candidate.takeIf { random.nextFloat() < SPEAKS_ODDS }
    }

    /**
     * What a bot says about somebody else's turn, or null.
     *
     * Only for something genuinely worth applauding. A bot clapping every score would be a bot
     * clapping, which is worth nothing — the point is that the one time it does react, it read the
     * same thing off the table that you did.
     */
    fun forOtherScore(
        category: Category,
        points: Int,
        random: Random = Random.Default
    ): String? {
        val worthIt = (category == Category.YAHTZEE && points > 0) ||
            (category == Category.LARGE_STRAIGHT && points > 0)
        if (!worthIt) return null
        return IMPRESSED.takeIf { random.nextFloat() < SPEAKS_ODDS }
    }
}
